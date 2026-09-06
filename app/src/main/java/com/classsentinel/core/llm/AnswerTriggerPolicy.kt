package com.classsentinel.core.llm

import com.classsentinel.core.detect.ClassEvent
import com.classsentinel.core.detect.EventScope
import com.classsentinel.core.detect.EventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Controls whether a detected question should start automatic answer generation. */
enum class AnswerTriggerMode {
    ALL_QUESTIONS,
    TARGETED_ONLY,
    OFF,
    ;

    companion object {
        val DEFAULT: AnswerTriggerMode = TARGETED_ONLY

        fun fromStored(value: String?): AnswerTriggerMode =
            value?.let { runCatching { valueOf(it) }.getOrNull() } ?: DEFAULT
    }

    val storedValue: String
        get() = name
}

/**
 * Independent answer-side policy. Event detection and history persistence happen before this
 * boundary, so rejecting an automatic answer never removes the QUESTION event.
 */
class AnswerTriggerPolicy(
    private val modeProvider: suspend () -> AnswerTriggerMode,
) {
    suspend fun shouldGenerate(event: ClassEvent): Boolean = when (modeProvider()) {
        AnswerTriggerMode.ALL_QUESTIONS -> event.type == EventType.QUESTION
        AnswerTriggerMode.TARGETED_ONLY ->
            event.type == EventType.QUESTION && event.scope == EventScope.DIRECT
        AnswerTriggerMode.OFF -> false
    }
}

/** Runs the policy after EventEngine and invokes the answer boundary only when allowed. */
internal class AnswerTriggerDispatcher(
    private val scope: CoroutineScope,
    private val policy: AnswerTriggerPolicy,
    private val onAllowed: (ClassEvent, Long?) -> Unit,
) {
    fun dispatch(event: ClassEvent, eventId: Long?): Job = scope.launch {
        if (policy.shouldGenerate(event)) {
            onAllowed(event, eventId)
        }
    }
}
