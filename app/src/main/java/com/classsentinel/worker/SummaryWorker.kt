package com.classsentinel.worker

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.classsentinel.core.llm.LlmConfig
import com.classsentinel.core.summary.SummaryGenerationResult
import com.classsentinel.core.summary.SummaryGenerator
import com.classsentinel.core.summary.SummaryTemplate
import com.classsentinel.core.summary.SummaryTemplates
import com.classsentinel.data.AppDatabase
import com.classsentinel.data.SettingsRepositoryHolder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private fun LlmConfig.isConfigured(): Boolean =
    baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

/** 持久化课程总结的状态机。 */
object SummaryStatus {
    const val NONE = "NONE"
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val SUCCEEDED = "SUCCEEDED"
    const val FAILED = "FAILED"
}

/**
 * Task 18：按 courseId 读取转写并生成持久化总结。
 *
 * WorkManager input/output 只携带 ID 和安全错误码；课堂正文、API key 和 provider body
 * 均在 Worker 进程内读取/消费，不进入 WorkManager 数据。
 */
class SummaryWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    /** 运行时依赖 seam；测试注入，生产走 [SummaryWorkerRuntime]。 */
    @VisibleForTesting
    var dependencies: SummaryWorkerDependencies? = null

    override suspend fun doWork(): ListenableWorker.Result {
        val courseId = inputData.getLong(KEY_COURSE_ID, INVALID_COURSE_ID)
        if (courseId <= 0L) return failure(ERROR_CODE_INVALID_INPUT)

        val deps = dependencies ?: SummaryWorkerRuntime.dependencies(applicationContext)
        val transcript = deps.transcriptForCourse(courseId)
        if (transcript.isBlank()) {
            deps.updateSummary(courseId, SummaryStatus.NONE, null, null)
            return ListenableWorker.Result.success()
        }

        val config = deps.aiConfig()
        if (config == null || !config.isConfigured()) {
            deps.updateSummary(courseId, SummaryStatus.FAILED, null, ERROR_CODE_CONFIG)
            return failure(ERROR_CODE_CONFIG)
        }

        deps.updateSummary(courseId, SummaryStatus.RUNNING, null, null)
        return try {
            when (val generated = deps.generator.generateResult(transcript, config, deps.summaryTemplate())) {
                SummaryGenerationResult.NoContent -> {
                    deps.updateSummary(courseId, SummaryStatus.NONE, null, null)
                    ListenableWorker.Result.success()
                }

                is SummaryGenerationResult.Success -> {
                    deps.updateSummary(
                        courseId,
                        SummaryStatus.SUCCEEDED,
                        generated.markdown,
                        null,
                    )
                    ListenableWorker.Result.success()
                }

                is SummaryGenerationResult.Failed -> {
                    val errorCode = safeErrorCode(generated.code)
                    deps.updateSummary(courseId, SummaryStatus.FAILED, null, errorCode)
                    failure(errorCode)
                }
            }
        } catch (e: CancellationException) {
            // WorkManager 停止时保留可重试状态；取消本身仍必须原样传播。
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    deps.updateSummary(courseId, SummaryStatus.QUEUED, null, null)
                }
            }
            throw e
        } catch (_: Exception) {
            deps.updateSummary(courseId, SummaryStatus.FAILED, null, ERROR_CODE_GENERATION)
            failure(ERROR_CODE_GENERATION)
        }
    }

    private fun failure(code: String): ListenableWorker.Result =
        ListenableWorker.Result.failure(
            Data.Builder()
                .putString(KEY_ERROR_CODE, code)
                .build(),
        )

    private fun safeErrorCode(raw: String): String = when (raw) {
        ERROR_CODE_EMPTY,
        ERROR_CODE_GENERATION,
        ERROR_CODE_CONFIG,
        ERROR_CODE_QUEUE,
        -> raw
        else -> ERROR_CODE_GENERATION
    }

    companion object {
        const val KEY_COURSE_ID = "courseId"
        const val KEY_ERROR_CODE = "errorCode"
        const val INVALID_COURSE_ID = -1L

        const val ERROR_CODE_EMPTY = "EMPTY_RESPONSE"
        const val ERROR_CODE_GENERATION = "GENERATION_FAILED"
        const val ERROR_CODE_CONFIG = "CONFIG"
        const val ERROR_CODE_QUEUE = "QUEUE_FAILED"
        const val ERROR_CODE_INVALID_INPUT = "INVALID_INPUT"

        const val BACKOFF_DELAY_MILLIS = 30_000L
        const val MAX_BACKOFF_DELAY_MILLIS = 1_800_000L
        private const val UNIQUE_WORK_PREFIX = "course-summary-"

        /** 单次总结只携带 courseId，网络恢复后执行，指数退避由 WorkManager 管理。 */
        fun buildRequest(courseId: Long): OneTimeWorkRequest =
            OneTimeWorkRequest.Builder(SummaryWorker::class.java)
                .setInputData(Data.Builder().putLong(KEY_COURSE_ID, courseId).build())
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_DELAY_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                .build()

        fun uniqueWorkName(courseId: Long): String = UNIQUE_WORK_PREFIX + courseId

        /** 同一课程保持单个总结任务，防止重复 STOP/重试产生重复 LLM 调用。 */
        fun enqueueUnique(workManager: WorkManager, courseId: Long) {
            workManager.enqueueUniqueWork(
                uniqueWorkName(courseId),
                ExistingWorkPolicy.KEEP,
                buildRequest(courseId),
            )
        }

        /**
         * 手动生成/重试入口：不读取 autoSummary 开关，先原子写入 QUEUED，再提交唯一任务。
         * 课程不存在或已有 QUEUED/RUNNING 任务时返回 false；WorkManager 失败会留下安全错误码。
         */
        suspend fun enqueueManual(
            context: Context,
            db: AppDatabase,
            courseId: Long,
        ): Boolean {
            if (courseId <= 0L) return false
            val appContext = context.applicationContext
            val dependencies = object : SummaryManualScheduleDependencies {
                override suspend fun markQueued(courseId: Long): Boolean = withContext(Dispatchers.IO) {
                    db.courseDao().markSummaryQueued(courseId) > 0
                }

                override fun enqueue(courseId: Long) {
                    enqueueUnique(WorkManager.getInstance(appContext), courseId)
                }
            }
            return try {
                enqueueManual(courseId, dependencies)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 状态已落为 QUEUED 但任务入口失败时，保留可见且可重试的安全终态。
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching {
                        db.courseDao().updateSummary(
                            courseId,
                            SummaryStatus.FAILED,
                            null,
                            ERROR_CODE_QUEUE,
                        )
                    }
                }
                false
            }
        }

        /**
         * STOP finalize 成功后调用：最新读取开关、转写和 AI 配置，满足条件才标 QUEUED 并排队。
         * 返回 false 表示本次没有排队（开关关闭、无内容、未配置或课程不存在）。
         */
        suspend fun enqueueIfEligible(
            context: Context,
            db: AppDatabase,
            courseId: Long,
        ): Boolean {
            val appContext = context.applicationContext
            val settings = SettingsRepositoryHolder.get(appContext)
            val dependencies = object : SummaryScheduleDependencies {
                override suspend fun autoSummaryEnabled(): Boolean = withContext(Dispatchers.IO) {
                    try {
                        settings.autoSummaryFlow.first()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                }

                override suspend fun transcriptForCourse(courseId: Long): String = withContext(Dispatchers.IO) {
                    db.transcriptDao().getForCourse(courseId)
                        .asSequence()
                        .map { it.text.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString("\n")
                }

                override suspend fun aiConfig(): LlmConfig? = withContext(Dispatchers.IO) {
                    val ai = try {
                        settings.aiSettingsFlow.first()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        return@withContext null
                    }
                    LlmConfig(ai.baseUrl, ai.apiKey, ai.model).takeIf { it.isConfigured() }
                }

                override suspend fun markQueued(courseId: Long): Boolean = withContext(Dispatchers.IO) {
                    db.courseDao().updateSummary(courseId, SummaryStatus.QUEUED, null, null) > 0
                }

                override fun enqueue(courseId: Long) {
                    enqueueUnique(WorkManager.getInstance(appContext), courseId)
                }
            }

            return try {
                enqueueIfEligible(courseId, dependencies)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // QUEUED 已落库但 WorkManager 入口失败时，留下可见的安全终态。
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching {
                        db.courseDao().updateSummary(
                            courseId,
                            SummaryStatus.FAILED,
                            null,
                            ERROR_CODE_QUEUE,
                        )
                    }
                }
                false
            }
        }

        /** 可独立测试的资格判断与顺序：所有条件满足后才写 QUEUED，再提交任务。 */
        internal suspend fun enqueueIfEligible(
            courseId: Long,
            dependencies: SummaryScheduleDependencies,
        ): Boolean {
            if (!dependencies.autoSummaryEnabled()) return false
            if (dependencies.transcriptForCourse(courseId).isBlank()) return false
            val config = dependencies.aiConfig() ?: return false
            if (!config.isConfigured()) return false
            if (!dependencies.markQueued(courseId)) return false
            dependencies.enqueue(courseId)
            return true
        }

        /** 手动入口的纯 seam：不依赖 autoSummary，顺序必须是 markQueued → enqueue。 */
        internal suspend fun enqueueManual(
            courseId: Long,
            dependencies: SummaryManualScheduleDependencies,
        ): Boolean {
            if (courseId <= 0L) return false
            if (!dependencies.markQueued(courseId)) return false
            dependencies.enqueue(courseId)
            return true
        }
    }
}

