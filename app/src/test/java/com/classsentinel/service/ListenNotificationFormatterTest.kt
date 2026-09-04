package com.classsentinel.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ListenNotificationFormatterTest {
    @Test
    fun `status text shows elapsed engine and pending count without classroom content`() {
        val text = ListenNotificationFormatter.statusText(
            ListenNotificationStatus(
                elapsedMs = 125_000L,
                engine = "TeleSpeech",
                pendingSegments = 3,
            ),
        )

        assertEquals("已听 02:05 · 引擎 TeleSpeech · 待处理 3 段", text)
        assertFalse(text.contains("课堂原文"))
        assertFalse(text.contains("AI答案"))
    }
}
