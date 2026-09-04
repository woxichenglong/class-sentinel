package com.classsentinel.core.speech

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * 容灾链：按顺序尝试引擎，当前引擎流异常则切下一个。
 * 全部引擎失败一轮后结束（由上层 ListenPipeline 呈现 Error 状态）。
 * 暴露 activeEngine 供 UI 显示当前活跃引擎。
 *
 * 备用引擎连续成功 3 句后回切主引擎，给主引擎恢复机会；如果主引擎再次失败，
 * 仍按原顺序降级。生产分段主路径由 [SegmentSpeechRouter] 负责同段 fallback，
 * 本类保留给旧的长流调用方。
 */
class FallbackSpeechEngine(
    private val engines: List<SpeechEngine>,
) : SpeechEngine {

    override val name: String get() = "fallback-chain"

    private val _activeEngine = MutableStateFlow(engines.firstOrNull()?.name ?: "none")
    val activeEngine: StateFlow<String> = _activeEngine

    override fun transcribe(pcm: Flow<ShortArray>): Flow<String> = flow {
        if (engines.isEmpty()) {
            throw java.io.IOException("没有可用的 ASR 引擎")
        }
        var index = 0
        var failedAttempts = 0
        var fallbackSuccesses = 0
        while (failedAttempts < engines.size) {
            val engine = engines[index]
            _activeEngine.value = engine.name
            try {
                engine.transcribe(pcm).collect { text ->
                    emit(text)
                    if (index > 0) {
                        fallbackSuccesses++
                        if (fallbackSuccesses >= FALLBACK_SUCCESS_THRESHOLD) {
                            throw ReenterPrimaryException
                        }
                    }
                }
                return@flow // 引擎自然结束（pcm 结束），任务完成
            } catch (_: ReenterPrimaryException) {
                // 取消当前备用引擎的收集后，从下一段数据重新给主引擎一次机会。
                index = 0
                failedAttempts = 0
                fallbackSuccesses = 0
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failedAttempts++
                fallbackSuccesses = 0
                index = (index + 1) % engines.size
            }
        }
        // 全部引擎失败：抛异常让 ListenPipeline 置 Error 状态（P0 修复：静默卡死无感知）
        throw java.io.IOException("所有 ASR 引擎均失败（已尝试 ${engines.size} 个）")
    }

    companion object {
        const val FALLBACK_SUCCESS_THRESHOLD = 3
    }
}

private object ReenterPrimaryException : Exception()
