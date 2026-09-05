package com.classsentinel.core.detect

import com.classsentinel.service.EarlyRollcallAlertGate
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventEngineTest {

    private fun engine(sens: Sensitivity = Sensitivity.STANDARD) = EventEngine(
        NameMatcher(listOf(NameEntry("张伟", listOf("张微")))),
        MutableStateFlow(sens),
    )

    @Test
    fun `rollcall event on name plus context`() {
        val e = engine().process("张伟，你来回答一下", ts = 1000)
        assertEquals(EventType.ROLLCALL, e?.type)
    }

    @Test
    fun `question event on trigger word`() {
        val e = engine().process("哪位同学能说说为什么", ts = 1000)
        assertEquals(EventType.QUESTION, e?.type)
    }

    @Test
    fun `rollcall suppress window blocks duplicate`() {
        val eng = engine()
        eng.process("张伟，你来回答", ts = 1_000)
        assertNull(eng.process("张伟，你再说说", ts = 20_000)) // 60s 抑制窗口内
    }

    @Test
    fun `rollcall after suppress window fires again`() {
        val eng = engine()
        eng.process("张伟，你来回答", ts = 1_000)
        val e = eng.process("张伟，你来回答", ts = 70_000)
        assertEquals(EventType.ROLLCALL, e?.type)
    }

    @Test
    fun `question suppress window independent from rollcall`() {
        val eng = engine()
        eng.process("张伟，你来回答", ts = 1_000)
        // 提问抑制窗口是独立计时的，点名后 1s 的提问不受点名抑制影响
        val e = eng.process("哪位同学说说看", ts = 2_000)
        assertEquals(EventType.QUESTION, e?.type)
    }

    @Test
    fun `class open suppression does not block a later direct question`() {
        val eng = engine()

        val classOpen = eng.processFinal(
            FinalTranscript(1, "为什么价格上涨", 0L, 1_000L),
            ts = 1_000,
        )
        assertEquals(EventScope.CLASS_OPEN, classOpen?.scope)

        val direct = eng.processFinal(
            FinalTranscript(2, "你来解释一下 CAPM 的 beta", 1_000L, 31_000L),
            ts = 31_000,
        )

        assertEquals(EventScope.DIRECT, direct?.scope)
    }

    @Test
    fun `class open question does not inherit a name from the previous final`() {
        val eng = engine()

        assertNull(
            eng.processFinal(
                FinalTranscript(1, "张伟", 0L, 1_000L),
                ts = 1_000,
            ),
        )

        val event = eng.processFinal(
            FinalTranscript(2, "谁来回答", 1_000L, 2_000L),
            ts = 2_000,
        )

        assertEquals(EventType.QUESTION, event?.type)
        assertEquals(EventScope.CLASS_OPEN, event?.scope)
    }

    @Test
    fun `sensitivity hot update takes effect`() {
        val flow = MutableStateFlow<Sensitivity>(Sensitivity.STRICT)
        val eng = EventEngine(NameMatcher(listOf(NameEntry("张伟", emptyList()))), flow)
        assertNull(eng.process("张伟", ts = 1_000)) // STRICT 需上下文 → 不命中
        flow.value = Sensitivity.LOOSE
        val e = eng.process("张伟", ts = 70_000) // LOOSE 无需上下文 → 命中
        assertEquals(EventType.ROLLCALL, e?.type)
    }

    @Test
    fun `exact-name partial creates rollcall before final`() {
        val event = engine().processPartialRollcall(
            utteranceId = 7,
            text = "张伟，你来回答",
            ts = 1_000,
        )

        assertEquals(EventType.ROLLCALL, event?.type)
        assertEquals("PARTIAL_EXACT_NAME", event?.reason)
    }

    @Test
    fun `repeated exact partials for one utterance alert only once`() {
        val eng = engine()

        assertNotNull(eng.processPartialRollcall(7, "张伟，你来", ts = 1_000))
        assertNull(eng.processPartialRollcall(7, "张伟，你来回答", ts = 1_200))
    }

    @Test
    fun `early partial followed by confirming final still returns authoritative rollcall`() {
        val eng = engine()

        assertNotNull(eng.processPartialRollcall(7, "张伟，你来", ts = 1_000))
        val event = eng.processFinal(
            FinalTranscript(7, "张伟，你来回答", 0L, 2_000L),
            ts = 2_000,
        )

        assertEquals(EventType.ROLLCALL, event?.type)
    }

    @Test
    fun `confirming final keeps authoritative event while early alert gate suppresses duplicate`() {
        val eng = engine()
        val gate = EarlyRollcallAlertGate()

        val provisional = eng.processPartialRollcall(7, "张伟，你来", ts = 1_000)
        assertNotNull(provisional)
        assertTrue(gate.record(7))

        val final = eng.processFinal(
            FinalTranscript(7, "张伟，你来回答", 0L, 2_000L),
            ts = 2_000,
        )

        assertEquals(EventType.ROLLCALL, final?.type)
        assertTrue(gate.consume(7))
        assertFalse(gate.consume(7))
    }

    @Test
    fun `early partial followed by final without the name creates no rollcall`() {
        val eng = engine()

        assertNotNull(eng.processPartialRollcall(7, "张伟，你来", ts = 1_000))
        assertNull(
            eng.processFinal(
                FinalTranscript(7, "老师请继续讲", 0L, 2_000L),
                ts = 2_000,
            ),
        )
    }

    @Test
    fun `retracted partial does not consume confirmed rollcall suppression window`() {
        val eng = engine()

        assertNotNull(eng.processPartialRollcall(7, "张伟，你来", ts = 1_000))
        assertNull(
            eng.processFinal(
                FinalTranscript(7, "老师请继续讲", 0L, 2_000L),
                ts = 2_000,
            ),
        )

        val laterRollcall = eng.processFinal(
            FinalTranscript(8, "张伟，你来回答", 5_000L, 6_000L),
            ts = 6_000,
        )

        assertEquals(EventType.ROLLCALL, laterRollcall?.type)
    }

    @Test
    fun `fuzzy-only partial waits for final while final can still rollcall`() {
        val eng = engine()

        assertNull(eng.processPartialRollcall(7, "章伟，你来一下", ts = 1_000))
        val final = eng.processFinal(
            FinalTranscript(7, "章伟，你来一下", 0L, 2_000L),
            ts = 2_000,
        )

        assertEquals(EventType.ROLLCALL, final?.type)
    }

    @Test
    fun `partial question text never creates a question event`() {
        val event = engine().processPartialRollcall(
            utteranceId = 7,
            text = "哪位同学能说说为什么",
            ts = 1_000,
        )

        assertNull(event)
    }

    @Test
    fun `name final followed by question final creates one direct question`() {
        val eng = engine()

        assertNull(eng.processFinal(FinalTranscript(1, "张伟", 0L, 1_000L), ts = 1_000))
        val event = eng.processFinal(
            FinalTranscript(2, "你来回答这个问题", 1_000L, 2_000L),
            ts = 2_000,
        )

        assertNotNull(event)
        assertEquals(EventType.QUESTION, event?.type)
        assertEquals(EventScope.DIRECT, event?.scope)
        assertEquals("你来回答这个问题", event?.triggerText)
        assertEquals("张伟\n你来回答这个问题", event?.context)
        assertNull(
            eng.processFinal(
                FinalTranscript(2, "你来回答这个问题", 1_000L, 2_000L),
                ts = 2_100,
            ),
        )
    }

    @Test
    fun `open question and explicit class invitation create class open scope`() {
        val open = engine().processFinal(
            FinalTranscript(1, "为什么这个结论成立", 0L, 1_000L),
            ts = 1_000,
        )
        assertEquals(EventType.QUESTION, open?.type)
        assertEquals(EventScope.CLASS_OPEN, open?.scope)

        val invitation = engine().processFinal(
            FinalTranscript(1, "有没有同学来回答", 0L, 1_000L),
            ts = 1_000,
        )
        assertEquals(EventType.QUESTION, invitation?.type)
        assertEquals(EventScope.CLASS_OPEN, invitation?.scope)
    }

    @Test
    fun `binary confirmation and ordinary statement do not create answer event`() {
        assertNull(engine().processFinal(FinalTranscript(1, "这个结论对不对", 0L, 1_000L), ts = 1_000))
        assertNull(engine().processFinal(FinalTranscript(2, "是不是这样", 1_000L, 2_000L), ts = 2_000))
        assertNull(engine().processFinal(FinalTranscript(3, "大家先看书十分钟", 2_000L, 3_000L), ts = 3_000))
    }

    @Test
    fun `session reset accepts reused utterance ids without old suppression or context`() {
        val eng = engine()
        eng.processFinal(FinalTranscript(1, "张伟", 0L, 1_000L), ts = 1_000)

        eng.resetSession()

        val event = eng.processFinal(
            FinalTranscript(1, "为什么价格上涨", 0L, 1_000L),
            ts = 2_000,
        )

        assertNotNull(event)
        assertEquals(EventScope.CLASS_OPEN, event?.scope)
        assertEquals("为什么价格上涨", event?.context)
    }
}
