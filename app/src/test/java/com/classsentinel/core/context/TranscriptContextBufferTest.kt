package com.classsentinel.core.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptContextBufferTest {
    @Test
    fun `context keeps recent ordered text within time and character budget`() {
        val buffer = TranscriptContextBuffer(windowMs = 60_000L, maxChars = 8)
        buffer.add(TimedTranscript("过期", -20_000L))
        buffer.add(TimedTranscript("前文", 20_000L))
        buffer.add(TimedTranscript("背景", 40_000L))
        buffer.add(TimedTranscript("触发句", 50_000L))

        val context = buffer.contextAt(50_000L)

        assertFalse(context.contains("过期"))
        assertTrue(context.indexOf("前文") < context.indexOf("触发句"))
        assertTrue(context.contains("触发句"))
        assertTrue(context.length <= 8)
        assertEquals(1, "触发句".toRegex().findAll(context).count())
    }

    @Test
    fun `clear removes all context`() {
        val buffer = TranscriptContextBuffer(maxChars = 100)
        buffer.add(TimedTranscript("课堂片段", 1_000L))
        buffer.clear()
        assertEquals("", buffer.contextAt(1_000L))
    }
}
