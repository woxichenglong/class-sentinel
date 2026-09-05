package com.classsentinel.worker

import androidx.room.Room
import com.classsentinel.data.AppDatabase
import com.classsentinel.data.entities.PendingAudioEntity
import com.classsentinel.data.entities.TranscriptChunkEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 课堂时间线契约：数据库写入 seq 与课堂音频 offset 解耦，恢复段按原始课堂位置展示。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingTimelineTest {
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
    fun `pending from minute ten remains near minute ten after recovery at minute fifty`() = runBlocking {
        val pending = PendingAudioEntity(
            courseId = 11L,
            segmentId = "old-10m",
            filePath = "private/old-10m.wav",
            durationMs = 1_000L,
            startOffsetMs = 10 * 60_000L,
            endOffsetMs = 10 * 60_000L + 1_000L,
            createdTs = 50 * 60_000L,
        )
        val pendingId = db.pendingAudioDao().insert(pending)
        val stored = pending.copy(id = pendingId)
        db.transcriptDao().insert(
            TranscriptChunkEntity(
                courseId = 11L,
                seq = 1,
                text = "minute-fifty-live",
                ts = 50 * 60_000L,
                startOffsetMs = 50 * 60_000L,
                endOffsetMs = 50 * 60_000L + 1_000L,
            ),
        )
        val queue = RoomPendingAudioQueue(db.pendingAudioDao(), db.transcriptDao(), db)

        queue.recordTranscript(
            stored,
            TranscriptChunkEntity(
                courseId = 11L,
                seq = 0,
                text = "minute-ten-recovered",
                ts = 50 * 60_000L,
                segmentId = stored.segmentId,
                recoveryKey = "pending-audio:$pendingId",
                startOffsetMs = stored.startOffsetMs,
                endOffsetMs = stored.endOffsetMs,
            ),
        )

        val rows = db.transcriptDao().getForCourse(11L)
        assertEquals(listOf("minute-ten-recovered", "minute-fifty-live"), rows.map { it.text })
        assertEquals(10 * 60_000L, rows.first().startOffsetMs)
    }

    @Test
    fun `equal and near offsets have deterministic classroom ordering`() = runBlocking {
        val rows = listOf(
            TranscriptChunkEntity(
                courseId = 12L,
                seq = 3,
                text = "same-end-seq3",
                ts = 3_000L,
                startOffsetMs = 1_000L,
                endOffsetMs = 2_000L,
            ),
            TranscriptChunkEntity(
                courseId = 12L,
                seq = 1,
                text = "same-end-seq1",
                ts = 1_000L,
                startOffsetMs = 1_000L,
                endOffsetMs = 2_000L,
            ),
            TranscriptChunkEntity(
                courseId = 12L,
                seq = 2,
                text = "near-end",
                ts = 2_000L,
                startOffsetMs = 1_000L,
                endOffsetMs = 1_500L,
            ),
            TranscriptChunkEntity(
                courseId = 12L,
                seq = 4,
                text = "later",
                ts = 4_000L,
                startOffsetMs = 1_001L,
                endOffsetMs = 2_001L,
            ),
        )
        rows.forEach { db.transcriptDao().insert(it) }

        assertEquals(
            listOf("near-end", "same-end-seq1", "same-end-seq3", "later"),
            db.transcriptDao().getForCourse(12L).map { it.text },
        )
        assertTrue(db.transcriptDao().getForCourse(12L).zipWithNext().all { (a, b) ->
            a.startOffsetMs < b.startOffsetMs ||
                (a.startOffsetMs == b.startOffsetMs && a.endOffsetMs <= b.endOffsetMs)
        })
    }
}
