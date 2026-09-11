package com.classsentinel.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionSpanAssemblerTest {

    @Test
    fun `joins an English word split across adjacent finals without an extra space`() {
        val previous = FinalTranscript(
            utteranceId = 1,
            text = "比如老师问 what is the difference between machine learning and deep learn",
            startOffsetMs = 0L,
            endOffsetMs = 1_000L,
        )
        val current = FinalTranscript(
            utteranceId = 2,
            text = "ing，请你用自己的话解释一下。",
            startOffsetMs = 1_500L,
            endOffsetMs = 2_000L,
        )

        val assembled = QuestionSpanAssembler.assemble(current, previous)

        assertEquals(
            "比如老师问 what is the difference between machine learning and deep learning，请你用自己的话解释一下。",
            assembled,
        )
        assertTrue("learn ing" !in assembled)
    }

    @Test
    fun `falls back to the current final when the previous gap exceeds two seconds`() {
        val previous = FinalTranscript(1, "前一句", 0L, 1_000L)
        val current = FinalTranscript(2, "请你解释一下", 3_001L, 4_000L)

        assertEquals(current.text, QuestionSpanAssembler.assemble(current, previous))
    }

    @Test
    fun `uses at most the supplied previous final and never needs an older final`() {
        val older = FinalTranscript(1, "更早的课堂铺垫", 0L, 500L)
        val previous = FinalTranscript(2, "上一句", 600L, 1_000L)
        val current = FinalTranscript(3, "请你解释", 1_500L, 2_000L)

        val assembled = QuestionSpanAssembler.assemble(current, previous)

        assertEquals("上一句请你解释", assembled)
        assertTrue(older.text !in assembled)
    }

    @Test
    fun `falls back instead of truncating when the merged question exceeds 300 characters`() {
        val previous = FinalTranscript(1, "前".repeat(250), 0L, 1_000L)
        val current = FinalTranscript(2, "请".repeat(51), 1_500L, 2_000L)

        assertEquals(current.text, QuestionSpanAssembler.assemble(current, previous))
    }

    @Test
    fun `preserves a meaningful existing ASCII boundary space`() {
        val previous = FinalTranscript(1, "machine learning ", 0L, 1_000L)
        val current = FinalTranscript(2, "model", 1_500L, 2_000L)

        assertEquals("machine learning model", QuestionSpanAssembler.assemble(current, previous))
    }

    @Test
    fun `falls back to the current final when there is no previous final`() {
        val current = FinalTranscript(2, "请你解释一下", 1_500L, 2_000L)

        assertEquals(current.text, QuestionSpanAssembler.assemble(current, null))
    }
}
