package com.classsentinel.worker

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.classsentinel.core.audio.PendingAudioStore
import com.classsentinel.data.AppDatabase
import com.classsentinel.data.HistoryCleanup
import com.classsentinel.data.HistoryRepository
import com.classsentinel.data.SettingsRepositoryHolder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Task 17：按 SettingsRepository 中的 retentionDays 定期清理已结束历史。
 *
 * Worker 只把 retentionDays 和当前时间传给数据仓库；数据库事务与私有文件清理由
 * [HistoryRepository] 负责。异常返回 retry，不把异常文本写入 WorkManager Data。
 */
class RetentionCleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    @VisibleForTesting
    var dependencies: RetentionCleanupDependencies? = null

    override suspend fun doWork(): Result {
        val deps = dependencies ?: RetentionCleanupRuntime.dependencies(applicationContext)
        return try {
            deps.cleanup.deleteExpired(
                retentionDays = deps.retentionDays(),
                nowMillis = deps.clock(),
            )
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 数据库/文件系统暂时失败交给 WorkManager 重试；不暴露异常原文。
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "history-retention-cleanup"
        const val REPEAT_INTERVAL_DAYS = 1L

        /** 每日执行；历史清理不需要网络。 */
        fun buildRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<RetentionCleanupWorker>(
                REPEAT_INTERVAL_DAYS,
                TimeUnit.DAYS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build(),
                )
                .build()

        /** KEEP 防止应用启动和手动触发重复创建周期任务。 */
        fun enqueueUnique(workManager: WorkManager) {
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                buildRequest(),
            )
        }
    }
}

/** Worker 的可注入 seam；生产默认实现从真实 Room/Settings 组装。 */
interface RetentionCleanupDependencies {
    val cleanup: HistoryCleanup
    val retentionDays: suspend () -> String
    val clock: () -> Long
}

object RetentionCleanupRuntime {
    fun dependencies(context: Context): RetentionCleanupDependencies {
        val appContext = context.applicationContext
        val db = AppDatabase.get(appContext)
        val pendingDao = db.pendingAudioDao()
        val repository = HistoryRepository(
            db = db,
            pendingAudioStore = PendingAudioStore(
                rootDir = File(appContext.noBackupFilesDir, "pending-audio"),
                dao = pendingDao,
            ),
        )
        val settings = SettingsRepositoryHolder.get(appContext)
        return object : RetentionCleanupDependencies {
            override val cleanup: HistoryCleanup = repository
            override val retentionDays: suspend () -> String = {
                settings.retentionDaysFlow.first()
            }
            override val clock: () -> Long = System::currentTimeMillis
        }
    }
}
