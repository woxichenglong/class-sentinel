package com.classsentinel.data

import androidx.room.Room
import com.classsentinel.core.audio.PendingAudioStore
import com.classsentinel.core.audio.WavSegment
import com.classsentinel.data.entities.CourseEntity
import com.classsentinel.data.entities.EventEntity
import com.classsentinel.data.entities.StudyArtifactEntity
import com.classsentinel.data.entities.TranscriptChunkEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Task 17：历史保留与清空历史的数据层契约。
 *
 * 测试只使用临时 Room 数据库和临时 pending-audio 根目录；文本与音频均为合成 fixture。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var pendingRoot: File
    private lateinit var pendingStore: PendingAudioStore
    private lateinit var repository: HistoryRepository

    private val now = 10L * DAY_MS

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        pendingRoot = temporaryFolder.newFolder("pending-audio")
        pendingStore = PendingAudioStore(
            rootDir = pendingRoot,
            dao = db.pendingAudioDao(),
            clock = { now },
        )
        repository = HistoryRepository(db, pendingStore, clock = { now })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `seven day retention removes old course children and pending file but keeps recent course`() = runBlocking {
        val oldId = insertCourse("old", now - 8 * DAY_MS, now - 7 * DAY_MS)
        val recentId = insertCourse("recent", now - 2 * DAY_MS, now - DAY_MS)
        addChildren(oldId, marker = "old")
        addChildren(recentId, marker = "recent")
        val oldPending = savePending(oldId, "old-segment")
        val recentPending = savePending(recentId, "recent-segment")

        repository.deleteExpired(retentionDays = "7", nowMillis = now)

        assertNull("8-day-old course must be removed", db.courseDao().getById(oldId))
        assertNotNull("recent course must be retained", db.courseDao().getById(recentId))
        assertTrue("old events must be removed", db.eventDao().getForCourse(oldId).isEmpty())
        assertTrue("old transcript chunks must be removed", db.transcriptDao().getForCourse(oldId).isEmpty())
        assertTrue("old study artifacts must be removed", db.studyArtifactDao().getForCourse(oldId).isEmpty())
        assertEquals(1, db.studyArtifactDao().getForCourse(recentId).size)
        assertTrue(
            "old pending row must be removed",
            db.pendingAudioDao().getByState("PENDING").none { it.courseId == oldId },
        )
        assertFalse("old pending file must be removed", File(oldPending.filePath).exists())
        assertTrue("recent pending file must be retained", File(recentPending.filePath).exists())
    }

    @Test
    fun `forever retention removes nothing including old course children and file`() = runBlocking {
        val oldId = insertCourse("old", now - 365 * DAY_MS, now - 364 * DAY_MS)
        addChildren(oldId, marker = "old")
        val pending = savePending(oldId, "old-segment")

        repository.deleteExpired(retentionDays = "forever", nowMillis = now)

        assertNotNull("forever policy must retain course", db.courseDao().getById(oldId))
        assertEquals(1, db.eventDao().getForCourse(oldId).size)
        assertEquals(1, db.transcriptDao().getForCourse(oldId).size)
        assertEquals(1, db.studyArtifactDao().getForCourse(oldId).size)
        assertTrue(
            "forever policy must retain pending row",
            db.pendingAudioDao().getByState("PENDING").any { it.id == pending.id },
        )
        assertTrue("forever policy must retain pending file", File(pending.filePath).exists())
    }

    @Test
    fun `retention never deletes a still running course`() = runBlocking {
        val runningId = insertCourse(
            title = "running",
            startTs = now - 30 * DAY_MS,
            endTs = null,
            status = "RUNNING",
        )
        val pending = savePending(runningId, "running-segment")

        repository.deleteExpired(retentionDays = "7", nowMillis = now)

        assertNotNull("automatic retention must not delete an active course", db.courseDao().getById(runningId))
        assertTrue("active pending file must be retained", File(pending.filePath).exists())
    }

    @Test
    fun `clear history removes all courses children every pending state and files`() = runBlocking {
        val firstId = insertCourse("first", now - 10 * DAY_MS, now - 9 * DAY_MS)
        val secondId = insertCourse("second", now - 2 * DAY_MS, now - DAY_MS)
        addChildren(firstId, marker = "first")
        addChildren(secondId, marker = "second")
        val firstPending = savePending(firstId, "first-segment")
        val secondPending = savePending(secondId, "second-segment")
        db.pendingAudioDao().updateState(firstPending.id, "FAILED", 2, "NETWORK")

        repository.clearHistory()

        assertTrue("all courses must be deleted", db.courseDao().getAll().isEmpty())
        assertEquals("all events must be deleted", 0, db.eventDao().countAll())
        assertEquals("all transcript chunks must be deleted", 0, db.transcriptDao().countAll())
        assertTrue("all study artifacts must be deleted", db.studyArtifactDao().getForCourse(firstId).isEmpty())
        assertTrue("all study artifacts must be deleted", db.studyArtifactDao().getForCourse(secondId).isEmpty())
        assertTrue("PENDING rows must be deleted", db.pendingAudioDao().getByState("PENDING").isEmpty())
        assertTrue("FAILED rows must be deleted", db.pendingAudioDao().getByState("FAILED").isEmpty())
        assertFalse("first pending file must be deleted", File(firstPending.filePath).exists())
        assertFalse("second pending file must be deleted", File(secondPending.filePath).exists())
    }

    private suspend fun insertCourse(
        title: String,
        startTs: Long,
        endTs: Long?,
        status: String = "COMPLETED",
    ): Long = db.courseDao().insert(
        CourseEntity(
            title = title,
            startTs = startTs,
            endTs = endTs,
            status = status,
        ),
    )

    private suspend fun addChildren(courseId: Long, marker: String) {
        db.eventDao().insert(
            EventEntity(
                courseId = courseId,
                type = "QUESTION",
                triggerText = "fixture-trigger-$marker",
                contextText = "fixture-context-$marker",
                notifiedAt = now,
                ts = now,
            ),
        )
        db.transcriptDao().insert(
            TranscriptChunkEntity(
                courseId = courseId,
                seq = 1,
                text = "fixture-transcript-$marker",
                ts = now,
                segmentId = "segment-$marker",
            ),
        )
        db.studyArtifactDao().insertIfAbsent(
            StudyArtifactEntity(
                courseId = courseId,
                type = StudyArtifactEntity.TYPE_FLASHCARDS,
                createdTs = now,
                updatedTs = now,
            ),
        )
    }

    private suspend fun savePending(courseId: Long, segmentId: String) = pendingStore.save(
        courseId = courseId,
        segment = WavSegment(
            id = segmentId,
            startOffsetMs = 0L,
            endOffsetMs = 1_000L,
            bytes = byteArrayOf(1, 2, 3),
        ),
    )

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1_000L
    }
}
