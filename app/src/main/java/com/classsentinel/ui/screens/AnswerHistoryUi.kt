package com.classsentinel.ui.screens

import com.classsentinel.data.AnswerCard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val ANSWER_CONTEXT_PREVIEW_CHARS = 120

internal data class AnswerCardPresentation(
    val eventId: Long,
    val question: String,
    val answer: String,
    val context: String,
    val time: String,
)

internal fun answerCardPresentation(
    card: AnswerCard,
    zoneId: ZoneId = ZoneId.systemDefault(),
    expanded: Boolean,
): AnswerCardPresentation {
    val context = card.context.trim()
    val visibleContext = if (expanded || context.length <= ANSWER_CONTEXT_PREVIEW_CHARS) {
        context
    } else {
        context.take(ANSWER_CONTEXT_PREVIEW_CHARS).trimEnd() + "…"
    }
    return AnswerCardPresentation(
        eventId = card.eventId,
        question = card.question,
        answer = card.answer?.trim().orEmpty().ifBlank { "答案暂不可用" },
        context = visibleContext,
        time = Instant.ofEpochMilli(card.timestampMs)
            .atZone(zoneId)
            .format(TIME_FORMATTER),
    )
}

private val TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
