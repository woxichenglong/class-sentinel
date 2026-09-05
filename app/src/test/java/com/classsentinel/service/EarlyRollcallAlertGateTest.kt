package com.classsentinel.service

import com.classsentinel.core.detect.ClassEvent
import com.classsentinel.core.detect.EventScope
import com.classsentinel.core.detect.EventType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EarlyRollcallAlertGateTest {

    @Test
    fun `one utterance can be recorded and consumed only once`() {
        val gate = EarlyRollcallAlertGate()

        assertTrue(gate.record(7))
        assertFalse(gate.record(7))
        assertTrue(gate.consume(7))
        assertFalse(gate.consume(7))
    }

    @Test
    fun `final question does not satisfy rollcall suppression condition`() {
        val gate = EarlyRollcallAlertGate()
        val question = ClassEvent(
            type = EventType.QUESTION,
            triggerText = "为什么",
            context = "为什么",
            ts = 1_000,
            scope = EventScope.CLASS_OPEN,
        )

        assertTrue(gate.record(7))
        val earlyAlerted = gate.consume(7)

        assertFalse(question.type == EventType.ROLLCALL && earlyAlerted)
    }

    @Test
    fun `final rollcall with an early marker is the duplicate to suppress`() {
        val gate = EarlyRollcallAlertGate()

        assertTrue(gate.record(7))

        assertTrue(gate.consume(7))
    }
}