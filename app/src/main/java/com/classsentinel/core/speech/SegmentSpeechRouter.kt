package com.classsentinel.core.speech

import com.classsentinel.core.audio.WavSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/**
 * v0.2 Task 5：单段 ASR 路由（主引擎有限重试 + 同段 fallback）。
 *
 * 输入一个 [WavSegment]，输出携带 segmentId / engine / text 的成功结果，
 * 或携带最后一个（最有用）[AsrException] 的 typed failure。
 *
 * 规则：
 * - 每个 segment 都从 [primary] 开始；一次 primary 故障不会永久放弃 primary；
 * - 主引擎 transient 失败（NETWORK / RATE_LIMIT / SERVER，或可归一化的普通
 *   [IOException] → NETWORK）：对**同一个** segment 做有界重试（[maxPrimaryRetries] 次，
 *   延迟以 [retryDelayMillis] 为 base 的有界指数退避，封顶 [maxRetryDelayMillis]），
 *   随后依次尝试 [fallbacks]；
 * - fallback 必须接收与 primary 完全相同的 [WavSegment] 对象（同 id / 同 bytes），
 *   绝不重新从 PCM/VAD 开始；
 * - EMPTY 允许尝试 fallback（尽管 `AsrError.retriable=false`）；AUTH / CONFIG / UNKNOWN
 *   不重试、不锤 fallback，直接按 typed failure 返回；
 * - 成功的 segment 不会被再次调用/发射（s1 成功不会因 s2 失败被重放）；
 * - [events] StateFlow 提供轻量可观测信号：每次引擎调用/切换/结束发布一条
 *   [RouterEvent]，只含 engine / segmentId / 状态，不含文本、key、body；
 * - 失败段 seam [onSegmentFailed]：一段在 primary + fallback 全链路失败、即将返回
 *   typed failure 时**恰好**回调一次（同一段不重复回调；primary/fallback 成功路径
 *   不触发），回调收到同一 [WavSegment] 对象与最终 [AsrException]，可交给持久化等
 *   外部消费方。回调侧只应消费 segmentId / typed error，不得打印/落库音频文本、
 *   API key 或请求 body。
 *
 * 取消语义：引擎抛出的 [CancellationException] 立即原样上抛，不进入重试、
 * 不降级、不吞成 failure。
 */
