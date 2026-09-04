package com.classsentinel.core.speech

import com.classsentinel.core.audio.VadSplitter
import com.classsentinel.core.audio.WavSegment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * 旧 `Flow<ShortArray> -> Flow<String>` 路径的适配器。
 *
 * 不再在引擎内复制 VAD：复用 [VadSplitter.segments] 把 PCM 切成 [WavSegment]，
 * 逐段调用 [SegmentSpeechEngine.transcribeSegment]（新单段主路径）。
 * 失败不静默吞错：抛携带 [AsrError] 的 [AsrException]，由旧 FallbackSpeechEngine
 * 以异常切换引擎 / 上层呈现失败。成功段只 emit 一次。
 */
class LegacySpeechAdapter(
    private val engine: SegmentSpeechEngine,
    private val vad: VadSplitter = VadSplitter(),
    private val onSegmentTranscribed: (suspend (WavSegment, String) -> Unit)? = null,
) : SpeechEngine, SegmentAwareSpeechEngine {

    override val name: String get() = engine.name

    override fun transcribe(pcm: Flow<ShortArray>): Flow<String> =
        transcribeSegments(pcm).map { it.text }

    override fun transcribeSegments(pcm: Flow<ShortArray>): Flow<TranscribedSegment> = flow {
        vad.segments(pcm).collect { segment: WavSegment ->
            val text = engine.transcribeSegment(segment).getOrThrow()
            onSegmentTranscribed?.invoke(segment, text)
            emit(TranscribedSegment(segment, text))
        }
    }
}
