package com.classsentinel.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalTranscriptWindowTest {

    @Test
    fun `name final followed by question final within window is combined once`() {
        val window = FinalTranscriptWindow(windowMs = 8_000L)

        window.add(FinalTranscript(1, "张三", 0L, 1_000L))
        val snapshot = window.add(FinalTranscript(2, "你来回答这个问题", 1_000L, 2_000L))

        assertEquals("张三\n你来回答这个问题", snapshot.combinedText)
        assertEquals(listOf(1, 2), snapshot.entries.map { it.utteranceId })
    }

    @Test
    fun `final outside time window is not combined`() {
        val window = FinalTranscriptWindow(windowMs = 8_000L)

        window.add(FinalTranscript(1, "张三", 0L, 1_000L))
        val snapshot = window.add(FinalTranscript(2, "你来回答", 9_500L, 10_000L))

        assertEquals("你来回答", snapshot.combinedText)
        assertEquals(listOf(2), snapshot.entries.map { it.utteranceId })
    }

    @Test
    fun `duplicate final utterance is ignored`() {
        val window = FinalTranscriptWindow()
        val first = FinalTranscript(1, "同一句", 0L, 1_000L)

        window.add(first)
        val snapshot = window.add(first.copy(text = "同一句重复到达"))

        assertEquals(listOf(first), snapshot.entries)
    }

    @Test
    fun `window is bounded by entry count`() {
        val window = FinalTranscriptWindow(windowMs = Long.MAX_VALUE, maxEntries = 2)

        window.add(FinalTranscript(1, "一", 0L, 1L))
        window.add(FinalTranscript(2, "二", 1L, 2L))
        val snapshot = window.add(FinalTranscript(3, "三", 2L, 3L))

        assertEquals(listOf(2, 3), snapshot.entries.map { it.utteranceId })
        assertTrue(snapshot.combinedText == "二\n三")
    }
}
