package com.classsentinel.worker

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.testing.TestListenableWorkerBuilder
import com.classsentinel.core.audio.PendingAudioStore
import com.classsentinel.core.audio.WavSegment
import com.classsentinel.core.speech.AsrError
import com.classsentinel.core.speech.AsrException
import com.classsentinel.data.AppDatabase
import com.classsentinel.data.InMemorySecretStore
import com.classsentinel.data.PendingAudioDao
import com.classsentinel.data.SettingsRepository
import com.classsentinel.data.SettingsRepositoryHolder
import com.classsentinel.data.TranscriptDao
import com.classsentinel.data.entities.PendingAudioEntity
import com.classsentinel.data.entities.TranscriptChunkEntity
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v0.2 Task 16：PendingTranscriptionWorker 的严格 RED→GREEN 测试。
 *
 * 依赖全部通过 fake 注入（queue/sink/transcriber/store），不接触任何
 * 全局课堂/凭证状态；音频字节全部为短合成 WAV；任何断言不输出原始内容。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingTranscriptionWorkerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dao: FakeDao
    private lateinit var store: PendingAudioStore
    private lateinit var queue: FakeQueue
    private lateinit var transcriber: FakeTranscriber
    private var now: Long = 1_700_000_000_000L
    private lateinit var root: File
    /** PendingAudioRecovery RED 测试：记录 schedule 回调次数。 */
    private var scheduleCount = 0

    @Before
    fun setUp() {
        root = tmp.newFolder("pending-audio")
        dao = FakeDao()
        store = PendingAudioStore(rootDir = root, dao = dao, clock = { now })
        queue = FakeQueue()
        transcriber = FakeTranscriber()
        scheduleCount = 0
        SettingsRepositoryHolder.installForTests(
            SettingsRepository.createForTests(RuntimeEnvironment.getApplication(), InMemorySecretStore()),
        )
    }

    @After
    fun tearDown() {
        SettingsRepositoryHolder.installForTests(null)
    }

    // ---- 工具 ----

    /** 记录型 fake DAO（复用 PendingAudioStoreTest 语义：insert 自增 id）。 */
    private class FakeDao : PendingAudioDao {
        val inserted = mutableListOf<PendingAudioEntity>()
        val deleted = mutableListOf<PendingAudioEntity>()
        var failDelete = false
        /** 可注入 insert 失败（PendingAudioRecovery RED 测试：save 失败不得误报调度）。 */
        var failInsertCount = 0

        override suspend fun insert(segment: PendingAudioEntity): Long {
            if (failInsertCount > 0) {
                failInsertCount--
                throw IOException("db insert failed (synthetic)")
            }
            // Room @Insert 语义：显式主键 > 0 时保留（fake dao.insert(entity) 直接调用）；
            // id == 0 时分配 1-based 自增 id（PendingAudioStore.save 路径）。
            val id = if (segment.id > 0) segment.id else inserted.size.toLong() + 1
            inserted += segment.copy(id = id)
            return id
        }

        override fun observeForCourse(courseId: Long): Flow<List<PendingAudioEntity>> = emptyFlow()
        override suspend fun getByState(state: String): List<PendingAudioEntity> =
            inserted.filter { it.state == state }
        override suspend fun updateState(id: Long, state: String, attempts: Int, lastError: String?) {
            val i = inserted.indexOfFirst { it.id == id }
            if (i >= 0) inserted[i] = inserted[i].copy(state = state, attempts = attempts, lastError = lastError)
        }
        override suspend fun delete(segment: PendingAudioEntity) {
            if (failDelete) throw IOException("db delete failed (synthetic)")
            deleted += segment
            inserted.removeAll { it.id == segment.id }
        }
        override suspend fun deleteForCourse(courseId: Long) {
            inserted.removeAll { it.courseId == courseId }
        }

        override suspend fun getAll(): List<PendingAudioEntity> = inserted.toList()

        override suspend fun clearAll() {
            inserted.clear()
        }
    }

    /** 记录型 fake queue：记录 transcript 写入、删除、状态变化。 */
    private class FakeQueue : PendingAudioQueue {
        val pending = mutableListOf<PendingAudioEntity>()
        val transcripts = mutableListOf<TranscriptChunkEntity>()
        val failed = mutableListOf<Pair<Long, String>>()
        val attemptFailures = mutableListOf<Pair<Long, String>>()
        val deleted = mutableListOf<Long>()
        var failTranscriptCount = 0
        var failDelete = false

        override suspend fun pendingSegments(): List<PendingAudioEntity> =
            pending.sortedWith(compareBy<PendingAudioEntity> { it.createdTs }.thenBy { it.id })

        override suspend fun recordTranscript(chunk: TranscriptChunkEntity): Boolean {
            if (failTranscriptCount > 0) {
                failTranscriptCount--
                return false
            }
            transcripts += chunk
            return true
        }

        override suspend fun markFailed(entity: PendingAudioEntity, errorKind: String, errorCode: String?) {
            failed += entity.id to errorKind
            val i = pending.indexOfFirst { it.id == entity.id }
            if (i >= 0) {
                pending[i] = pending[i].copy(
                    state = "FAILED",
                    attempts = pending[i].attempts + 1,
                    lastError = errorCode ?: errorKind,
                )
            }
        }

        override suspend fun recordAttemptFailure(entity: PendingAudioEntity, errorKind: String, errorCode: String?) {
            attemptFailures += entity.id to errorKind
            // 契约：保持 PENDING、attempts + 1；lastError 只写安全 code，绝不保存异常 message。
            val i = pending.indexOfFirst { it.id == entity.id }
            if (i >= 0) {
                pending[i] = pending[i].copy(
                    state = "PENDING",
                    attempts = pending[i].attempts + 1,
                    lastError = errorCode ?: errorKind,
                )
            }
        }
    }

    /** 最小 TranscriptDao fake：仅当真正实现 RoomPendingAudioQueue 时构造需要；本测试只用于编译。 */
    private class FakeTranscriptDao : TranscriptDao {
        override suspend fun insert(chunk: TranscriptChunkEntity): Long = 1L
        override suspend fun getForCourse(courseId: Long): List<TranscriptChunkEntity> = emptyList()
        override fun observeForCourse(courseId: Long): Flow<List<TranscriptChunkEntity>> = emptyFlow()
        override fun observeMarkedForCourse(courseId: Long): Flow<List<TranscriptChunkEntity>> = emptyFlow()
        override suspend fun mark(chunkId: Long, courseId: Long): Int = 0
        override suspend fun unmark(chunkId: Long, courseId: Long): Int = 0
        override suspend fun maxSeq(courseId: Long): Int = 0
        override suspend fun countAll(): Int = 0
        override suspend fun deleteForCourse(courseId: Long): Int = 0
        override suspend fun clearAll() = Unit
    }

    /** 记录型 fake transcriber：可注入成功文本 / AsrException / 普通 IOException。 */
    private class FakeTranscriber : PendingSegmentTranscriber {
        var textBySegment = mutableMapOf<String, String>()
        var errorBySegment = mutableMapOf<String, AsrError>()
        var ioErrorBySegment = mutableMapOf<String, Boolean>()
        var uncaughtBySegment = mutableMapOf<String, Boolean>()
        val calls = mutableListOf<String>()

        override suspend fun transcribe(segment: WavSegment): Result<PendingSegmentResult> {
            calls += segment.id
            if (uncaughtBySegment[segment.id] == true) throw IllegalStateException("synthetic boom")
            if (ioErrorBySegment[segment.id] == true) throw IOException("synthetic io")
            errorBySegment[segment.id]?.let { return Result.failure(AsrException(it)) }
            val text = textBySegment[segment.id] ?: ""
            return Result.success(PendingSegmentResult(segment.id, text))
        }
    }

    private fun wavBytes(sampleCount: Int = 160, seed: Int = 7): ByteArray {
        val dataSize = sampleCount * 2
        val b = ByteArray(44 + dataSize)
        ascii(b, 0, "RIFF")
        intLE(b, 4, 36 + dataSize)
        ascii(b, 8, "WAVE")
        ascii(b, 12, "fmt ")
        intLE(b, 16, 16)
        shortLE(b, 20, 1)
        shortLE(b, 22, 1)
        intLE(b, 24, 16000)
        intLE(b, 28, 32000)
        shortLE(b, 32, 2)
        shortLE(b, 34, 16)
        ascii(b, 36, "data")
        intLE(b, 40, dataSize)
        for (i in 0 until dataSize) b[44 + i] = ((i * 31 + seed) % 256).toByte()
        return b
    }

    private fun ascii(b: ByteArray, offset: Int, s: String) {
        for (i in s.indices) b[offset + i] = s[i].code.toByte()
    }

    private fun intLE(b: ByteArray, offset: Int, value: Int) {
        b[offset] = value.toByte()
        b[offset + 1] = (value shr 8).toByte()
        b[offset + 2] = (value shr 16).toByte()
        b[offset + 3] = (value shr 24).toByte()
    }

    private fun shortLE(b: ByteArray, offset: Int, value: Int) {
        b[offset] = value.toByte()
        b[offset + 1] = (value shr 8).toByte()
    }

    private suspend fun pendingRow(
        id: Long,
        segmentId: String,
        createdTs: Long,
        state: String = "PENDING",
        attempts: Int = 0,
    ): PendingAudioEntity {
        val entity = PendingAudioEntity(
            id = id,
            courseId = 1L,
            segmentId = segmentId,
            filePath = File(root, "c1-s$segmentId.wav").absolutePath,
            durationMs = 1000L,
            state = state,
            attempts = attempts,
            createdTs = createdTs,
        )
        queue.pending += entity
        return entity
    }

    private suspend fun saveReal(segmentId: String, createdTs: Long): PendingAudioEntity {
        val seg = WavSegment(segmentId, 0L, 1000L, wavBytes())
        return store.save(1L, seg).also { queue.pending += it }
    }

    private fun deps(
        queue: PendingAudioQueue = this.queue,
        store: PendingAudioStore = this.store,
        transcriber: PendingSegmentTranscriber? = this.transcriber,
        maxAttempts: Int = 3,
        batchLimit: Int = 10,
        clock: () -> Long = { now },
    ): PendingTranscriptionDependencies = object : PendingTranscriptionDependencies {
        override val queue = queue
        override val store = store
        override val transcriber = transcriber
        override val maxAttempts = maxAttempts
        override val batchLimit = batchLimit
        override val clock = clock
    }

    private fun worker(deps: PendingTranscriptionDependencies): PendingTranscriptionWorker {
        val context = RuntimeEnvironment.getApplication() as Context
        return TestListenableWorkerBuilder<PendingTranscriptionWorker>(context)
            .build()
            .also { it.dependencies = deps }
    }

    private suspend fun runWorker(deps: PendingTranscriptionDependencies): ListenableWorker.Result {
        val context = RuntimeEnvironment.getApplication() as Context
        val w = TestListenableWorkerBuilder.from(
            context,
            PendingTranscriptionWorker::class.java,
        ).build()
        w.dependencies = deps
        return w.doWork()
    }

    /**
     * 终态失败断言：必须是 [ListenableWorker.Result.Failure]，且输出 Data 携带
     * 安全 kind（可选 code）。不把带 Data 的 failure 与空 Data 的 failure() 做对象相等比较。
     */
    private fun assertTerminalFailure(result: ListenableWorker.Result, kind: String, code: String? = null) {
        assertTrue("expected Failure but was $result", result is ListenableWorker.Result.Failure)
        val output = (result as ListenableWorker.Result.Failure).outputData
        assertEquals(kind, output.getString(PendingTranscriptionWorker.KEY_ERROR_KIND))
        if (code != null) {
            assertEquals(code, output.getString(PendingTranscriptionWorker.KEY_ERROR_CODE))
        }
    }

    // ---- 测试 ----

    @Test
    fun `request has CONNECTED constraint and EXPONENTIAL bounded backoff`() {
        val request = PendingTranscriptionWorker.buildRequest()
        val spec = request.workSpec
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(androidx.work.BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertTrue(spec.backoffDelayDuration >= PendingTranscriptionWorker.BACKOFF_DELAY_MILLIS)
        assertTrue(
            "backoff must be bounded",
            spec.backoffDelayDuration <= PendingTranscriptionWorker.MAX_BACKOFF_DELAY_MILLIS,
        )
    }

    @Test
    fun `processes pending in createdTs order with sink-then-delete and no re-transcribe`() = runBlocking {
        val e1 = saveReal("a", createdTs = 100L)
        val e2 = saveReal("b", createdTs = 200L)
        transcriber.textBySegment["a"] = "alpha"
        transcriber.textBySegment["b"] = "beta"

        val result = runWorker(deps())

        assertEquals(ListenableWorker.Result.success(), result)
        // 严格按 createdTs ASC 顺序转写，前段不会重复
        assertEquals(listOf("a", "b"), transcriber.calls)
        // sink 先于删除：两条都先写入 transcript，再各删除一次
        assertEquals(listOf("a", "b"), queue.transcripts.map { it.segmentId })
        assertEquals(listOf(e1.id, e2.id), dao.deleted.map { it.id })
        assertEquals(listOf("alpha", "beta"), queue.transcripts.map { it.text })
    }

    @Test
    fun `network failure first attempt retries keeping PENDING and incrementing attempts`() = runBlocking {
        val e1 = saveReal("a", createdTs = 100L)
        transcriber.errorBySegment["a"] = AsrError(AsrError.Kind.NETWORK, retriable = true, message = "net down")

        val result = runWorker(deps(maxAttempts = 3))

        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(listOf(e1.id to "NETWORK"), queue.attemptFailures)
        // 仍 PENDING，attempts 递增：Worker 依赖的是 Queue seam，从 queue.pending 断言
        val row = queue.pending.first { it.id == e1.id }
        assertEquals("PENDING", row.state)
        assertEquals(1, row.attempts)
        // 未删除、未写 transcript；attempts 递增由 recordAttemptFailure 交给 sink 实现
        assertTrue(queue.deleted.isEmpty())
        assertTrue(queue.transcripts.isEmpty())
    }

    @Test
    fun `retriable failure after max attempts is terminal FAILED`() = runBlocking {
        val e1 = saveReal("a", createdTs = 100L)
        transcriber.errorBySegment["a"] = AsrError(AsrError.Kind.SERVER, retriable = true, message = "boom")

        val result = runWorker(deps(maxAttempts = 1))

        // 带安全 Data 的 Failure：不做与空 Data failure() 的对象相等比较。
        assertTerminalFailure(result, kind = "SERVER", code = "asr")
        assertEquals(listOf(e1.id to "SERVER"), queue.failed)
        assertTrue(queue.deleted.isEmpty())
        assertTrue(queue.transcripts.isEmpty())
    }

    @Test
    fun `AUTH failure is terminal without retry and stops the batch`() = runBlocking {
        val eAuth = saveReal("auth", createdTs = 100L)
        val eCfg = saveReal("cfg", createdTs = 200L)
        transcriber.errorBySegment["auth"] = AsrError(AsrError.Kind.AUTH, retriable = false, message = "denied")
        transcriber.errorBySegment["cfg"] = AsrError(AsrError.Kind.CONFIG, retriable = false, message = "bad")

        val result = runWorker(deps(maxAttempts = 5))

        // 当前生产在首个 AUTH 错误即返回终态失败，cfg 未被处理（CONFIG 覆盖见独立测试）。
        assertTerminalFailure(result, kind = "AUTH", code = "asr")
        assertEquals(listOf(eAuth.id to "AUTH"), queue.failed)
        assertEquals(listOf("auth"), transcriber.calls)
    }

    @Test
    fun `CONFIG failure is terminal without retry`() = runBlocking {
        val eCfg = saveReal("cfg", createdTs = 100L)
        transcriber.errorBySegment["cfg"] = AsrError(AsrError.Kind.CONFIG, retriable = false, message = "bad")

        val result = runWorker(deps(maxAttempts = 5))

        assertTerminalFailure(result, kind = "CONFIG", code = "asr")
        assertEquals(listOf(eCfg.id to "CONFIG"), queue.failed)
    }

    @Test
    fun `Missing and Corrupt loads are terminal FAILED with safe storage codes`() = runBlocking {
        val missing = pendingRow(id = 11L, segmentId = "gone", createdTs = 100L) // 文件不存在
        val corrupt = pendingRow(id = 12L, segmentId = "bad", createdTs = 200L)
        saveCorruptFile("bad")

        val result = runWorker(deps())

        // 契约要求 Missing/Corrupt 返回带 STORAGE kind 的终态 Failure；当前生产只
        // markFailed 后继续（最终 success()），本断言如实保留期望并暴露该缺口（后续切片修）。
        assertTerminalFailure(result, kind = "STORAGE")
        assertEquals(listOf(missing.id to "STORAGE", corrupt.id to "STORAGE"), queue.failed)
        assertTrue(transcriber.calls.isEmpty())
        assertTrue(queue.deleted.isEmpty())
        assertTrue(queue.transcripts.isEmpty())
    }

    @Test
    fun `output Data contains no message path audio or text`() = runBlocking {
        val e1 = saveReal("a", createdTs = 100L)
        transcriber.errorBySegment["a"] = AsrError(AsrError.Kind.SERVER, retriable = true, message = "server exploded")

        val result = runWorker(deps(maxAttempts = 1))

        val output = result.toString()
        assertFalse("must not contain raw error message", output.contains("server exploded"))
        assertFalse("must not contain file path", output.contains("c1-sa.wav"))
        assertFalse("must not contain transcript text", output.contains("alpha"))
    }

    @Test
    fun `IOException maps to NETWORK and retries`() = runBlocking {
        val e1 = saveReal("a", createdTs = 100L)
        transcriber.ioErrorBySegment["a"] = true

        val result = runWorker(deps(maxAttempts = 3))

        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(listOf(e1.id to "NETWORK"), queue.attemptFailures)
    }

    @Test
    fun `sink failure leaves PENDING and does not delete`() = runBlocking {
        val e1 = saveReal("a", createdTs = 100L)
        transcriber.textBySegment["a"] = "alpha"
        queue.failTranscriptCount = 1

        val result = runWorker(deps())

        assertEquals(ListenableWorker.Result.retry(), result)
        assertTrue(queue.deleted.isEmpty())
        assertTrue(queue.transcripts.isEmpty())
        val row = dao.inserted.first { it.id == e1.id }
        assertEquals("PENDING", row.state)
    }

    @Test
    fun `delete failure keeps pending and retries`() = runBlocking {
        val e1 = saveReal("a", createdTs = 100L)
        transcriber.textBySegment["a"] = "alpha"
        dao.failDelete = true

        val result = runWorker(deps())

        assertEquals(ListenableWorker.Result.retry(), result)
        // 数据保留契约：DAO 删除失败时音频文件必须仍存在。若文件先被删而 DB 行保留 PENDING，
        // 下一次 Worker 重试只能 load 成 Missing → 终态 STORAGE 失败，音频丢失（Task 16 数据丢失缺陷）。
        // 当前生产 store.delete 顺序是 file.delete() → dao.delete(entity)，违反该契约 → 本用例 RED。
        val file = File(e1.filePath)
        assertTrue("audio file must survive a failed DAO delete (data retention contract)", file.exists())
        // DB 行仍保留（PENDING、attempts 未递增）
        val row = dao.inserted.first { it.id == e1.id }
        assertEquals("PENDING", row.state)
        assertEquals(0, row.attempts)
        assertEquals(listOf("alpha"), queue.transcripts.map { it.text })
    }

    @Test
    fun `uncaught exception maps to UNKNOWN terminal failure`() = runBlocking {
        val e1 = saveReal("a", createdTs = 100L)
        transcriber.uncaughtBySegment["a"] = true

        val result = runWorker(deps(maxAttempts = 3))

        assertTerminalFailure(result, kind = "UNKNOWN", code = "asr")
        assertEquals(listOf(e1.id to "UNKNOWN"), queue.failed)
    }

    private fun saveCorruptFile(segmentId: String) {
        val file = File(root, "c1-s$segmentId.wav")
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
    }

    // ---- 真实 Room Queue adapter 状态写回契约（RED：RoomPendingAudioQueue 尚不存在） ---- //

    /**
     * RED 契约（Task 16 后续切片）：真实 Room 实现的 [PendingAudioQueue] 必须把
     * 状态写回同一个 [PendingAudioDao]（updateState 语义），而不是像当前生产那样
     * 只有接口 + EmptyPendingAudioQueue。
     *
     * [RoomPendingAudioQueue] 生产类尚不存在 → 本测试编译失败（RED）。
     * 构造签名与默认实现契约：RoomPendingAudioQueue(pendingDao, transcriptDao)。
     */
    private fun roomQueue(): RoomPendingAudioQueue =
        RoomPendingAudioQueue(dao, FakeTranscriptDao())

    @Test
    fun `RoomPendingAudioQueue recordAttemptFailure writes PENDING attempts 1 and only safe code`() = runBlocking {
        val entity = PendingAudioEntity(
            id = 7L,
            courseId = 1L,
            segmentId = "seg7",
            filePath = File(root, "c1-sseg7.wav").absolutePath,
            durationMs = 1000L,
            state = "PENDING",
            attempts = 0,
            createdTs = 100L,
        )
        dao.insert(entity) // 真实实现按 id 更新 DB 行，fake 必须持有该行
        val queue = roomQueue()

        queue.recordAttemptFailure(entity, "NETWORK", "asr")

        val row = dao.inserted.first { it.id == 7L }
        assertEquals("retriable failure keeps PENDING", "PENDING", row.state)
        assertEquals("attempts incremented once", 1, row.attempts)
        assertEquals("lastError is the safe code, never the raw message", "asr", row.lastError)
    }

    @Test
    fun `RoomPendingAudioQueue markFailed writes FAILED attempts 2 and lastError is safe code only`() = runBlocking {
        val entity = PendingAudioEntity(
            id = 7L,
            courseId = 1L,
            segmentId = "seg7",
            filePath = File(root, "c1-sseg7.wav").absolutePath,
            durationMs = 1000L,
            state = "PENDING",
            attempts = 1,
            createdTs = 100L,
        )
        dao.insert(entity)
        val queue = roomQueue()

        queue.markFailed(entity, "STORAGE", "CORRUPT")

        val row = dao.inserted.first { it.id == 7L }
        assertEquals("terminal failure marks FAILED", "FAILED", row.state)
        assertEquals("attempts incremented to 2", 2, row.attempts)
        // 注意：不要对 row.toString() 断言不含文件路径 —— filePath 是 Task 14/16 必需的
        // 合法持久化元数据（本例正是 c1-sseg7.wav），toString 包含它不代表 raw error 泄露。
        // 真正要防的是 lastError 保存异常 message/path/audio/text：只断言安全错误字段。
        assertEquals("lastError is the safe code only", "CORRUPT", row.lastError)
        val safe = row.lastError.orEmpty()
        assertFalse("no raw error message may reach storage", safe.contains("CORRUPTED-BLOB-SEG7"))
        assertFalse("no error path fragment may reach storage", safe.contains("c1-sseg7.wav"))
    }

    // ---- 默认运行时 Room 依赖契约（RED：默认 transcriber 尚未接线） ---- //

    /**
     * RED 契约（Task 16 后续切片）：Worker 的默认运行时必须从应用 Room 数据库
     * 构造真实队列，而不是 [DefaultDependencies] 的 EmptyPendingAudioQueue + temp store。
     *
     * 期望生产签名：`PendingTranscriptionRuntime.dependencies(context: Context)` 返回
     * [PendingTranscriptionDependencies]，其中：
     * - queue 是 [RoomPendingAudioQueue]（消费 AppDatabase 的 pendingAudioDao/transcriptDao）；
     * - transcriber 必须是可在新进程中按配置构造的真实实现，而不是 null/no-op；
     * - maxAttempts/batchLimit 使用 [PendingTranscriptionWorker] 常量。
     *
     * 当前默认实现的 transcriber 仍为 null → 本断言 RED。
     */
    @Test
    fun `default runtime dependencies use Room queue and restart safe transcriber`() {
        val app = RuntimeEnvironment.getApplication()
        val deps = PendingTranscriptionRuntime.dependencies(app)

        assertTrue(
            "default runtime must consume real Room pending rows, not the empty queue",
            deps.queue is RoomPendingAudioQueue,
        )
        assertNotNull(
            "default runtime must provide a real restart-safe transcriber, not null/no-op",
            deps.transcriber,
        )
        assertEquals(
            "maxAttempts must follow the Worker default",
            PendingTranscriptionWorker.DEFAULT_MAX_ATTEMPTS,
            deps.maxAttempts,
        )
        assertEquals(
            "batchLimit must follow the Worker default",
            PendingTranscriptionWorker.DEFAULT_BATCH_LIMIT,
            deps.batchLimit,
        )
    }

    @Test
    fun `default worker consumes real room pending row and marks config failure`() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val db = AppDatabase.get(app)
        val root = File(app.noBackupFilesDir, "pending-audio").apply { mkdirs() }
        val file = File(root, "c991-sruntime.wav").apply {
            writeBytes(ByteArray(46).apply {
                "RIFF".forEachIndexed { index, char -> this[index] = char.code.toByte() }
                "WAVE".forEachIndexed { index, char -> this[8 + index] = char.code.toByte() }
            })
        }
        val insertedId = db.pendingAudioDao().insert(
            PendingAudioEntity(
                courseId = 991L,
                segmentId = "runtime",
                filePath = file.absolutePath,
                durationMs = 1_000L,
                createdTs = 1L,
            ),
        )
        try {
            SettingsRepositoryHolder.get(app).saveAsrSiliconKey("")
            PendingTranscriptionRuntime.installTranscriberFactory(null)

            val worker = TestListenableWorkerBuilder.from(
                app,
                PendingTranscriptionWorker::class.java,
            ).build()
            val result = worker.doWork()

            assertTerminalFailure(result, kind = "CONFIG", code = "asr")
            val row = db.pendingAudioDao().getAll().single { it.id == insertedId }
            assertEquals("FAILED", row.state)
            assertEquals(1, row.attempts)
            assertEquals("asr", row.lastError)
        } finally {
            db.pendingAudioDao().deleteForCourse(991L)
            file.delete()
            PendingTranscriptionRuntime.installTranscriberFactory(null)
        }
    }

    // ---- RED: 失败段持久化 + Worker 调度 handler（PendingAudioRecovery 尚不存在） ---- //

    /**
     * RED 契约（Task 16 后续切片）：把 [SegmentSpeechRouter.onSegmentFailed] 的失败段
     * 交给 [PendingAudioStore.save]，并在持久化成功后触发一次
     * [PendingTranscriptionWorker.enqueueUnique] 的 [PendingAudioRecovery] 生产类尚不存在
     * → 本测试编译失败（RED）。
     *
     * 期望生产签名：
     * `class PendingAudioRecovery(courseId: Long, store: PendingAudioStore, schedule: () -> Unit)`，
     * 方法 `suspend fun persist(segment: WavSegment, error: AsrException): PendingAudioEntity`。
     * 安全契约：lastError 只写安全 kind（例如 `NETWORK`），绝不含异常 message；
     * schedule 只在 save 成功之后调用一次；save/DAO 失败时不得误报调度。
     */
    @Test
    fun `PendingAudioRecovery persists failed segment and schedules worker once after successful save`() = runBlocking {
        val recovery = PendingAudioRecovery(
            courseId = 1L,
            store = store,
            schedule = { scheduleCount++ },
        )
        val seg = WavSegment(
            id = "r1",
            startOffsetMs = 0L,
            endOffsetMs = 1000L,
            bytes = wavBytes(),
        )
        val error = AsrException(
            AsrError(
                kind = AsrError.Kind.NETWORK,
                retriable = true,
                message = "https://example.com/r1.wav 连接超时: 课堂原文敏感内容",
            ),
        )

        val saved = recovery.persist(seg, error)

        // 返回实体携带 DAO 行 id，且文件真实存在于私有 root
        assertTrue("saved entity must carry dao row id", saved.id > 0L)
        val file = File(saved.filePath)
        assertTrue("audio file must exist in root", file.isFile)
        assertEquals("file must live directly under root", root.canonicalFile, file.canonicalFile.parentFile)
        // DAO row：courseId / segmentId / state=PENDING / attempts=0 / 注入时钟
        val row = dao.inserted.first { it.id == saved.id }
        assertEquals(1L, row.courseId)
        assertEquals("r1", row.segmentId)
        assertEquals("PENDING", row.state)
        assertEquals(0, row.attempts)
        assertEquals(now, row.createdTs)
        // lastError 只写安全 kind，绝不含异常 message / URL 路径 / 课堂原文
        assertEquals("NETWORK", row.lastError)
        assertFalse("raw error message must never reach storage", row.lastError.orEmpty().contains("课堂原文敏感内容"))
        assertFalse("url/path fragment must never reach storage", row.lastError.orEmpty().contains("example.com"))
        // schedule 恰好一次，且发生在 save 成功之后
        assertEquals("schedule must be called exactly once", 1, scheduleCount)
        assertEquals("schedule must fire after the dao row exists", 1, dao.inserted.size)
    }

    @Test
    fun `PendingAudioRecovery does not schedule when save fails`() = runBlocking {
        val recovery = PendingAudioRecovery(
            courseId = 1L,
            store = store,
            schedule = { scheduleCount++ },
        )
        val seg = WavSegment(
            id = "r2",
            startOffsetMs = 0L,
            endOffsetMs = 1000L,
            bytes = wavBytes(),
        )
        val error = AsrException(AsrError(AsrError.Kind.NETWORK, retriable = true, message = "net down"))
        dao.failInsertCount = 1

        try {
            recovery.persist(seg, error)
            fail("store.save failure must propagate out of persist")
        } catch (e: IOException) {
            // expected: DB 故障不得被吞成成功
        }

        // 未产生文件孤儿（store.save 清理本次新建），未落任何 DB 行，不得误报调度
        assertTrue("no orphan audio file on failed save", root.listFiles()!!.isEmpty())
        assertTrue("no db row recorded on failed save", dao.inserted.isEmpty())
        assertEquals("schedule must NOT be called when save fails", 0, scheduleCount)
    }
}
