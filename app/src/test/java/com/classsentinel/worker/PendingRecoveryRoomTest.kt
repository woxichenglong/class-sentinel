package com.classsentinel.worker

import androidx.room.Room
import com.classsentinel.data.AppDatabase
import com.classsentinel.data.entities.PendingAudioEntity
import com.classsentinel.data.entities.TranscriptChunkEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Recovery 的真实 Room 边界测试：验证 transcript 与 pending 消费共用事务，
 * 以及只针对 recovery 的 nullable key 不影响 live transcript。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingRecoveryRoomTest {
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
    fun `transaction failure rolls back transcript and keeps pending`() = runBlocking {
        val pending = PendingAudioEntity(
            courseId = 9L,
            segmentId = "seg-1",
            filePath = "private/seg-1.wav",
            durationMs = 1_000L,
            createdTs = 100L,
        )
        val pendingId = db.pendingAudioDao().insert(pending)
        val stored = pending.copy(id = pendingId)
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_pending_delete
            BEFORE DELETE ON pending_audio_segments
            BEGIN
                SELECT RAISE(ABORT, 'synthetic pending delete failure');
            END
            """.trimIndent(),
        )
        val queue = RoomPendingAudioQueue(db.pendingAudioDao(), db.transcriptDao(), db)
        val chunk = recoveryChunk(stored)

        var failed = false
        try {
            queue.recordTranscript(stored, chunk)
        } catch (_: Throwable) {
            failed = true
        }

        assertTrue("the injected pending delete failure must reach the caller", failed)
        assertEquals(1, db.pendingAudioDao().getByState("PENDING").size)
        assertTrue("Room transaction must roll back transcript insert", db.transcriptDao().getForCourse(9L).isEmpty())
    }

    @Test
    fun `same recovery key is idempotent and live null keys remain insertable`() = runBlocking {
        val pending = PendingAudioEntity(
            courseId = 9L,
            segmentId = "seg-1",
            filePath = "private/seg-1.wav",
            durationMs = 1_000L,
            createdTs = 100L,
        )
        val pendingId = db.pendingAudioDao().insert(pending)
        val stored = pending.copy(id = pendingId)
        val queue = RoomPendingAudioQueue(db.pendingAudioDao(), db.transcriptDao(), db)
        val chunk = recoveryChunk(stored)

        queue.recordTranscript(stored, chunk)
        queue.recordTranscript(stored, chunk)

        val live1 = TranscriptChunkEntity(
            courseId = 9L,
            seq = 2,
            text = "live-1",
            ts = 200L,
            segmentId = "",
        )
        val live2 = live1.copy(id = 0L, seq = 3, text = "live-2", ts = 300L)
        db.transcriptDao().insert(live1)
        db.transcriptDao().insert(live2)

        val rows = db.transcriptDao().getForCourse(9L)
        assertEquals("one recovery row plus two live rows", 3, rows.size)
        assertEquals(1, rows.count { it.recoveryKey == "pending-audio:$pendingId" })
        assertEquals(2, rows.count { it.recoveryKey == null })
        assertTrue(db.pendingAudioDao().getByState("PENDING").isEmpty())
    }

    private fun recoveryChunk(entity: PendingAudioEntity) = TranscriptChunkEntity(
        courseId = entity.courseId,
        seq = 0,
        text = "recovered",
        ts = 1_000L,
        segmentId = entity.segmentId,
        recoveryKey = "pending-audio:${entity.id}",
        startOffsetMs = 0L,
        endOffsetMs = entity.durationMs,
    )
}
