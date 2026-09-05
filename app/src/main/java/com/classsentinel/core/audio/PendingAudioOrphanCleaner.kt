package com.classsentinel.core.audio

import com.classsentinel.data.PendingAudioDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of one safe pending-audio orphan sweep. */
data class PendingAudioOrphanSweepResult(
    val deleted: Int = 0,
    val failures: Int = 0,
)

/**
 * Finds pending-audio files no longer referenced by any DB row and delegates the actual
 * root/name/grace checks to [PendingAudioStore].
 */
class PendingAudioOrphanCleaner(
    private val store: PendingAudioStore,
    private val dao: PendingAudioDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val orphanGracePeriodMs: Long = DEFAULT_ORPHAN_GRACE_PERIOD_MS,
) {
    init {
        require(orphanGracePeriodMs >= 0L) { "ORPHAN_GRACE_PERIOD_NEGATIVE" }
    }

    suspend fun sweep(): PendingAudioOrphanSweepResult = withContext(Dispatchers.IO) {
        val referencedPaths = dao.getAll()
            .asSequence()
            .map { java.io.File(it.filePath).canonicalPath }
            .toSet()
        store.sweepOrphans(
            referencedPaths = referencedPaths,
            nowMillis = clock(),
            gracePeriodMs = orphanGracePeriodMs,
        )
    }

    companion object {
        const val DEFAULT_ORPHAN_GRACE_PERIOD_MS = 10 * 60 * 1_000L
    }
}
