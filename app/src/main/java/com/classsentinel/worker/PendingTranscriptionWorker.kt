package com.classsentinel.worker

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
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
import com.classsentinel.core.audio.PendingAudioStore
import com.classsentinel.core.audio.PendingAudioStore.LoadResult
import com.classsentinel.core.audio.WavSegment
import com.classsentinel.core.speech.AsrError
import com.classsentinel.core.speech.AsrException
import com.classsentinel.core.speech.ProductionAsrFactory
import com.classsentinel.core.speech.SegmentSpeechRouter
import com.classsentinel.data.AppDatabase
import com.classsentinel.data.PendingAudioDao
import com.classsentinel.data.TranscriptDao
import com.classsentinel.data.entities.PendingAudioEntity
import com.classsentinel.data.entities.TranscriptChunkEntity
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * v0.2 Task 16：失败音频段的离线转写 Worker。
 *
 * 由 WorkManager 调度（约束 CONNECTED、指数退避、延迟有上限），逐条消费
 * PENDING 队列：store.load → transcriber → sink 持久化 → store.delete。
 *
 * - Worker 默认依赖从应用 Room/私有文件和 DataStore 配置构造；测试仍可通过 [dependencies] 注入。
 * - 输出 Data 只含安全 kind/code；不写 message / 路径 / 音频 / 转写文本。
 * - 取消（[CancellationException]）原样传播，不吞、不重试、不删 pending。
 */
class PendingTranscriptionWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    /** 运行时依赖 seam；测试用 fake 覆盖。null 时解析真实默认 [PendingTranscriptionRuntime]。 */
    @VisibleForTesting
    var dependencies: PendingTranscriptionDependencies? = null

    override suspend fun doWork(): ListenableWorker.Result {
        val deps = dependencies ?: PendingTranscriptionRuntime.dependencies(applicationContext)
        val transcriber = deps.transcriber
        if (transcriber == null) {
            // 未配置 transcriber：安全 CONFIG 终态失败，绝不删除 pending。
            return failure(ERROR_KIND_CONFIG, ERROR_CODE_UNCONFIGURED)
        }

        val queue = deps.queue
        val store = deps.store
        val maxAttempts = deps.maxAttempts
        val boundedBatchLimit = deps.batchLimit.coerceAtLeast(1)
        val executionBudget = deps.executionBudget.coerceAtLeast(1)
        var processed = 0

        // 用有界批循环消费正常 backlog；预算耗尽才安排 continuation，避免一次 Worker
        // 长时间清理无上限队列，也不把正常 backlog 伪装成瞬态 retry。
        while (processed < executionBudget) {
            val pending = queue.pendingSegments()
            if (pending.isEmpty()) break
            val batch = pending.take(boundedBatchLimit).take(executionBudget - processed)

            // 本批次第一个安全存储错误（MISSING/CORRUPT）：继续处理/标记其余 pending 行，
            // 全部标记终态 FAILED 后，批次整体仍须以 STORAGE 终态 failure 返回（Task 16）。
            var firstStorageErrorCode: String? = null

            for (entity in batch) {
                if (isStopped) return ListenableWorker.Result.retry()

                when (val loadResult = store.load(entity)) {
                    is LoadResult.Missing -> {
                        // 缺失文件无法恢复：终态 FAILED + 固定安全存储错误类别，不无限重试。
                        queue.markFailed(entity, ERROR_KIND_STORAGE, ERROR_CODE_MISSING)
                        if (firstStorageErrorCode == null) firstStorageErrorCode = ERROR_CODE_MISSING
                        processed++
                        continue
                    }

                    is LoadResult.Corrupt -> {
                        // 损坏 WAV 同样不可重试：终态 FAILED + 安全存储错误类别。
                        queue.markFailed(entity, ERROR_KIND_STORAGE, ERROR_CODE_CORRUPT)
                        if (firstStorageErrorCode == null) firstStorageErrorCode = ERROR_CODE_CORRUPT
                        processed++
                        continue
                    }

                    is LoadResult.Success -> {
                        val segment = WavSegment(
                            id = entity.segmentId,
                            startOffsetMs = entity.startOffsetMs,
                            endOffsetMs = resolvedEndOffset(entity),
                            bytes = loadResult.bytes,
                        )

                        val outcome: kotlin.Result<PendingSegmentResult> = try {
                            transcriber.transcribe(segment)
                        } catch (e: CancellationException) {
                            throw e // 取消原样传播：不吞、不重试、不删 pending
                        } catch (t: Throwable) {
                            kotlin.Result.failure<PendingSegmentResult>(AsrException(t.normalizedAsrError()))
                        }

                        val failure: AsrError? = when {
                            outcome.isSuccess -> null
                            else -> {
                                val e = outcome.exceptionOrNull()
                                if (e is AsrException) e.error else e?.normalizedAsrError()
                            }
                        }
                        if (failure != null) {
                            val kind = failure.kind
                            val isRetriable = when (kind) {
                                AsrError.Kind.NETWORK,
                                AsrError.Kind.RATE_LIMIT,
                                AsrError.Kind.SERVER,
                                -> true
                                else -> false
                            }

                            if (isRetriable && entity.attempts + 1 < maxAttempts) {
                                // 保留 PENDING、递增 attempts，交给 WorkManager retry。
                                queue.recordAttemptFailure(entity, kind.safeCode(), failure.errorCode())
                                return ListenableWorker.Result.retry()
                            }

                            queue.markFailed(entity, kind.safeCode(), failure.errorCode())
                            return failure(kind.safeCode(), failure.errorCode())
                        }

                        val text = outcome.getOrThrow().text
                        val transcript = TranscriptChunkEntity(
                            courseId = entity.courseId,
                            seq = 0,
                            text = text,
                            ts = deps.clock(),
                            segmentId = entity.segmentId,
                            recoveryKey = recoveryKey(entity),
                            startOffsetMs = entity.startOffsetMs,
                            endOffsetMs = resolvedEndOffset(entity),
                        )

                        // transcript + pending 行消费必须由同一个 Room transaction 完成；
                        // 事务失败时两者一起回滚，避免 retry 重复落库。
                        val sinkOk = try {
                            queue.recordTranscript(entity, transcript)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            false
                        }
                        if (!sinkOk) return ListenableWorker.Result.retry()

                        // transaction commit 后才 best-effort 删除 WAV；文件删除失败不能
                        // 让已提交的 transcript 再次进入转写。
                        try {
                            deps.deleteAudioFile(entity)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            // 文件残留交给后续清理，不回滚/重试已提交的数据库结果。
                        }
                        processed++
                    }
                }
            }

            // 本批次出现过不可恢复的安全存储错误（MISSING/CORRUPT）：全部行已标记终态
            // FAILED，批次整体必须以 STORAGE 终态 failure 返回，而不是 success()（Task 16）。
            if (firstStorageErrorCode != null) {
                return failure(ERROR_KIND_STORAGE, firstStorageErrorCode)
            }
        }

        if (queue.pendingSegments().isNotEmpty()) {
            // 预算耗尽：APPEND 一个无 backoff 的 continuation，确保 backlog 不会悬空。
            deps.scheduleContinuation()
        }
        return ListenableWorker.Result.success()
    }

    private fun recoveryKey(entity: PendingAudioEntity): String =
        "pending-audio:${entity.id}"

    /** 兼容未经过 v5 migration 的测试/边界实体，仍保证 end > start。 */
    private fun resolvedEndOffset(entity: PendingAudioEntity): Long =
        entity.endOffsetMs.takeIf { it > entity.startOffsetMs }
            ?: (entity.startOffsetMs + entity.durationMs).coerceAtLeast(entity.startOffsetMs)

    private fun failure(kind: String, code: String?): ListenableWorker.Result =
        ListenableWorker.Result.failure(
            Data.Builder()
                .putString(KEY_ERROR_KIND, kind)
                .apply { if (code != null) putString(KEY_ERROR_CODE, code) }
                .build(),
        )

    private fun AsrError.errorCode(): String? =
        if (message.isNotBlank()) "asr" else null

    private fun Throwable.normalizedAsrError(): AsrError =
        when (this) {
            is AsrException -> error
            is IOException -> AsrError(
                kind = AsrError.Kind.NETWORK,
                retriable = true,
                message = "network error",
            )
            else -> AsrError(
                kind = AsrError.Kind.UNKNOWN,
                retriable = false,
                message = "unknown error",
            )
        }

    private fun AsrError.Kind.safeCode(): String = when (this) {
        AsrError.Kind.AUTH -> "AUTH"
        AsrError.Kind.RATE_LIMIT -> "RATE_LIMIT"
        AsrError.Kind.NETWORK -> "NETWORK"
        AsrError.Kind.SERVER -> "SERVER"
        AsrError.Kind.EMPTY -> "EMPTY"
        AsrError.Kind.CONFIG -> "CONFIG"
        AsrError.Kind.UNKNOWN -> "UNKNOWN"
    }

    companion object {
        const val UNIQUE_WORK_NAME = "pending-transcription"
        const val BACKOFF_DELAY_MILLIS = 30_000L
        const val MAX_BACKOFF_DELAY_MILLIS = 1_800_000L

        const val KEY_ERROR_KIND = "errorKind"
        const val KEY_ERROR_CODE = "errorCode"

        const val ERROR_KIND_STORAGE = "STORAGE"
        const val ERROR_CODE_MISSING = "MISSING"
        const val ERROR_CODE_CORRUPT = "CORRUPT"
        const val ERROR_KIND_CONFIG = "CONFIG"
        const val ERROR_CODE_UNCONFIGURED = "UNCONFIGURED"

        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_BATCH_LIMIT = 10
        const val DEFAULT_EXECUTION_BUDGET = 50

        /** 构建入队请求：CONNECTED 约束 + 瞬态失败使用指数退避 + 有界延迟。 */
        fun buildRequest(): OneTimeWorkRequest =
            OneTimeWorkRequest.Builder(PendingTranscriptionWorker::class.java)
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

        /** backlog continuation：不设置 retry backoff，正常续跑不是瞬态失败。 */
        private fun buildContinuationRequest(): OneTimeWorkRequest =
            OneTimeWorkRequest.Builder(PendingTranscriptionWorker::class.java)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

        /** 唯一 work 名 + KEEP：避免同时重复跑同一队列。 */
        fun enqueueUnique(workManager: WorkManager) {
            workManager.enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                buildRequest(),
            )
        }

        /** 当前 Worker 结束前追加下一段 work，避免 KEEP 把 continuation 丢掉。 */
        fun enqueueContinuation(workManager: WorkManager) {
            workManager.enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND,
                buildContinuationRequest(),
            )
        }
    }
}

