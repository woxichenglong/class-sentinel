package com.classsentinel.core.pipeline

import com.classsentinel.core.audio.AudioStreamer
import com.classsentinel.core.speech.SpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 监听管道：PCM → 引擎 → 句子文本流。
 * 只负责串联与状态呈现；引擎自身异常由 FallbackSpeechEngine 重连消化。
 */
class ListenPipeline(
    private val streamer: AudioStreamer,
    private val speech: SpeechEngine,
) {
    val state = MutableStateFlow<PipelineState>(PipelineState.Idle)
    private val _segments = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val segments: SharedFlow<String> = _segments

    fun start(scope: CoroutineScope) {
        scope.launch {
            var count = 0
            try {
                speech.transcribe(streamer.pcm()).collect { text ->
                    count++
                    state.value = PipelineState.Listening(count)
                    _segments.emit(text)
                }
            } catch (e: Exception) {
                state.value = PipelineState.Error("转写中断: ${e.message}")
            }
        }
    }
}
