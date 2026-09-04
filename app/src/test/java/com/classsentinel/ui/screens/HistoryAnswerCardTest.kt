package com.classsentinel.ui.screens

import com.classsentinel.data.AnswerCard
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryAnswerCardTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `empty answer remains visible as safe unavailable state`() {
        val card = AnswerCard(
            eventId = 7L,
            question = "为什么？",
            answer = null,
            context = "课堂依据",
            timestampMs = time("2026-09-04T17:30:00"),
        )

        val presentation = answerCardPresentation(card, zone, expanded = false)

        assertEquals("答案暂不可用", presentation.answer)
        assertEquals("2026-09-04 17:30", presentation.time)
    }

    @Test
    fun `long context is folded until the card is expanded`() {
        val context = "依据".repeat(100)
        val card = AnswerCard(7L, "问题", "答案", context, time("2026-09-04T17:30:00"))

        val collapsed = answerCardPresentation(card, zone, expanded = false)
        val expanded = answerCardPresentation(card, zone, expanded = true)

        assertTrue(collapsed.context.length <= ANSWER_CONTEXT_PREVIEW_CHARS + 1)
        assertTrue(collapsed.context.endsWith("…"))
        assertFalse(collapsed.context == context)
        assertEquals(context, expanded.context)
    }

    @Test
    fun `success answer and question text are preserved`() {
        val card = AnswerCard(9L, "问题原文", "短答案", "依据", time("2026-09-04T08:00:00"))

        val presentation = answerCardPresentation(card, zone, expanded = false)

        assertEquals("问题原文", presentation.question)
        assertEquals("短答案", presentation.answer)
        assertEquals("依据", presentation.context)
    }

    private fun time(value: String): Long =
        LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
}