/** 可注入依赖集合（默认实现不含任何全局课堂/凭证状态）。 */
interface PendingTranscriptionDependencies {
    val queue: PendingAudioQueue
    val store: PendingAudioStore
    val transcriber: PendingSegmentTranscriber?
    val maxAttempts: Int
    val batchLimit: Int
    val clock: () -> Long

    /** 单次 Worker 最多处理的段数；耗尽后安排 continuation。 */
    val executionBudget: Int
        get() = PendingTranscriptionWorker.DEFAULT_EXECUTION_BUDGET

    /** transaction 成功后的 WAV best-effort 删除 seam；失败不得重做 transcript。 */
    val deleteAudioFile: (PendingAudioEntity) -> Boolean
        get() = { entity -> store.deleteFile(entity) }

    /** 正常 backlog continuation；默认 seam 无动作，生产实现接 WorkManager APPEND。 */
    val scheduleContinuation: () -> Unit
        get() = {}
}

/**
 * [PendingTranscriptionWorker] 的可注入 seam（纯 Kotlin，无 Hilt）。
 *
 * 一个 [PendingAudioQueue] 同时提供 Worker 所需的一切：按 createdTs ASC
 * （同值再 id ASC）列出待转写段、记录转写结果、标记终态。实现完全由
 * 测试通过 fake 注入；默认运行时实现不做任何全局课堂/凭证状态依赖。
 */
interface PendingAudioQueue {
    /** 按 createdTs ASC、同值再 id ASC 返回所有 PENDING 段。 */
    suspend fun pendingSegments(): List<PendingAudioEntity>

    /** 在同一事务内持久化 recovery transcript 并消费 pending 行。 */
    suspend fun recordTranscript(entity: PendingAudioEntity, chunk: TranscriptChunkEntity): Boolean

    /** 把一段标记为终态 FAILED（携带固定安全 error kind/code，不含路径/原文）。 */
    suspend fun markFailed(entity: PendingAudioEntity, errorKind: String, errorCode: String?)

    /** 转写失败后递增尝试次数（保持 PENDING 以便重试）。 */
    suspend fun recordAttemptFailure(entity: PendingAudioEntity, errorKind: String, errorCode: String?)
}