/** Worker 的纯数据 seam；生产实现从 Room/DataStore 执行时读取。 */
interface SummaryWorkerDependencies {
    val generator: SummaryGenerator
    suspend fun transcriptForCourse(courseId: Long): String
    suspend fun aiConfig(): LlmConfig?
    /** 读取执行时的模板；旧测试 seam 未实现时保持默认四段式兼容。 */
    suspend fun summaryTemplate(): SummaryTemplate = SummaryTemplates.DEFAULT
    suspend fun updateSummary(
        courseId: Long,
        status: String,
        markdown: String?,
        errorCode: String?,
    )
}

/** STOP 后自动总结资格判断的纯 seam，避免测试依赖全局 DataStore/WorkManager。 */
interface SummaryScheduleDependencies {
    suspend fun autoSummaryEnabled(): Boolean
    suspend fun transcriptForCourse(courseId: Long): String
    suspend fun aiConfig(): LlmConfig?
    suspend fun markQueued(courseId: Long): Boolean
    fun enqueue(courseId: Long)
}

/** 手动生成/Retry 的最小调度 seam；资格由 UI 状态和 Worker 执行阶段共同保证。 */
interface SummaryManualScheduleDependencies {
    suspend fun markQueued(courseId: Long): Boolean
    fun enqueue(courseId: Long)
}

