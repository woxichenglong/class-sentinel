package com.classsentinel.core.llm

import com.classsentinel.core.detect.ClassEvent
import com.classsentinel.core.detect.EventScope
import com.classsentinel.core.detect.EventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnswerTriggerPolicyTest {

    @Test
    fun `ALL_QUESTIONS dispatches class-open questions`() = runTest {
        val calls = mutableListOf<ClassEvent>()
        val dispatcher = dispatcher(this, AnswerTriggerMode.ALL_QUESTIONS, calls)
        val event = question(EventScope.CLASS_OPEN)

        dispatcher.dispatch(event, eventId = 1L)
        advanceUntilIdle()

        assertEquals(EventType.QUESTION, event.type)
        assertEquals(listOf(event), calls)
    }

    @Test
    fun `ALL_QUESTIONS dispatches direct questions`() = runTest {
        val calls = mutableListOf<ClassEvent>()
        val dispatcher = dispatcher(this, AnswerTriggerMode.ALL_QUESTIONS, calls)
        val event = question(EventScope.DIRECT)

        dispatcher.dispatch(event, eventId = 1L)
        advanceUntilIdle()

        assertEquals(listOf(event), calls)
    }

    @Test
    fun `TARGETED_ONLY keeps class-open question but does not dispatch it`() = runTest {
        val calls = mutableListOf<ClassEvent>()
        val dispatcher = dispatcher(this, AnswerTriggerMode.TARGETED_ONLY, calls)
        val event = question(EventScope.CLASS_OPEN)

        dispatcher.dispatch(event, eventId = 1L)
        advanceUntilIdle()

        assertEquals(EventType.QUESTION, event.type)
        assertEquals(EventScope.CLASS_OPEN, event.scope)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `TARGETED_ONLY dispatches direct questions`() = runTest {
        val calls = mutableListOf<ClassEvent>()
        val dispatcher = dispatcher(this, AnswerTriggerMode.TARGETED_ONLY, calls)
        val event = question(EventScope.DIRECT)

        dispatcher.dispatch(event, eventId = 1L)
        advanceUntilIdle()

        assertEquals(listOf(event), calls)
    }

    @Test
    fun `OFF does not dispatch class-open or direct questions`() = runTest {
        val calls = mutableListOf<ClassEvent>()
        val dispatcher = dispatcher(this, AnswerTriggerMode.OFF, calls)

        dispatcher.dispatch(question(EventScope.CLASS_OPEN), eventId = 1L)
        dispatcher.dispatch(question(EventScope.DIRECT), eventId = 2L)
        advanceUntilIdle()

        assertTrue(calls.isEmpty())
    }

    @Test
    fun `ROLLCALL never dispatches in any answer mode`() = runTest {
        AnswerTriggerMode.entries.forEach { mode ->
            val calls = mutableListOf<ClassEvent>()
            val dispatcher = dispatcher(this, mode, calls)

            dispatcher.dispatch(
                ClassEvent(
                    type = EventType.ROLLCALL,
                    triggerText = "张伟，你来回答",
                    context = "张伟，你来回答",
                    ts = 1L,
                    scope = EventScope.ROLLCALL,
                ),
                eventId = 1L,
            )
            advanceUntilIdle()

            assertTrue("mode=$mode", calls.isEmpty())
        }
    }

    @Test
    fun `policy reads the current mode for each event`() = runTest {
        var mode = AnswerTriggerMode.TARGETED_ONLY
        val calls = mutableListOf<ClassEvent>()
        val dispatcher = AnswerTriggerDispatcher(
            scope = this,
            policy = AnswerTriggerPolicy { mode },
            onAllowed = { event, _ -> calls += event },
        )

        dispatcher.dispatch(question(EventScope.CLASS_OPEN), eventId = 1L)
        advanceUntilIdle()
        mode = AnswerTriggerMode.ALL_QUESTIONS
        dispatcher.dispatch(question(EventScope.CLASS_OPEN), eventId = 2L)
        advanceUntilIdle()

        assertEquals(1, calls.size)
    }

    private fun dispatcher(
        scope: CoroutineScope,
        mode: AnswerTriggerMode,
        calls: MutableList<ClassEvent>,
    ) = AnswerTriggerDispatcher(
        scope = scope,
        policy = AnswerTriggerPolicy { mode },
        onAllowed = { event, _ -> calls += event },
    )

    private fun question(scope: EventScope) = ClassEvent(
        type = EventType.QUESTION,
        triggerText = "为什么 CAPM 成立",
        context = "为什么 CAPM 成立",
        ts = 1L,
        scope = scope,
    )
}
