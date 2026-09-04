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
import com.classsentinel.core.study.StudyArtifactGenerator
import com.classsentinel.core.study.StudyGenerationResult
import com.classsentinel.data.AppDatabase
import com.classsentinel.data.SettingsRepositoryHolder
import com.classsentinel.data.entities.StudyArtifactEntity
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Task 27/28：按课程和产物类型执行一次人工学习任务。
 * WorkManager 数据只有 courseId/type/mode；转写、原文和 API key 均在运行时从本地读取。
 */
class StudyArtifactWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    @VisibleForTesting
    var dependencies: StudyArtifactWorkerDependencies? = null

    override suspend fun doWork(): ListenableWorker.Result {
        val courseId = inputData.getLong(KEY_COURSE_ID, INVALID_COURSE_ID)
        val type = inputData.getString(KEY_ARTIFACT_TYPE).orEmpty()
        val mode = inputData.getString(KEY_MODE).orEmpty()
        if (courseId <= 0L || !isValidType(type) || !isValidMode(type, mode)) {
            return failure(ERROR_CODE_INVALID_INPUT)
        }

        val deps = dependencies ?: StudyArtifactWorkerRuntime.dependencies(applicationContext)
        val current = deps.artifactForCourse(courseId, type)
        var sourceFailure: String? = null
        val source = try {
            deps.transcriptForCourse(courseId, type, mode)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            sourceFailure = ERROR_CODE_SOURCE
            ""
        }
        val now = System.currentTimeMillis()
        val preservedContent = if (type == StudyArtifactEntity.TYPE_BILINGUAL_SUMMARY) {
            source.takeIf { it.isNotBlank() }
                ?.let { StudyArtifactGenerator.encodeBilingual(it, null) }
                ?: current?.contentJson
        } else {
            null
        }
        val base = current ?: StudyArtifactEntity(
            courseId = courseId,
            type = type,
            createdTs = now,
            updatedTs = now,
        )
        val running = base.copy(
            status = StudyArtifactEntity.STATUS_RUNNING,
            contentJson = preservedContent,
            error = null,
            updatedTs = now,
        )
        val artifactId = deps.saveArtifact(running)
        val persistedRunning = running.copy(id = artifactId)

        suspend fun terminalFailure(code: String): ListenableWorker.Result {
            deps.saveArtifact(
                persistedRunning.copy(
                    status = StudyArtifactEntity.STATUS_FAILED,
                    contentJson = preservedContent,
                    model = null,
                    error = code,
                    updatedTs = System.currentTimeMillis(),
                ),
            )
            return failure(code)
        }

        if (sourceFailure != null) return terminalFailure(sourceFailure!!)
        if (source.isBlank()) return terminalFailure(ERROR_CODE_EMPTY_SOURCE)

        val config = try {
            deps.aiConfig()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (config == null || config.baseUrl.isBlank() || config.apiKey.isBlank() || config.model.isBlank()) {
            return terminalFailure(ERROR_CODE_CONFIG)
        }

        return try {
            val generated = when (type) {
                StudyArtifactEntity.TYPE_FLASHCARDS ->
                    deps.generator.generateFlashcards(source, config)
                StudyArtifactEntity.TYPE_QUIZ ->
                    deps.generator.generateQuiz(source, config)
                StudyArtifactEntity.TYPE_BILINGUAL_SUMMARY ->
                    if (mode == MODE_MARKED_TEXT) {
                        deps.generator.translateMarkedText(source, config)
                    } else {
                        deps.generator.generateBilingualSummary(source, config)
                    }
                else -> StudyGenerationResult.Failed(ERROR_CODE_INVALID_INPUT)
            }
            when (generated) {
                is StudyGenerationResult.Success<*> -> {
                    deps.saveArtifact(
                        persistedRunning.copy(
                            status = StudyArtifactEntity.STATUS_SUCCEEDED,
                            contentJson = generated.contentJson,
                            model = config.model,
                            error = null,
                            updatedTs = System.currentTimeMillis(),
                        ),
                    )
                    ListenableWorker.Result.success()
                }

                is StudyGenerationResult.Failed -> terminalFailure(safeErrorCode(generated.errorCode))
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    deps.saveArtifact(
                        persistedRunning.copy(
                            status = StudyArtifactEntity.STATUS_QUEUED,
                            contentJson = preservedContent,
                            error = null,
                            updatedTs = System.currentTimeMillis(),
                        ),
                    )
                }
            }
            throw e
        } catch (_: Exception) {
            terminalFailure(ERROR_CODE_GENERATION)
        }
    }

    private fun failure(code: String): ListenableWorker.Result =
        ListenableWorker.Result.failure(
            Data.Builder().putString(KEY_ERROR_CODE, code).build(),
        )

    private fun safeErrorCode(raw: String): String = when (raw) {
        ERROR_CODE_EMPTY_SOURCE,
        ERROR_CODE_CONFIG,
        ERROR_CODE_GENERATION,
        StudyArtifactGenerator.ERROR_EMPTY_RESPONSE,
        StudyArtifactGenerator.ERROR_INVALID_JSON,
        StudyArtifactGenerator.ERROR_INVALID_REQUEST,
        StudyArtifactGenerator.ERROR_OUTPUT_TOO_LONG,
        -> raw
        else -> ERROR_CODE_GENERATION
    }

    companion object {
        const val KEY_COURSE_ID = "courseId"
        const val KEY_ARTIFACT_TYPE = "artifactType"
        const val KEY_MODE = "mode"
        const val KEY_ERROR_CODE = "errorCode"
        const val INVALID_COURSE_ID = -1L

        const val MODE_FULL_TRANSCRIPT = "FULL_TRANSCRIPT"
        const val MODE_MARKED_TEXT = "MARKED_TEXT"

        const val ERROR_CODE_INVALID_INPUT = "INVALID_INPUT"
        const val ERROR_CODE_EMPTY_SOURCE = "EMPTY_SOURCE"
        const val ERROR_CODE_SOURCE = "SOURCE_FAILED"
        const val ERROR_CODE_CONFIG = "CONFIG"
        const val ERROR_CODE_GENERATION = "GENERATION_FAILED"
        const val ERROR_CODE_QUEUE = "QUEUE_FAILED"

        private const val BACKOFF_DELAY_MILLIS = 30_000L
        private const val UNIQUE_WORK_PREFIX = "study-artifact-"

        fun isValidType(type: String): Boolean = type in setOf(
            StudyArtifactEntity.TYPE_FLASHCARDS,
            StudyArtifactEntity.TYPE_QUIZ,
            StudyArtifactEntity.TYPE_BILINGUAL_SUMMARY,
        )

        fun isValidMode(type: String, mode: String): Boolean =
            mode == MODE_FULL_TRANSCRIPT ||
                (type == StudyArtifactEntity.TYPE_BILINGUAL_SUMMARY && mode == MODE_MARKED_TEXT)

        fun buildRequest(courseId: Long, type: String, mode: String): OneTimeWorkRequest =
            OneTimeWorkRequest.Builder(StudyArtifactWorker::class.java)
                .setInputData(
                    Data.Builder()
                        .putLong(KEY_COURSE_ID, courseId)
                        .putString(KEY_ARTIFACT_TYPE, type)
                        .putString(KEY_MODE, mode)
                        .build(),
                )
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

        fun uniqueWorkName(courseId: Long, type: String): String =
            UNIQUE_WORK_PREFIX + courseId + "-" + type.lowercase()

        /** 人工入口：先把产物置为 QUEUED，再提交同课程/类型唯一任务。 */
        suspend fun enqueueManual(
            context: Context,
            db: AppDatabase,
            courseId: Long,
            type: String,
            mode: String = MODE_FULL_TRANSCRIPT,
        ): Boolean {
            if (courseId <= 0L || !isValidType(type) || !isValidMode(type, mode)) return false
            val appContext = context.applicationContext
            val dao = db.studyArtifactDao()
            return try {
                val now = System.currentTimeMillis()
                val current = withContext(Dispatchers.IO) {
                    dao.getForCourseAndType(courseId, type)
                }
                val preserved = if (type == StudyArtifactEntity.TYPE_BILINGUAL_SUMMARY) {
                    withContext(Dispatchers.IO) {
                        val chunks = db.transcriptDao().getForCourse(courseId)
                            .filter { mode == MODE_FULL_TRANSCRIPT || it.isMarked }
                        val original = chunks.asSequence()
                            .map { it.text.trim() }
                            .filter { it.isNotEmpty() }
                            .joinToString("\n")
                        original.takeIf { it.isNotBlank() }?.let {
                            StudyArtifactGenerator.encodeBilingual(it, null)
                        } ?: current?.contentJson
                    }
                } else {
                    null
                }
                val queued = (current ?: StudyArtifactEntity(
                    courseId = courseId,
                    type = type,
                    createdTs = now,
                    updatedTs = now,
                )).copy(
                    status = StudyArtifactEntity.STATUS_QUEUED,
                    contentJson = preserved,
                    error = null,
                    updatedTs = now,
                )
                withContext(Dispatchers.IO) { dao.upsert(queued) }
                WorkManager.getInstance(appContext).enqueueUniqueWork(
                    uniqueWorkName(courseId, type),
                    ExistingWorkPolicy.KEEP,
                    buildRequest(courseId, type, mode),
                )
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching {
                        dao.getForCourseAndType(courseId, type)?.let { queued ->
                            dao.updateContent(
                                id = queued.id,
                                status = StudyArtifactEntity.STATUS_FAILED,
                                contentJson = queued.contentJson,
                                model = queued.model,
                                error = ERROR_CODE_QUEUE,
                                updatedTs = System.currentTimeMillis(),
                            )
                        }
                    }
                }
                false
            }
        }
    }
}