/** 真实运行时接线：Room 转写/课程 + DataStore AI 配置。 */
object SummaryWorkerRuntime {
    fun dependencies(context: Context): SummaryWorkerDependencies {
        val appContext = context.applicationContext
        val db = AppDatabase.get(appContext)
        val settings = SettingsRepositoryHolder.get(appContext)
        return object : SummaryWorkerDependencies {
            override val generator: SummaryGenerator = SummaryGenerator()

            override suspend fun transcriptForCourse(courseId: Long): String =
                withContext(Dispatchers.IO) {
                    db.transcriptDao().getForCourse(courseId)
                        .asSequence()
                        .map { it.text.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString("\n")
                }

            override suspend fun aiConfig(): LlmConfig? = withContext(Dispatchers.IO) {
                val ai = try {
                    settings.aiSettingsFlow.first()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@withContext null
                }
                ai.let { LlmConfig(it.baseUrl, it.apiKey, it.model) }.takeIf { it.isConfigured() }
            }

            override suspend fun summaryTemplate(): SummaryTemplate = withContext(Dispatchers.IO) {
                try {
                    settings.summaryTemplateFlow.first()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    SummaryTemplates.DEFAULT
                }
            }

            override suspend fun updateSummary(
                courseId: Long,
                status: String,
                markdown: String?,
                errorCode: String?,
            ) {
                withContext(Dispatchers.IO) {
                    db.courseDao().updateSummary(courseId, status, markdown, errorCode)
                }
            }
        }
    }
}
