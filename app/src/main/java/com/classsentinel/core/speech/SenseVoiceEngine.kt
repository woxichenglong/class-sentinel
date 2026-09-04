package com.classsentinel.core.speech

import com.classsentinel.core.audio.VadSplitter

/** 兜底引擎：SenseVoiceSmall（硅基流动免费，已标 Deprecated 仅灾备）。复用 OpenAiCompatAsrEngine 单段主路径。 */
class SenseVoiceEngine(
    apiKey: String,
    vad: VadSplitter = VadSplitter(),
    client: okhttp3.OkHttpClient = OpenAiCompatAsrEngine.defaultClient(),
) : OpenAiCompatAsrEngine(
    name = "SenseVoiceSmall",
    baseUrl = "https://api.siliconflow.cn/v1",
    apiKey = apiKey,
    model = "FunAudioLLM/SenseVoiceSmall",
    vad = vad,
    client = client,
)
