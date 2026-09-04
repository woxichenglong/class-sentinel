package com.classsentinel.data

import androidx.room.Room
import com.classsentinel.data.entities.CourseEntity
import com.classsentinel.data.entities.EventEntity
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnswerHistoryRepositoryTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: AnswerHistoryRepository
    private val zone = ZoneId.of("Asia/Shanghai")

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = AnswerHistoryRepository(database.eventDao(), zone)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `history returns questions only and keeps empty answers`() = runBlocking {
        val courseId = database.courseDao().insert(
            CourseEntity(title = "session", startTs = 1L, endTs = 2L),
        )
        val oldQuestionId = insertEvent(
            courseId,
            "QUESTION",
            day("2026-09-03", 10, 0),
            "旧问题",
            "旧依据",
            null,
        )
        val rollcallId = insertEvent(
            courseId,
            "ROLLCALL",
            day("2026-09-03", 11, 0),
            "点名",
            "点名依据",
            "ignored",
        )
        val newestQuestionId = insertEvent(
            courseId,
            "QUESTION",
            day("2026-09-04", 9, 30),
            "新问题",
            "新依据",
            "答案",
        )

        val cards = repository.getCards()

        assertEquals(listOf(newestQuestionId, oldQuestionId), cards.map { it.eventId })
        assertEquals("新问题", cards.first().question)
        assertEquals("答案", cards.first().answer)
        assertEquals("旧依据", cards.last().context)
        assertNull(repository.getCardById(rollcallId))
    }

    @Test
    fun `cards group by local date descending with newest card first`() = runBlocking {
        val courseId = database.courseDao().insert(
            CourseEntity(title = "session", startTs = 1L, endTs = 2L),
        )
        val first = insertEvent(courseId, "QUESTION", day("2026-09-03", 10, 0), "一", "依据一", "a")
        val second = insertEvent(courseId, "QUESTION", day("2026-09-03", 12, 0), "二", "依据二", "b")
        val third = insertEvent(courseId, "QUESTION", day("2026-09-04", 8, 0), "三", "依据三", "c")

        val groups = repository.groupByDate(repository.getCards())

        assertEquals(listOf("2026-09-04", "2026-09-03"), groups.map { it.date })
        assertEquals(listOf(third), groups[0].cards.map { it.eventId })
        assertEquals(listOf(second, first), groups[1].cards.map { it.eventId })
    }

    @Test
    fun `clear history removes only question events`() = runBlocking {
        val courseId = database.courseDao().insert(
            CourseEntity(title = "session", startTs = 1L, endTs = 2L),
        )
        insertEvent(courseId, "QUESTION", day("2026-09-04", 8, 0), "问题", "依据", "答案")
        val rollcallId = insertEvent(courseId, "ROLLCALL", day("2026-09-04", 8, 1), "点名", "依据", null)

        assertEquals(1, repository.clearHistory())

        assertTrue(repository.getCards().isEmpty())
        assertEquals(rollcallId, database.eventDao().getForCourse(courseId).single().id)
    }

    private suspend fun insertEvent(
        courseId: Long,
        type: String,
        ts: Long,
        question: String,
        context: String,
        answer: String?,
    ): Long =
        database.eventDao().insert(
            EventEntity(
                courseId = courseId,
                type = type,
                triggerText = question,
                contextText = context,
                answerText = answer,
                notifiedAt = ts,
                ts = ts,
            ),
        )

    private fun day(date: String, hour: Int, minute: Int): Long =
        LocalDate.parse(date)
            .atTime(hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
}
