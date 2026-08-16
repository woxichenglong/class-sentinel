package com.classsentinel.core.speech

import com.classsentinel.core.audio.VadSplitter

/** 主力引擎：电信 TeleSpeechASR（60 方言混说，免费） */
class TeleSpeechEngine(
    apiKey: String,
    vad: VadSplitter = VadSplitter(),
) : OpenAiCompatAsrEngine(
    name = "TeleSpeechASR",
    baseUrl = "https://api.siliconflow.cn/v1",
    apiKey = apiKey,
    model = "TeleAI/TeleSpeechASR",
    vad = vad,
)