interface StudyArtifactWorkerDependencies {
    val generator: StudyArtifactGenerator
    suspend fun transcriptForCourse(courseId: Long, type: String, mode: String): String
    suspend fun aiConfig(): LlmConfig?
    suspend fun artifactForCourse(courseId: Long, type: String): StudyArtifactEntity?
    suspend fun saveArtifact(artifact: StudyArtifactEntity): Long
}

/** 生产运行时：Room 提供正文/状态，DataStore/Keystore 提供 AI 配置。 */
object StudyArtifactWorkerRuntime {
    fun dependencies(context: Context): StudyArtifactWorkerDependencies {
        val appContext = context.applicationContext
        val db = AppDatabase.get(appContext)
        val settings = SettingsRepositoryHolder.get(appContext)
        return object : StudyArtifactWorkerDependencies {
            override val generator: StudyArtifactGenerator = StudyArtifactGenerator()

            override suspend fun transcriptForCourse(courseId: Long, type: String, mode: String): String =
                withContext(Dispatchers.IO) {
                    db.transcriptDao().getForCourse(courseId)
                        .asSequence()
                        .filter { type != StudyArtifactEntity.TYPE_BILINGUAL_SUMMARY || mode == StudyArtifactWorker.MODE_FULL_TRANSCRIPT || it.isMarked }
                        .map { it.text.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString("\n")
                }

            override suspend fun aiConfig(): LlmConfig? = withContext(Dispatchers.IO) {
                val ai = settings.aiSettingsFlow.first()
                LlmConfig(ai.baseUrl, ai.apiKey, ai.model)
                    .takeIf { it.baseUrl.isNotBlank() && it.apiKey.isNotBlank() && it.model.isNotBlank() }
            }

            override suspend fun artifactForCourse(courseId: Long, type: String): StudyArtifactEntity? =
                withContext(Dispatchers.IO) { db.studyArtifactDao().getForCourseAndType(courseId, type) }

            override suspend fun saveArtifact(artifact: StudyArtifactEntity): Long =
                withContext(Dispatchers.IO) { db.studyArtifactDao().upsert(artifact) }
        }
    }
}
