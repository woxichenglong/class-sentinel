package com.classsentinel.core.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withTimeoutOrNull

/** Observable, privacy-safe answer generation state. */
sealed interface AnswerResult {
    data object Generating : AnswerResult

    /** Replaceable live answer text; it is never persisted to the event history. */
    data class Streaming(val text: String) : AnswerResult

    data class Succeeded(val answer: String) : AnswerResult

    data class Insufficient(val question: String) : AnswerResult

    data class Failed(val safeCode: String) : AnswerResult {
        init {
            require(safeCode in SAFE_CODES) { "safeCode must be a known safe category" }
        }

        companion object {
            private val SAFE_CODES = buildSet {
                LlmError.Kind.values().forEach { add(it.name) }
                add("LLM_TIMEOUT")
                add("LLM_REQUEST")
                add("ANSWER_SAVE")
            }
        }
    }
}

/** Exact wire sentinel used when the model cannot answer from the supplied evidence. */
internal const val INSUFFICIENT_ANSWER_SENTINEL = "[[INSUFFICIENT]]"

/** Fixed user-facing copy for safe LLM categories; provider details never cross this boundary. */
internal fun answerFailureMessage(safeCode: String): String = when (safeCode) {
    "AUTH" -> "API Key 认证失败（401），请检查配置"
    "FORBIDDEN" -> "AI 服务拒绝访问（403），请检查权限"
    "NOT_FOUND" -> "AI 接口不存在（404），请检查地址"
    "MODEL_UNSUPPORTED" -> "模型不存在或不受支持，请检查模型名"
    "CAPABILITY_UNSUPPORTED" -> "当前模型不支持 ClassSentinel 所需能力"
    "CONFIG" -> "检查 AI 配置"
    "DNS" -> "无法解析 AI 服务域名（DNS）"
    "NETWORK" -> "网络异常，请稍后重试"
    "RATE_LIMIT" -> "请求过于频繁，请稍后重试"
    "QUOTA_EXHAUSTED" -> "AI 额度已耗尽，请充值或更换账户"
    "SERVER" -> "AI 服务异常（5xx），请稍后重试"
    "TIMEOUT" -> "AI 请求超时，请稍后重试"
    "LLM_TIMEOUT" -> "请求超时"
    "EMPTY", "INVALID_RESPONSE" -> "AI 返回格式异常"
    "UNKNOWN", "LLM_REQUEST" -> "生成失败"
    else -> "生成失败"
}

/** Collects one answer stream with independent first-delta, idle, and total deadlines. */
fun answerResults(
    question: String,
    deltas: Flow<String>,
    timeoutMs: Long = 30_000L,
    firstDeltaTimeoutMs: Long = 8_000L,
    idleTimeoutMs: Long = 8_000L,
    streamOutput: Boolean = false,
): Flow<AnswerResult> = flow {
    require(timeoutMs > 0L) { "timeoutMs must be positive" }
    require(firstDeltaTimeoutMs > 0L) { "firstDeltaTimeoutMs must be positive" }
    require(idleTimeoutMs > 0L) { "idleTimeoutMs must be positive" }
    emit(AnswerResult.Generating)
    try {
        val outcome = coroutineScope {
            val channel = deltas.filter(String::isNotEmpty).produceIn(this)
            try {
                withTimeoutOrNull(timeoutMs) {
                    collectAnswer(channel, firstDeltaTimeoutMs, idleTimeoutMs) { textSoFar ->
                        if (streamOutput) {
                            val normalized = textSoFar.trim()
                            if (normalized.isNotBlank() &&
                                normalized != INSUFFICIENT_ANSWER_SENTINEL &&
                                !INSUFFICIENT_ANSWER_SENTINEL.startsWith(normalized)
                            ) {
                                emit(AnswerResult.Streaming(textSoFar))
                            }
                        }
                    }
                } ?: CollectedAnswer.TimedOut
            } finally {
                channel.cancel()
            }
        }
        if (outcome is CollectedAnswer.TimedOut) {
            emit(AnswerResult.Failed("LLM_TIMEOUT"))
        } else if (outcome.answer.isBlank() || outcome.answer == INSUFFICIENT_ANSWER_SENTINEL) {
            emit(AnswerResult.Insufficient(question))
        } else {
            emit(AnswerResult.Succeeded(outcome.answer))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: LlmException) {
        emit(AnswerResult.Failed(e.error.safeCode))
    } catch (_: Exception) {
        emit(AnswerResult.Failed("LLM_REQUEST"))
    }
}

private sealed interface CollectedAnswer {
    val answer: String

    data class Completed(override val answer: String) : CollectedAnswer
    data object TimedOut : CollectedAnswer {
        override val answer: String = ""
    }
}

private suspend fun collectAnswer(
    channel: ReceiveChannel<String>,
    firstDeltaTimeoutMs: Long,
    idleTimeoutMs: Long,
    onDelta: suspend (String) -> Unit,
): CollectedAnswer {
    val answer = StringBuilder()
    var firstDelta = true
    while (true) {
        val next = withTimeoutOrNull(if (firstDelta) firstDeltaTimeoutMs else idleTimeoutMs) {
            channel.receiveCatching()
        } ?: return CollectedAnswer.TimedOut
        next.exceptionOrNull()?.let { throw it }
        if (next.isClosed) return CollectedAnswer.Completed(answer.toString().trim())
        answer.append(next.getOrThrow())
        firstDelta = false
        onDelta(answer.toString())
    }
}
