package com.classsentinel.core.speech

/**
 * v0.2 Task 3：类型化语音事件契约（sealed）。
 *
 * 供 Pipeline/UI 订阅的纯数据事件；字段只含业务数据，
 * 不包含日志、凭证或其他副作用。
 */
sealed interface SpeechEvent {
    /** 一段最终识别文本。 */
    data class Text(
        val segmentId: String,
        val text: String,
    ) : SpeechEvent

    /** ASR 引擎切换（如降级/恢复）。 */
    data class EngineChanged(
        val engine: String,
    ) : SpeechEvent

    /** 某段转写失败后的恢复中状态。 */
    data class Recovering(
        val segmentId: String,
        val message: String,
    ) : SpeechEvent

    /** 转写失败；[segmentId] 为 null 表示尚未产生分段。 */
    data class Failed(
        val segmentId: String?,
        val error: AsrError,
    ) : SpeechEvent
}
