package com.classsentinel.core.detect

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `sensitivity hot update takes effect`() {
        val flow = MutableStateFlow<Sensitivity>(Sensitivity.STRICT)
        val eng = EventEngine(NameMatcher(listOf(NameEntry("张伟", emptyList()))), flow)
        assertNull(eng.process("张伟", ts = 1_000)) // STRICT 需上下文 → 不命中
        flow.value = Sensitivity.LOOSE
        val e = eng.process("张伟", ts = 70_000) // LOOSE 无需上下文 → 命中
        assertEquals(EventType.ROLLCALL, e?.type)
    }
}