class SegmentSpeechRouter(
    private val primary: SegmentSpeechEngine,
    private val fallbacks: List<SegmentSpeechEngine> = emptyList(),
    /** 主引擎 transient 失败后的有界重试次数（不含首次尝试），>=0。 */
    private val maxPrimaryRetries: Int = 1,
    /** 重试间隔 base（毫秒），默认 0；退避 1x/2x/4x… 饱和到 [maxRetryDelayMillis]，测试可注入避免等待。 */
    private val retryDelayMillis: Long = 0L,
    /** 指数退避上限（毫秒），默认 5 秒；饱和计算，不 Long 溢出。 */
    private val maxRetryDelayMillis: Long = 5_000L,
    /** 连续 primary transient 失败达到该阈值后进入 cooldown（按段计数）。 */
    private val cooldownAfterPrimaryFailures: Int = 3,
    /** cooldown 期间跳过 primary、直接走 fallback 的段数上限。 */
    private val primaryCooldownSegments: Int = 1,
    /** 可注入的延迟回调（测试用确定性时钟）；默认走 [delay]。 */
    private val onDelay: (suspend (Long) -> Unit)? = null,
    /**
     * 失败段 seam：当一段在 primary + fallback 全链路失败、即将返回 typed failure 时
     * 恰好调用一次（同一段不重复回调；成功路径不触发）。回调收到与输入同一
     * [WavSegment] 对象和最终 [AsrException]，可用于把失败段交给持久化等外部消费方。
     * 安全约束：回调侧只应消费 segmentId / typed error，不得打印/落库音频文本、
     * API key 或请求 body；回调自身异常（如持久化失败）**原样向调用方上抛**，
     * 使失败段不会被静默丢弃；[CancellationException] 同样原样传播、不得吞。
     */
    private val onSegmentFailed: (suspend (WavSegment, AsrException) -> Unit)? = null,
) {
    /** 引擎调用/切换/结束的轻量可观测事件（不含文本、key、body）。 */
    data class RouterEvent(
        val segmentId: String,
        val engine: String,
        val status: Status,
    ) {
        enum class Status { ATTEMPT, RETRY, FALLBACK, SUCCESS, FAILURE }
    }

    /** 单段转写成功结果：携带原始 segmentId、实际引擎名、文本。 */
    data class SegmentResult(
        val segmentId: String,
        val engine: String,
        val text: String,
    )

    private val _events = MutableStateFlow<List<RouterEvent>>(emptyList())
    /** 追加式事件流：只增不减，测试可确认 fallback 发生、engine/segment 未错配。 */
    val events: StateFlow<List<RouterEvent>> = _events.asStateFlow()

    /** primary 连续 transient 失败计数（跨 segment 累计，成功时清零）。 */
    private var consecutivePrimaryTransientFailures = 0
    /** 当前 cooldown 剩余段数（>0：跳过 primary 直接走 fallback，逐段消费）。 */
    private var primaryCooldownRemainingSegments = 0

    private fun emit(segmentId: String, engine: String, status: RouterEvent.Status) {
        _events.value = _events.value + RouterEvent(segmentId, engine, status)
    }

    /**
     * 第 [retryNumber] 次重试前的退避延迟（毫秒）：第 1 次 = base，之后倍增，
     * 饱和到 [maxRetryDelayMillis]。饱和先于倍增计算，避免 Long 溢出。
     */
    private fun backoffMillis(retryNumber: Int): Long {
        if (retryDelayMillis <= 0L) return 0L
        var millis = retryDelayMillis
        var remaining = retryNumber - 1
        while (remaining > 0 && millis < maxRetryDelayMillis) {
            millis = if (millis > maxRetryDelayMillis / 2) maxRetryDelayMillis else millis * 2
            remaining--
        }
        return millis.coerceAtMost(maxRetryDelayMillis)
    }

    private suspend fun pause(retryNumber: Int) {
        val sleeper = onDelay ?: { millis -> delay(millis) }
        sleeper(backoffMillis(retryNumber))
    }

    /** 错误归一化：优先 [AsrException.error]；普通 [IOException] 映射为可重试 NETWORK。 */
    private fun normalize(t: Throwable): AsrException =
        when (t) {
            is AsrException -> t
            is IOException -> AsrException(AsrError.network(t.message ?: "network error"))
            else -> AsrException(AsrError(AsrError.Kind.UNKNOWN, retriable = false))
        }

    /** 单段是否值得重试/降级（transient 或 EMPTY 允许尝试 fallback）。 */
    private fun canAttemptFallback(error: AsrError): Boolean = when (error.kind) {
        AsrError.Kind.NETWORK, AsrError.Kind.RATE_LIMIT, AsrError.Kind.SERVER, AsrError.Kind.EMPTY -> true
        AsrError.Kind.AUTH, AsrError.Kind.CONFIG, AsrError.Kind.UNKNOWN -> false
    }

    private fun isTransientRetriable(error: AsrError): Boolean =
        error.retriable && (error.kind == AsrError.Kind.NETWORK ||
            error.kind == AsrError.Kind.RATE_LIMIT ||
            error.kind == AsrError.Kind.SERVER)

    /**
     * 失败收口：在 [transcribeSegment] 决定对 [segment] 返回 typed failure 前调用
     * [onSegmentFailed] **恰好一次**。回调只应消费 segmentId / typed error（不得打印/
     * 落库音频文本、API key 或请求 body）；回调自身异常（如持久化失败）**原样向
     * 调用方上抛**，使失败段不会被静默丢弃；[CancellationException] 同样原样传播、不得吞。
     */
    private suspend fun invokeSegmentFailed(segment: WavSegment, error: AsrException) {
        onSegmentFailed?.invoke(segment, error)
    }

    /**
     * 转写单个 [segment]：主引擎 → 有界重试 → fallback 链。
     * 返回 [Result.success]（[SegmentResult]）或 [Result.failure]（[AsrException]）。
     */
    suspend fun transcribeSegment(segment: WavSegment): Result<SegmentResult> {
        val engineChain = listOf(primary) + fallbacks
        var lastError: AsrException? = null
        // 单段内是否消费过一个 cooldown 段（防同一段内多引擎/多尝试重复消费）
        var consumedCooldownSegment = false

        // cooldown 只在有 fallback 时生效：没有 fallback 时绝不因 cooldown 跳过 primary
        if (primaryCooldownRemainingSegments > 0 && fallbacks.isNotEmpty()) {
            primaryCooldownRemainingSegments--
            consumedCooldownSegment = true
            // primary 未被调用，直接走 fallback 链（fallback 收到同一段），不发射 primary ATTEMPT
            for (engine in fallbacks) {
                emit(segment.id, engine.name, RouterEvent.Status.FALLBACK)
                val outcome: Outcome = try {
                    val result = engine.transcribeSegment(segment)
                    when {
                        result.isSuccess -> Outcome.Success(engine, result.getOrThrow())
                        else -> {
                            val exception = result.exceptionOrNull()
                            if (exception is CancellationException) throw exception
                            Outcome.Failure(engine, normalize(exception ?: AsrException(AsrError(AsrError.Kind.UNKNOWN, retriable = false))))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e // 取消立即上抛：不重试、不降级、不吞
                } catch (t: Throwable) {
                    Outcome.Failure(engine, normalize(t))
                }
                when (outcome) {
                    is Outcome.Success -> {
                        emit(segment.id, outcome.engine.name, RouterEvent.Status.SUCCESS)
                        return Result.success(
                            SegmentResult(
                                segmentId = segment.id,
                                engine = outcome.engine.name,
                                text = outcome.text,
                            ),
                        )
                    }
                    is Outcome.Failure -> {
                        val error = outcome.error
                        lastError = error
                        emit(segment.id, outcome.engine.name, RouterEvent.Status.FAILURE)
                        if (!canAttemptFallback(error.error)) {
                            invokeSegmentFailed(segment, error)
                            return Result.failure(error)
                        }
                    }
                }
            }
            invokeSegmentFailed(segment, lastError ?: AsrException(AsrError(AsrError.Kind.UNKNOWN, retriable = false)))
            return Result.failure(lastError ?: AsrException(AsrError(AsrError.Kind.UNKNOWN, retriable = false)))
        }

        for ((engineIndex, engine) in engineChain.withIndex()) {
            val isPrimary = engineIndex == 0
            val attempts = if (isPrimary) maxPrimaryRetries + 1 else 1

            for (attempt in 1..attempts) {
                val status = when {
                    isPrimary && attempt > 1 -> RouterEvent.Status.RETRY
                    !isPrimary -> RouterEvent.Status.FALLBACK
                    else -> RouterEvent.Status.ATTEMPT
                }
                emit(segment.id, engine.name, status)

                val outcome: Outcome = try {
                    val result = engine.transcribeSegment(segment)
                    when {
                        result.isSuccess -> Outcome.Success(engine, result.getOrThrow())
                        else -> {
                            val exception = result.exceptionOrNull()
                            if (exception is CancellationException) throw exception
                            Outcome.Failure(engine, normalize(exception ?: AsrException(AsrError(AsrError.Kind.UNKNOWN, retriable = false))))
                        }
                    }
                } catch (e: CancellationException) {
                    throw e // 取消立即上抛：不重试、不降级、不吞
                } catch (t: Throwable) {
                    Outcome.Failure(engine, normalize(t))
                }

                when (outcome) {
                    is Outcome.Success -> {
                        // primary 成功：清零连续失败计数，解除 cooldown
                        if (isPrimary) {
                            consecutivePrimaryTransientFailures = 0
                            primaryCooldownRemainingSegments = 0
                        }
                        emit(segment.id, outcome.engine.name, RouterEvent.Status.SUCCESS)
                        return Result.success(
                            SegmentResult(
                                segmentId = segment.id,
                                engine = outcome.engine.name,
                                text = outcome.text,
                            ),
                        )
                    }

                    is Outcome.Failure -> {
                        val error = outcome.error
                        lastError = error
                        emit(segment.id, outcome.engine.name, RouterEvent.Status.FAILURE)

                        // 非 transient（AUTH/CONFIG/UNKNOWN）：不重试、不降级，直接返回
                        if (!canAttemptFallback(error.error)) {
                            invokeSegmentFailed(segment, error)
                            return Result.failure(error)
                        }
                        // transient 且本引擎还有尝试配额：同一段重试（有界指数退避）
                        if (attempt < attempts && isTransientRetriable(error.error)) {
                            pause(retryNumber = attempt)
                            continue
                        }
                        // primary transient 失败且本段配额已耗尽：计入连续失败，达到阈值则触发 cooldown
                        if (isPrimary && isTransientRetriable(error.error)) {
                            if (!consumedCooldownSegment) {
                                consecutivePrimaryTransientFailures++
                            }
                            if (consecutivePrimaryTransientFailures >= cooldownAfterPrimaryFailures) {
                                primaryCooldownRemainingSegments = primaryCooldownSegments
                            }
                        }
                        // EMPTY（不重试）或已耗尽重试：退出本引擎，进入 fallback 链
                        break
                    }
                }
            }
        }

        // 全部引擎失败：返回携带最后一个（最有用）typed error 的 failure
        invokeSegmentFailed(segment, lastError ?: AsrException(AsrError(AsrError.Kind.UNKNOWN, retriable = false)))
        return Result.failure(lastError ?: AsrException(AsrError(AsrError.Kind.UNKNOWN, retriable = false)))
    }

    private sealed interface Outcome {
        data class Success(val engine: SegmentSpeechEngine, val text: String) : Outcome
        data class Failure(val engine: SegmentSpeechEngine, val error: AsrException) : Outcome
    }
}
