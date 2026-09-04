package com.classsentinel.core.speech

import com.classsentinel.core.audio.VadSplitter

/** 主力引擎：硅基流动 XingChenASR-V3.2-Ultra。复用 OpenAiCompatAsrEngine 单段主路径。 */
class TeleSpeechEngine(
    apiKey: String,
    vad: VadSplitter = VadSplitter(),
    client: okhttp3.OkHttpClient = OpenAiCompatAsrEngine.defaultClient(),
) : OpenAiCompatAsrEngine(
    name = "XingChenASR-V3.2-Ultra",
    baseUrl = "https://api.siliconflow.cn/v1",
    apiKey = apiKey,
    model = "XingChenAGI/XingChenASR-V3.2-Ultra",
    vad = vad,
    client = client,
)
