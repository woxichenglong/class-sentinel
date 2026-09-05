package com.classsentinel.core.detect

import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

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
    private var confirmedRollcallTs = 0L
    private var lastDirectQuestion: LastQuestion? = null
    private var lastClassOpenQuestion: LastQuestion? = null
    private val finalWindow = FinalTranscriptWindow()
    private val processedFinalIds = mutableSetOf<Int>()
    private val processedPartialRollcallIds = mutableSetOf<Int>()
    private val provisionalRollcallIds = mutableSetOf<Int>()
    private var syntheticFinalId = 0

    /** Clear all per-listening-session state before a reused handle starts a new course. */
    fun resetSession() {
        confirmedRollcallTs = 0L
        lastDirectQuestion = null
        lastClassOpenQuestion = null
        processedFinalIds.clear()
        processedPartialRollcallIds.clear()
        provisionalRollcallIds.clear()
        syntheticFinalId = 0
        finalWindow.clear()
    }

    /** Compatibility entry point for non-streaming/import callers. */
    fun process(segment: String, ts: Long = System.currentTimeMillis()): ClassEvent? {
        val sens = sensitivityFlow.value
        nameMatcher.detect(segment, sens)?.let {
            if (confirmedRollcallTs == 0L || ts - confirmedRollcallTs >= sens.rollcallSuppressMs) {
                confirmedRollcallTs = ts
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
            if (canEmitQuestion(EventScope.CLASS_OPEN, segment, ts, sens.questionSuppressMs)) {
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
        if (confirmedRollcallTs == 0L || ts - confirmedRollcallTs >= sens.rollcallSuppressMs) {
            provisionalRollcallIds += utteranceId
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
        val earlyRollcall = provisionalRollcallIds.remove(final.utteranceId)
        if (final.text.isBlank() || !processedFinalIds.add(final.utteranceId)) return null
        val window = finalWindow.add(final)
        val combined = window.combinedText
        val sens = sensitivityFlow.value

        // Use only the current final for event classification. The rolling window is context for
        // the persisted event/answer, not evidence that an old name targets this new sentence.
        val question = QuestionDetector.detectAnswerable(final.text, sens.questionWordLevel)
        val nameHit = nameMatcher.detect(final.text, sens)

        // A confirming final commits the provisional alert to the authoritative suppression clock.
        // A rewritten final without a name does not, so a false partial cannot suppress later calls.
        if (earlyRollcall && nameHit != null) {
            confirmedRollcallTs = ts
        }

        // 明确问当前学生：姓名命中会把开放题提升为 DIRECT；高置信度“你”由 detector 自己识别。
        if (question != null && (question.scope == EventScope.DIRECT || nameHit != null)) {
            if (canEmitQuestion(EventScope.DIRECT, final.text, ts, sens.questionSuppressMs)) {
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
                // The final confirms the provisional alert and must still produce the
                // authoritative DB event; the service adapter suppresses only its duplicate alert.
                return ClassEvent(
                    type = EventType.ROLLCALL,
                    triggerText = final.text,
                    context = combined,
                    ts = ts,
                    scope = EventScope.ROLLCALL,
                    reason = "NAME_ONLY",
                )
            }
            if (confirmedRollcallTs == 0L || ts - confirmedRollcallTs >= sens.rollcallSuppressMs) {
                confirmedRollcallTs = ts
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
            if (canEmitQuestion(EventScope.CLASS_OPEN, final.text, ts, sens.questionSuppressMs)) {
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

    private fun canEmitQuestion(
        scope: EventScope,
        text: String,
        ts: Long,
        suppressionMs: Long,
    ): Boolean {
        val fingerprint = normalizeQuestion(text)
        val lastQuestion = if (scope == EventScope.DIRECT) lastDirectQuestion else lastClassOpenQuestion
        if (lastQuestion != null && ts - lastQuestion.ts < suppressionMs &&
            questionSimilarity(lastQuestion.fingerprint, fingerprint) >= QUESTION_SIMILARITY_THRESHOLD
        ) {
            return false
        }
        if (scope == EventScope.DIRECT) {
            lastDirectQuestion = LastQuestion(fingerprint, ts)
        } else {
            lastClassOpenQuestion = LastQuestion(fingerprint, ts)
        }
        return true
    }

    private fun normalizeQuestion(text: String): String =
        text.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

    private fun questionSimilarity(left: String, right: String): Double {
        if (left == right) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val previous = IntArray(right.length + 1) { it }
        val current = IntArray(right.length + 1)
        for (leftIndex in left.indices) {
            current[0] = leftIndex + 1
            for (rightIndex in right.indices) {
                val substitutionCost = if (left[leftIndex] == right[rightIndex]) 0 else 1
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + substitutionCost,
                )
            }
            previous.indices.forEach { index -> previous[index] = current[index] }
        }
        return 1.0 - previous[right.length].toDouble() / maxOf(left.length, right.length)
    }

    private data class LastQuestion(
        val fingerprint: String,
        val ts: Long,
    )

    private companion object {
        const val QUESTION_SIMILARITY_THRESHOLD = 0.85
    }
}
