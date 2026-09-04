package com.classsentinel.core.speech

import com.classsentinel.core.audio.WavSegment

/**
 * v0.2 Task 4（后半）：单段 ASR 引擎统一接口。
 *
 * 面向 [WavSegment] 的挂起转写：一次调用只转写一个已由 VAD 切好的段，
 * 引擎内部不再做 VAD、不吞错。失败返回 [Result.failure] 并携带 [AsrException]，
 * 其 [AsrException.error] 是 [AsrError] typed failure，供上层路由/UI 精确决策。
 *
 * 后续新 router 可直接依赖本接口（[OpenAiCompatAsrEngine] 直接实现它）。
 */
interface SegmentSpeechEngine {
    val name: String

    /**
     * 转写单个 [WavSegment]。
     * 成功返回 [Result.success]（非空文本）；失败返回 [Result.failure]
     * （[AsrException] 携带可检查的 [AsrError]，不会静默吞掉）。
     */
    suspend fun transcribeSegment(segment: WavSegment): Result<String>
}