/** 单段转写 seam：与 [com.classsentinel.core.speech.SegmentSpeechRouter] 的解耦边界。 */
interface PendingSegmentTranscriber {
    suspend fun transcribe(segment: WavSegment): Result<PendingSegmentResult>
}

data class PendingSegmentResult(
    val segmentId: String,
    val text: String,
)

/**
 * 默认运行时：从应用 Room 数据库构造真实队列，供 WorkManager 反射创建的
 * Worker 实例消费 PENDING 行（不再走 EmptyPendingAudioQueue + temp store）。
 *
 * 默认 [transcriber] 是按执行时配置构造的真实单段 Router 适配器，不依赖前台进程先注册
 * factory；因此 WorkManager 在应用进程重启后仍能消费 pending。测试可用
 * [installTranscriberFactory] 覆盖它。不存 API key/课堂文本/音频到 WorkManager Data。
 */
object PendingTranscriptionRuntime {

    /** 仅供测试覆盖的进程内 transcriber 工厂；null 表示恢复真实默认实现。 */
    @Volatile
    private var transcriberOverride: ((Context) -> PendingSegmentTranscriber?)? = null

    /** 真实默认依赖：同一个 [PendingAudioDao]，Room 队列 + noBackupFilesDir store。 */
    fun dependencies(context: Context): PendingTranscriptionDependencies {
        val appContext = context.applicationContext
        val db = AppDatabase.get(appContext)
        val pendingDao = db.pendingAudioDao()
        return DefaultDependencies(
            queue = RoomPendingAudioQueue(pendingDao, db.transcriptDao(), db),
            store = PendingAudioStore(
                rootDir = File(appContext.noBackupFilesDir, "pending-audio"),
                dao = pendingDao,
            ),
            transcriber = transcriberOverride?.invoke(appContext)
                ?: RuntimePendingSegmentTranscriber(appContext),
            scheduleContinuation = {
                PendingTranscriptionWorker.enqueueContinuation(WorkManager.getInstance(appContext))
            },
        )
    }

    /**
     * 测试覆盖 seam；传 null 恢复按 DataStore 动态构造的真实默认实现。
     */
    @VisibleForTesting
    fun installTranscriberFactory(factory: ((Context) -> PendingSegmentTranscriber?)?) {
        transcriberOverride = factory
    }
}

/**
 * WorkManager 默认 transcriber：首次消费时读取最新 ASR 配置并构造 Router，后续 pending 段
 * 复用本次 Worker 的 Router（不重复做 VAD）。配置/网络异常转为安全 typed failure，取消原样传播。
 */
