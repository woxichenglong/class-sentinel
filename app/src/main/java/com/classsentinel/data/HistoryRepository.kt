package com.classsentinel.data

import androidx.room.withTransaction
import com.classsentinel.core.audio.PendingAudioOrphanCleaner
import com.classsentinel.core.audio.PendingAudioStore
import com.classsentinel.data.entities.PendingAudioEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of a history cleanup operation. */
data class HistoryCleanupResult(
    val deletedCourses: Int = 0,
    val deletedPendingFiles: Int = 0,
    val pendingFileFailures: Int = 0,
)

/** Retention cleanup seam used by the WorkManager adapter. */
interface HistoryCleanup {
    suspend fun deleteExpired(retentionDays: String, nowMillis: Long): HistoryCleanupResult
}

/**
 * History data repository: applies retentionDays and clears all history safely.
 *
 * Room child-row deletion runs in one transaction, with child rows removed before courses.
 * Pending files are deleted only after the database transaction commits, so a DAO failure
 * never removes a file first. File failures are reported for a later cleanup attempt.
 */
class HistoryRepository(
    private val db: AppDatabase,
    private val pendingAudioStore: PendingAudioStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : HistoryCleanup {
    private val orphanCleaner = PendingAudioOrphanCleaner(
        store = pendingAudioStore,
        dao = db.pendingAudioDao(),
        clock = clock,
    )

    /**
     * Delete finished history older than the configured retention period.
     *
     * Only courses with a non-null [endTs] are eligible; RUNNING courses are never removed
     * by automatic retention. `forever` and invalid values are safe no-ops.
     */
    suspend fun deleteExpired(retentionDays: String): HistoryCleanupResult =
        deleteExpired(retentionDays, clock())

    override suspend fun deleteExpired(
        retentionDays: String,
        nowMillis: Long,
    ): HistoryCleanupResult {
        val days = parseRetentionDays(retentionDays)
            ?: return HistoryCleanupResult().also { sweepOrphansBestEffort() }
        val ageMillis = days.coerceAtMost(Long.MAX_VALUE / DAY_MILLIS) * DAY_MILLIS
        val cutoffTs = nowMillis - ageMillis
        val (deletedCourses, pending) = db.withTransaction {
            val courses = db.courseDao().getFinishedBefore(cutoffTs)
            val courseIds = courses.map { it.id }.toSet()
            val pendingForCourses = if (courseIds.isEmpty()) {
                emptyList()
            } else {
                db.pendingAudioDao().getAll().filter { it.courseId in courseIds }
            }
            courses.forEach { course ->
                db.eventDao().deleteForCourse(course.id)
                db.transcriptDao().deleteForCourse(course.id)
                db.studyArtifactDao().deleteForCourse(course.id)
                db.pendingAudioDao().deleteForCourse(course.id)
                db.courseDao().deleteById(course.id)
            }
            courses.size to pendingForCourses
        }
        return deletePendingFiles(pending, deletedCourses).also { sweepOrphansBestEffort() }
    }

    /**
     * Clear all history.
     *
     * Delete events, transcript chunks, pending rows, and finally courses in one database
     * transaction. Pending files are removed only after that transaction commits.
     */
    suspend fun clearHistory(): HistoryCleanupResult {
        val (deletedCourses, pending) = db.withTransaction {
            val courses = db.courseDao().getAll()
            val pendingAll = db.pendingAudioDao().getAll()
            db.eventDao().clearAll()
            db.transcriptDao().clearAll()
            db.studyArtifactDao().clearAll()
            db.pendingAudioDao().clearAll()
            db.courseDao().clearAll()
            courses.size to pendingAll
        }
        return deletePendingFiles(pending, deletedCourses).also { sweepOrphansBestEffort() }
    }

    private suspend fun sweepOrphansBestEffort() {
        runCatching { orphanCleaner.sweep() }
    }

    private suspend fun deletePendingFiles(
        pending: List<PendingAudioEntity>,
        deletedCourses: Int,
    ): HistoryCleanupResult = withContext(Dispatchers.IO) {
        val deleted = pending.count { pendingAudioStore.deleteFile(it) }
        HistoryCleanupResult(
            deletedCourses = deletedCourses,
            deletedPendingFiles = deleted,
            pendingFileFailures = pending.size - deleted,
        )
    }

    private fun parseRetentionDays(raw: String): Long? {
        val value = raw.trim()
        if (value.equals(FOREVER, ignoreCase = true)) return null
        return value.toLongOrNull()?.takeIf { it > 0L }
    }

    private companion object {
        const val FOREVER = "forever"
        const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
