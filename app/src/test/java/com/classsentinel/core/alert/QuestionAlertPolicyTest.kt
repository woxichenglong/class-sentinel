package com.classsentinel.core.alert

import com.classsentinel.core.detect.ClassEvent
import com.classsentinel.core.detect.EventScope
import com.classsentinel.core.detect.EventType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionAlertPolicyTest {

    @Test
    fun `ALL_QUESTIONS alerts class-open questions`() = runBlocking {
        assertTrue(policy(QuestionAlertMode.ALL_QUESTIONS).shouldAlert(question(EventScope.CLASS_OPEN)))
    }

    @Test
    fun `ALL_QUESTIONS alerts direct questions`() = runBlocking {
        assertTrue(policy(QuestionAlertMode.ALL_QUESTIONS).shouldAlert(question(EventScope.DIRECT)))
    }

    @Test
    fun `TARGETED_ONLY does not alert class-open questions`() = runBlocking {
        assertFalse(policy(QuestionAlertMode.TARGETED_ONLY).shouldAlert(question(EventScope.CLASS_OPEN)))
    }

    @Test
    fun `TARGETED_ONLY alerts direct questions`() = runBlocking {
        assertTrue(policy(QuestionAlertMode.TARGETED_ONLY).shouldAlert(question(EventScope.DIRECT)))
    }

    @Test
    fun `OFF does not alert class-open or direct questions`() = runBlocking {
        val alertPolicy = policy(QuestionAlertMode.OFF)

        assertFalse(alertPolicy.shouldAlert(question(EventScope.CLASS_OPEN)))
        assertFalse(alertPolicy.shouldAlert(question(EventScope.DIRECT)))
    }

    @Test
    fun `ROLLCALL remains alertable in every question alert mode`() = runBlocking {
        val rollcall = ClassEvent(
            type = EventType.ROLLCALL,
            triggerText = "张伟，起立",
            context = "张伟，起立",
            ts = 1L,
            scope = EventScope.ROLLCALL,
        )

        QuestionAlertMode.entries.forEach { mode ->
            assertTrue("mode=$mode", policy(mode).shouldAlert(rollcall))
        }
    }

    @Test
    fun `policy reads the current alert mode for each event`() = runBlocking {
        var mode = QuestionAlertMode.TARGETED_ONLY
        val alertPolicy = QuestionAlertPolicy { mode }

        assertFalse(alertPolicy.shouldAlert(question(EventScope.CLASS_OPEN)))
        mode = QuestionAlertMode.ALL_QUESTIONS
        assertTrue(alertPolicy.shouldAlert(question(EventScope.CLASS_OPEN)))
    }

    private fun policy(mode: QuestionAlertMode) = QuestionAlertPolicy { mode }

    private fun question(scope: EventScope) = ClassEvent(
        type = EventType.QUESTION,
        triggerText = "为什么 CAPM 成立",
        context = "为什么 CAPM 成立",
        ts = 1L,
        scope = scope,
    )
}
