package com.classsentinel.data

import androidx.room.Room
import com.classsentinel.data.entities.CourseEntity
import com.classsentinel.data.entities.TranscriptChunkEntity
import com.classsentinel.ui.screens.canMarkLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptMarkerTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `mark and unmark chunk updates marked observation for its course`() = runBlocking {
        val courseId = db.courseDao().insert(CourseEntity(title = "数学", startTs = 1_000L))
        val firstId = db.transcriptDao().insert(chunk(courseId, 1))
        db.transcriptDao().insert(chunk(courseId, 2))

        assertEquals(1, db.transcriptDao().mark(firstId, courseId))
        assertEquals(listOf(firstId), db.transcriptDao().observeMarkedForCourse(courseId).first().map { it.id })
        assertTrue(db.transcriptDao().getForCourse(courseId).first { it.id == firstId }.isMarked)

        assertEquals(1, db.transcriptDao().unmark(firstId, courseId))
        assertTrue(db.transcriptDao().observeMarkedForCourse(courseId).first().isEmpty())
        assertFalse(db.transcriptDao().getForCourse(courseId).first { it.id == firstId }.isMarked)
    }

    @Test
    fun `mark cannot target a chunk through the wrong course`() = runBlocking {
        val firstCourse = db.courseDao().insert(CourseEntity(title = "数学", startTs = 1_000L))
        val secondCourse = db.courseDao().insert(CourseEntity(title = "物理", startTs = 2_000L))
        val chunkId = db.transcriptDao().insert(chunk(firstCourse, 1))

        assertEquals(0, db.transcriptDao().mark(chunkId, secondCourse))
        assertTrue(db.transcriptDao().observeMarkedForCourse(firstCourse).first().isEmpty())
    }

    @Test
    fun `mark latest action is disabled without both active course and latest chunk`() {
        assertFalse(canMarkLatest(activeCourseId = null, latestChunkId = null))
        assertFalse(canMarkLatest(activeCourseId = 7L, latestChunkId = null))
        assertFalse(canMarkLatest(activeCourseId = null, latestChunkId = 9L))
        assertTrue(canMarkLatest(activeCourseId = 7L, latestChunkId = 9L))
    }

    private fun chunk(courseId: Long, seq: Int) = TranscriptChunkEntity(
        courseId = courseId,
        seq = seq,
        text = "句子-$seq",
        ts = seq * 1_000L,
    )
}
