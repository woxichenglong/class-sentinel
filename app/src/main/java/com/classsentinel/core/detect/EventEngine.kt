package com.classsentinel.core.detect

import kotlinx.coroutines.flow.StateFlow

enum class EventType { ROLLCALL, QUESTION }

enum class EventScope { ROLLCALL, DIRECT, CLASS_OPEN }

/** 课堂事件：点名 / 提问 */
data class ClassEvent(
    val type: EventType,
    val triggerText: String, // 触发句
    val context: String,     // 上下文（当前版本=触发句，后续可带前后文）
    val ts: Long,
    val scope: EventScope = if (type == EventType.QUESTION) EventScope.CLASS_OPEN else EventScope.ROLLCALL,
    val reason: String = "",
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
    private val finalWindow = FinalTranscriptWindow()
    private val processedFinalIds = mutableSetOf<Int>()
    private val processedPartialRollcallIds = mutableSetOf<Int>()
    private val earlyRollcallIds = mutableSetOf<Int>()
    private var syntheticFinalId = 0

    /** Clear all per-listening-session state before a reused handle starts a new course. */
    fun resetSession() {
        lastRollcallTs = 0L
        lastQuestionTs = 0L
        processedFinalIds.clear()
        processedPartialRollcallIds.clear()
        earlyRollcallIds.clear()
        syntheticFinalId = 0
        finalWindow.clear()
    }

    /** Compatibility entry point for non-streaming/import callers. */
    fun process(segment: String, ts: Long = System.currentTimeMillis()): ClassEvent? {
        val sens = sensitivityFlow.value
        nameMatcher.detect(segment, sens)?.let {
            if (lastRollcallTs == 0L || ts - lastRollcallTs >= sens.rollcallSuppressMs) {
                lastRollcallTs = ts
                return ClassEvent(
                    type = EventType.ROLLCALL,
                    triggerText = segment,
                    context = segment,
                    ts = ts,
                    scope = EventScope.ROLLCALL,
                    reason = "NAME_ONLY",
                )
            }
            return null
        }
        QuestionDetector.detect(segment, sens.questionWordLevel)?.let {
            if (lastQuestionTs == 0L || ts - lastQuestionTs >= sens.questionSuppressMs) {
                lastQuestionTs = ts
                return ClassEvent(
                    type = EventType.QUESTION,
                    triggerText = segment,
                    context = segment,
                    ts = ts,
                    scope = EventScope.CLASS_OPEN,
                    reason = "LEGACY_TRIGGER",
                )
            }
        }
        return null
    }

    /**
     * Fast path for live rollcall only. Partial hypotheses are allowed to alert on a textual
     * exact name hit, but never create a question, persist an event, or invoke the LLM.
     */
    fun processPartialRollcall(
        utteranceId: Int,
        text: String,
        ts: Long = System.currentTimeMillis(),
    ): ClassEvent? {
        if (text.isBlank() || utteranceId in processedPartialRollcallIds) return null
        val sens = sensitivityFlow.value
        val hit = nameMatcher.detect(text, sens) ?: return null
        if (!hit.isExact || hit.score != 1.0) return null

        processedPartialRollcallIds += utteranceId
        if (lastRollcallTs == 0L || ts - lastRollcallTs >= sens.rollcallSuppressMs) {
            lastRollcallTs = ts
            earlyRollcallIds += utteranceId
            return ClassEvent(
                type = EventType.ROLLCALL,
                triggerText = text,
                context = text,
                ts = ts,
                scope = EventScope.ROLLCALL,
                reason = "PARTIAL_EXACT_NAME",
            )
        }
        return null
    }

    /** Processes authoritative final text; partial hypotheses must never call this method. */
    fun processFinal(final: FinalTranscript, ts: Long = System.currentTimeMillis()): ClassEvent? {
        val earlyRollcall = earlyRollcallIds.remove(final.utteranceId)
        if (final.text.isBlank() || !processedFinalIds.add(final.utteranceId)) return null
        val window = finalWindow.add(final)
        val combined = window.combinedText
        val sens = sensitivityFlow.value

        val question = QuestionDetector.detectAnswerable(combined, sens.questionWordLevel)
        val nameHit = nameMatcher.detect(combined, sens)

        // 明确问当前学生：姓名命中会把开放题提升为 DIRECT；高置信度“你”由 detector 自己识别。
        if (question != null && (question.scope == EventScope.DIRECT || nameHit != null)) {
            if (lastQuestionTs == 0L || ts - lastQuestionTs >= sens.questionSuppressMs) {
                lastQuestionTs = ts
                return ClassEvent(
                    type = EventType.QUESTION,
                    triggerText = final.text,
                    context = combined,
                    ts = ts,
                    scope = EventScope.DIRECT,
                    reason = question.reason,
                )
            }
            return null
        }

        // 1. 姓名命中但没有问题：只做点名提醒，不调用 LLM。
        nameHit?.let {
            if (earlyRollcall) {
                // Partial already advanced the rollcall suppression clock. The final still must
                // produce the authoritative DB event; the service adapter suppresses only its
                // duplicate alert.
                lastRollcallTs = ts
                return ClassEvent(
                    type = EventType.ROLLCALL,
                    triggerText = final.text,
                    context = combined,
                    ts = ts,
                    scope = EventScope.ROLLCALL,
                    reason = "NAME_ONLY",
                )
            }
            if (lastRollcallTs == 0L || ts - lastRollcallTs >= sens.rollcallSuppressMs) {
                lastRollcallTs = ts
                return ClassEvent(
                    type = EventType.ROLLCALL,
                    triggerText = final.text,
                    context = combined,
                    ts = ts,
                    scope = EventScope.ROLLCALL,
                    reason = "NAME_ONLY",
                )
            }
            return null // 抑制窗口内；命中点名时不降级为提问
        }

        // 2. 无姓名的高置信度开放题/明确邀请。
        if (question?.scope == EventScope.CLASS_OPEN) {
            if (lastQuestionTs == 0L || ts - lastQuestionTs >= sens.questionSuppressMs) {
                lastQuestionTs = ts
                return ClassEvent(
                    type = EventType.QUESTION,
                    triggerText = final.text,
                    context = combined,
                    ts = ts,
                    scope = EventScope.CLASS_OPEN,
                    reason = question.reason,
                )
            }
        }
        return null
    }
}
