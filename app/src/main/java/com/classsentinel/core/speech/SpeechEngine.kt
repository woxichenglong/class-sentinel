package com.classsentinel.core.speech

import kotlinx.coroutines.flow.Flow

/**
 * ASR 引擎统一接口：输入 PCM 流，输出句子/段落文本流。
 * 切段方式由引擎内部决定（HTTP 批量引擎内置 VAD 分段；流式引擎直连）。
 */
interface SpeechEngine {
    val name: String
    fun transcribe(pcm: Flow<ShortArray>): Flow<String>
}
