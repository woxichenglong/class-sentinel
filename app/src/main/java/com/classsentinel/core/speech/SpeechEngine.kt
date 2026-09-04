package com.classsentinel.core.speech

import com.classsentinel.core.audio.WavSegment
import kotlinx.coroutines.flow.Flow

/**
 * ASR 引擎统一接口：输入 PCM 流，输出句子/段落文本流。
 * 切段方式由引擎内部决定（HTTP 批量引擎内置 VAD 分段；流式引擎直连）。
 */
interface SpeechEngine {
    val name: String
    fun transcribe(pcm: Flow<ShortArray>): Flow<String>
}

/** 带原始分段元数据的转写结果；用于可选音频留存，不携带凭证或日志副作用。 */
data class TranscribedSegment(
    val segment: WavSegment,
    val text: String,
)

/** 可选的单段元数据出口；旧的 [SpeechEngine] 调用方仍只消费文本流。 */
interface SegmentAwareSpeechEngine {
    fun transcribeSegments(pcm: Flow<ShortArray>): Flow<TranscribedSegment>
}