private class RuntimePendingSegmentTranscriber(
    private val context: Context,
) : PendingSegmentTranscriber {
    private var router: SegmentSpeechRouter? = null

    override suspend fun transcribe(segment: WavSegment): Result<PendingSegmentResult> {
        return try {
            val activeRouter = router ?: ProductionAsrFactory.createRouter(context).also { router = it }
            val result = activeRouter.transcribeSegment(segment)
            if (result.isSuccess) {
                val routed = result.getOrThrow()
                Result.success(PendingSegmentResult(segment.id, routed.text))
            } else {
                Result.failure(
                    result.exceptionOrNull()
                        ?: AsrException(AsrError(AsrError.Kind.UNKNOWN, retriable = false)),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AsrException) {
            Result.failure(e)
        } catch (_: IOException) {
            Result.failure(AsrException(AsrError.network("ASR runtime unavailable")))
        } catch (_: Throwable) {
            Result.failure(AsrException(AsrError(AsrError.Kind.UNKNOWN, retriable = false)))
        }
    }
}

/** 默认依赖容器；Worker 运行时的真实 transcriber 由 [PendingTranscriptionRuntime] 注入。 */
class DefaultDependencies(
    override val queue: PendingAudioQueue,
    override val store: PendingAudioStore,
    override val transcriber: PendingSegmentTranscriber? = null,
    override val maxAttempts: Int = PendingTranscriptionWorker.DEFAULT_MAX_ATTEMPTS,
    override val batchLimit: Int = PendingTranscriptionWorker.DEFAULT_BATCH_LIMIT,
    override val clock: () -> Long = System::currentTimeMillis,
    override val executionBudget: Int = PendingTranscriptionWorker.DEFAULT_EXECUTION_BUDGET,
    override val scheduleContinuation: () -> Unit = {},
) : PendingTranscriptionDependencies

/**
 * 真实 Room 实现的 [PendingAudioQueue]：状态写回同一个 [PendingAudioDao]
 * （updateState 语义），转写块经 [TranscriptDao] 持久化。
 *
 * 安全契约（Task 16）：lastError 只写调用方传入的安全 error kind/code，
 * 绝不保存异常 message / 文件路径 / 音频 / 转写文本。
 */
class RoomPendingAudioQueue(
    private val pendingDao: PendingAudioDao,
    private val transcriptDao: TranscriptDao,
    private val database: AppDatabase? = null,
) : PendingAudioQueue {

    /** 按 createdTs ASC、同值再 id ASC 返回所有 PENDING 段。 */
    override suspend fun pendingSegments(): List<PendingAudioEntity> =
        pendingDao.getByState("PENDING").sortedWith(compareBy<PendingAudioEntity> { it.createdTs }.thenBy { it.id })

    /**
     * 转写成功：在一个 Room transaction 内幂等写 transcript 并消费 pending 行。
     * recoveryKey 已存在时只补偿 pending 删除，不再次 insert；任一步失败由 Room 回滚。
     */
    override suspend fun recordTranscript(
        entity: PendingAudioEntity,
        chunk: TranscriptChunkEntity,
    ): Boolean {
        val db = requireNotNull(database) { "ROOM_DATABASE_REQUIRED_FOR_ATOMIC_RECOVERY" }
        db.withTransaction {
            val key = requireNotNull(chunk.recoveryKey) { "RECOVERY_KEY_REQUIRED" }
            val existing = transcriptDao.findRecoveryId(chunk.courseId, key)
            if (existing == null) {
                val nextSeq = transcriptDao.maxSeq(chunk.courseId) + 1
                transcriptDao.insert(chunk.copy(seq = nextSeq))
                if (pendingDao.deleteById(entity.id) == 0) {
                    error("PENDING_CONSUME_FAILED")
                }
            } else {
                // 上一次 transaction 已提交 transcript；这里只需完成可能遗漏的行消费。
                pendingDao.deleteById(entity.id)
            }
        }
        return true
    }

    /** 终态 FAILED：attempts + 1，lastError 只写安全 errorCode（或 kind）。 */
    override suspend fun markFailed(entity: PendingAudioEntity, errorKind: String, errorCode: String?) {
        pendingDao.updateState(entity.id, "FAILED", entity.attempts + 1, safeLastError(errorKind, errorCode))
    }

    /** 可重试失败：保持 PENDING、attempts + 1，lastError 只写安全 errorCode（或 kind）。 */
    override suspend fun recordAttemptFailure(entity: PendingAudioEntity, errorKind: String, errorCode: String?) {
        pendingDao.updateState(entity.id, "PENDING", entity.attempts + 1, safeLastError(errorKind, errorCode))
    }

    private fun safeLastError(errorKind: String, errorCode: String?): String? = errorCode ?: errorKind
}

/**
 * v0.2 Task 16：失败音频段的持久化 + Worker 调度 handler。
 *
 * 把 [com.classsentinel.core.speech.SegmentSpeechRouter.onSegmentFailed] 的失败段
 * 交给 [PendingAudioStore.save] 登记到 PENDING 队列，并在保存成功返回后触发一次
 * [PendingTranscriptionWorker.enqueueUnique]（经注入的 schedule 回调）。
 *
 * 安全契约：
 * - lastError 只写 [AsrError.Kind.name]（如 `NETWORK`），绝不含异常 message /
 *   URL / 文件路径 / 音频 / 课堂文本；
 * - 只有 [PendingAudioStore.save] 成功返回后才调用 schedule()；save/DAO 失败
 *   原样上抛且不调度（不把失败吞成成功；store.save 自身负责清理本次新建文件，
 *   不产生孤儿文件）；
 * - 不重复保存同一段，不自动记录完整课程（只登记本次失败段）。
 */
class PendingAudioRecovery(
    private val courseId: Long,
    private val store: PendingAudioStore,
    private val schedule: () -> Unit,
) {
    /** 持久化失败段；成功返回带 DAO 行 id 的实体，之后恰好调度一次 Worker。 */
    suspend fun persist(segment: WavSegment, error: AsrException): PendingAudioEntity {
        val saved = store.save(
            courseId = courseId,
            segment = segment,
            lastError = error.error.kind.name,
        )
        schedule()
        return saved
    }
}
