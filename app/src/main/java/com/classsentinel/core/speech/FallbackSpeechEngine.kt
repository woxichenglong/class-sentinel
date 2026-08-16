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
 * TODO(v2): 备引擎成功 3 句后回切主引擎（防横跳）；当前版本只做单向降级。
 */
class FallbackSpeechEngine(
    private val engines: List<SpeechEngine>,
) : SpeechEngine {

    override val name: String get() = "fallback-chain"

    private val _activeEngine = MutableStateFlow(engines.firstOrNull()?.name ?: "none")
    val activeEngine: StateFlow<String> = _activeEngine

    override fun transcribe(pcm: Flow<ShortArray>): Flow<String> = flow {
        var index = 0
        var triedRounds = 0
        while (triedRounds < engines.size) {
            val engine = engines[index]
            _activeEngine.value = engine.name
            var failed = false
            try {
                engine.transcribe(pcm).collect { emit(it) }
                return@flow // 引擎自然结束（pcm 结束），任务完成
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
            }
            if (failed) {
                triedRounds++
                index = (index + 1) % engines.size
            }
        }
        // 全部引擎失败：抛异常让 ListenPipeline 置 Error 状态（P0 修复：静默卡死无感知）
        throw java.io.IOException("所有 ASR 引擎均失败（已尝试 ${engines.size} 个）")
    }
}
