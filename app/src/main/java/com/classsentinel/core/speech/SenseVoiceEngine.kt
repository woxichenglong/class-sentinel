package com.classsentinel.core.speech

import com.classsentinel.core.audio.VadSplitter

/** 兜底引擎：SenseVoiceSmall（硅基流动免费，已标 Deprecated 仅灾备） */
class SenseVoiceEngine(
    apiKey: String,
    vad: VadSplitter = VadSplitter(),
) : OpenAiCompatAsrEngine(
    name = "SenseVoiceSmall",
    baseUrl = "https://api.siliconflow.cn/v1",
    apiKey = apiKey,
    model = "FunAudioLLM/SenseVoiceSmall",
    vad = vad,
)
