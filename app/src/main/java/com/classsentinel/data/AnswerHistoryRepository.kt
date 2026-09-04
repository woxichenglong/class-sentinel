package com.classsentinel.data

import com.classsentinel.data.entities.EventEntity
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A question/answer card without exposing the internal course relationship. */
data class AnswerCard(
    val eventId: Long,
    val question: String,
    val answer: String?,
    val context: String,
    val timestampMs: Long,
)

data class AnswerHistoryGroup(
    val date: String,
    val cards: List<AnswerCard>,
)

/** Reads only QUESTION events and groups them by the user's local calendar date. */
class AnswerHistoryRepository(
    private val eventDao: EventDao,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun getCards(): List<AnswerCard> =
        eventDao.getQuestionEvents().map(EventEntity::toAnswerCard)

    fun observeCards(): Flow<List<AnswerCard>> =
        eventDao.observeQuestionEvents().map { events -> events.map(EventEntity::toAnswerCard) }

    suspend fun getCardById(eventId: Long): AnswerCard? =
        eventDao.getQuestionById(eventId)?.toAnswerCard()

    suspend fun clearHistory(): Int = eventDao.clearQuestionEvents()

    fun groupByDate(cards: List<AnswerCard>): List<AnswerHistoryGroup> =
        cards
            .groupBy { dateKey(it.timestampMs) }
            .entries
            .sortedByDescending { it.key }
            .map { (date, entries) ->
                AnswerHistoryGroup(
                    date = date,
                    cards = entries.sortedByDescending { it.timestampMs },
                )
            }

    private fun dateKey(timestampMs: Long): String =
        Instant.ofEpochMilli(timestampMs).atZone(zoneId).toLocalDate().toString()
}

private fun EventEntity.toAnswerCard(): AnswerCard = AnswerCard(
    eventId = id,
    question = triggerText,
    answer = answerText,
    context = contextText,
    timestampMs = ts,
)