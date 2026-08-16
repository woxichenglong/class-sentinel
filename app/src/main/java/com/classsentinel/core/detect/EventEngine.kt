package com.classsentinel.core.detect

import kotlinx.coroutines.flow.StateFlow

enum class EventType { ROLLCALL, QUESTION }

/** 课堂事件：点名 / 提问 */
data class ClassEvent(
    val type: EventType,
    val triggerText: String, // 触发句
    val context: String,     // 上下文（当前版本=触发句，后续可带前后文）
    val ts: Long,
)

/**
 * 事件引擎：转写句 → 课堂事件。
 * 点名优先于提问；两类事件独立抑制窗口；灵敏度从 StateFlow 热读。
 */
class EventEngine(
    private val nameMatcher: NameMatcher,
    private val sensitivityFlow: StateFlow<Sensitivity>,
) {
    private var lastRollcallTs = 0L
    private var lastQuestionTs = 0L

    fun process(segment: String, ts: Long = System.currentTimeMillis()): ClassEvent? {
        val sens = sensitivityFlow.value

        // 1. 点名优先
        nameMatcher.detect(segment, sens)?.let { hit ->
            if (lastRollcallTs == 0L || ts - lastRollcallTs >= sens.rollcallSuppressMs) {
                lastRollcallTs = ts
                return ClassEvent(EventType.ROLLCALL, segment, segment, ts)
            }
            return null // 抑制窗口内；命中点名时不降级为提问
        }

        // 2. 提问
        QuestionDetector.detect(segment, sens.questionWordLevel)?.let {
            if (lastQuestionTs == 0L || ts - lastQuestionTs >= sens.questionSuppressMs) {
                lastQuestionTs = ts
                return ClassEvent(EventType.QUESTION, segment, segment, ts)
            }
        }
        return null
    }
}
