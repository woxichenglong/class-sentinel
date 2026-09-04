package com.classsentinel.core.context

import com.classsentinel.core.detect.FinalTranscript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptContextFinalOnlyTest {

    @Test
    fun `final transcript is added with timestamp and current context remains bounded`() {
        val buffer = TranscriptContextBuffer(windowMs = 60_000L, maxChars = 2_000)

        buffer.addFinal(FinalTranscript(1, "前文", 0L, 1_000L))
        buffer.addFinal(FinalTranscript(2, "问题", 1_000L, 2_000L))

        val context = buffer.contextAt(2_000L)

        assertTrue(context.contains("前文"))
        assertTrue(context.contains("问题"))
        assertEquals("前文\n问题", context)
    }

    @Test
    fun `old final is excluded after the sixty second window`() {
        val buffer = TranscriptContextBuffer(windowMs = 60_000L, maxChars = 2_000)

        buffer.addFinal(FinalTranscript(1, "过期", 0L, 1_000L))
        buffer.addFinal(FinalTranscript(2, "当前", 62_000L, 62_000L))

        assertEquals("当前", buffer.contextAt(62_000L))
    }
}
