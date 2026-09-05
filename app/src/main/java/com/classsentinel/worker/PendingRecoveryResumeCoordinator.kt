package com.classsentinel.worker

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.classsentinel.data.AppDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 配置修复后的 pending recovery 恢复边界。
 *
 * 只在仍有 PENDING 且当前 unique work 没有健康执行中的情况下替换旧的 global-failure work；
 * 没有 pending 或已有 ENQUEUED/RUNNING/BLOCKED work 时不产生 WorkManager 副作用。
 */
class PendingRecoveryResumeCoordinator(
    private val pendingCount: suspend () -> Int,
    private val workStates: suspend () -> List<WorkInfo.State>,
    private val enqueueAfterGlobalFailure: () -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun resumeAfterAsrConfigChange(): Boolean {
        if (pendingCount() == 0) return false
        val activeStates = workStates()
        if (activeStates.any { it == WorkInfo.State.ENQUEUED ||
                it == WorkInfo.State.RUNNING ||
                it == WorkInfo.State.BLOCKED
        }) {
            return false
        }
        enqueueAfterGlobalFailure()
        return true
    }

    companion object {
        fun create(
            context: Context,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): PendingRecoveryResumeCoordinator {
            val appContext = context.applicationContext
            val database = AppDatabase.get(appContext)
            val workManager = WorkManager.getInstance(appContext)
            return PendingRecoveryResumeCoordinator(
                pendingCount = {
                    withContext(ioDispatcher) {
                        database.pendingAudioDao().getByState("PENDING").size
                    }
                },
                workStates = {
                    withContext(ioDispatcher) {
                        workManager.getWorkInfosForUniqueWork(
                            PendingTranscriptionWorker.UNIQUE_WORK_NAME,
                        ).get().map { it.state }
                    }
                },
                enqueueAfterGlobalFailure = {
                    PendingTranscriptionWorker.enqueueAfterGlobalFailure(workManager)
                },
                ioDispatcher = ioDispatcher,
            )
        }
    }
}
