package com.classsentinel.core.alert

import com.classsentinel.core.detect.ClassEvent
import com.classsentinel.core.detect.EventScope
import com.classsentinel.core.detect.EventType

/** Controls alert delivery for QUESTION events without changing event detection or history. */
enum class QuestionAlertMode {
    ALL_QUESTIONS,
    TARGETED_ONLY,
    OFF,
    ;

    companion object {
        val DEFAULT: QuestionAlertMode = TARGETED_ONLY

        fun fromStored(value: String?): QuestionAlertMode =
            value?.let { runCatching { valueOf(it) }.getOrNull() } ?: DEFAULT
    }

    val storedValue: String
        get() = name
}

/**
 * Independent alert-side policy. Non-QUESTION events remain alertable so ROLLCALL behavior is
 * never controlled by this setting.
 */
class QuestionAlertPolicy(
    private val modeProvider: suspend () -> QuestionAlertMode,
) {
    suspend fun shouldAlert(event: ClassEvent): Boolean {
        if (event.type != EventType.QUESTION) return true
        return when (modeProvider()) {
            QuestionAlertMode.ALL_QUESTIONS -> true
            QuestionAlertMode.TARGETED_ONLY -> event.scope == EventScope.DIRECT
            QuestionAlertMode.OFF -> false
        }
    }
}
