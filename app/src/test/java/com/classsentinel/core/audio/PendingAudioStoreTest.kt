package com.classsentinel.core.audio

import com.classsentinel.data.PendingAudioDao
import com.classsentinel.data.entities.PendingAudioEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * v0.2 Task 16：失败/未转写语音分段的私有文件存储边界测试。
 *
 * 只测 [PendingAudioStore] 的可观测契约，DAO 用记录型 fake 注入（真实 Room DAO
 * 已在 Task 14/15 验收）；文件操作全部落在 JUnit TemporaryFolder 私有根目录：
 * - save：先写临时文件再原子 rename 到最终文件，最终文件可读、临时文件不残留，
 *   DAO 收到完整 PENDING 元数据，segmentId 不能造成路径穿越；
 * - 重复 (courseId, segmentId) 只产生一个文件（确定性安全文件名）；
 * - delete：删除文件与 DB 行，幂等，不碰根目录以外/无关文件；
 * - load：缺失文件与损坏 WAV（RIFF/WAVE 头、最小长度）返回可区分的 typed failure；
 * - DB insert 失败：清理本次新建的文件，不产生孤儿，也不误删重复保存时已有的文件。
 *
 * 测试数据全部为短合成 WAV 字节（无课堂内容），任何断言都不打印原始音频。
 */
class PendingAudioStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var dao: FakeDao
    private var now: Long = 0L
    private lateinit var store: PendingAudioStore

    /** 记录型 fake DAO：捕获 insert 的实体；可注入 insert 失败。 */
    private class FakeDao : PendingAudioDao {
        val inserted = mutableListOf<PendingAudioEntity>()
        val deleted = mutableListOf<PendingAudioEntity>()
        var failInsertCount = 0

        override suspend fun insert(segment: PendingAudioEntity): Long {
            if (failInsertCount > 0) {
                failInsertCount--
                throw IOException("db down (synthetic)")
            }
            // 模拟真实 Room @Insert 语义：SQLite AUTOINCREMENT 的 row id 从 1 起分配，
            // 落库行携带该 id，再返回它（与真实 DAO 返回的 row id 一致）。
            val id = inserted.size.toLong() + 1
            inserted += segment.copy(id = id)
            return id
        }

        override fun observeForCourse(courseId: Long): Flow<List<PendingAudioEntity>> = emptyFlow()

        override suspend fun getByState(state: String): List<PendingAudioEntity> =
            inserted.filter { it.state == state }

        override suspend fun updateState(id: Long, state: String, attempts: Int, lastError: String?) {
            val index = inserted.indexOfFirst { it.id == id }
            if (index >= 0) {
                inserted[index] = inserted[index].copy(state = state, attempts = attempts, lastError = lastError)
            }
        }

        override suspend fun delete(segment: PendingAudioEntity) {
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

    @Before
    fun setUp() {
        root = tmp.newFolder("pending-audio")
        dao = FakeDao()
        now = 1_700_000_000_000L
        store = PendingAudioStore(rootDir = root, dao = dao, clock = { now })
    }

    /** 44-byte 标准 WAV 头 + 短 PCM16 数据（纯合成字节，无课堂内容）。 */
    private fun wavBytes(sampleCount: Int = 160, seed: Int = 7): ByteArray {
        val dataSize = sampleCount * 2
        val b = ByteArray(44 + dataSize)
        ascii(b, 0, "RIFF")
        intLE(b, 4, 36 + dataSize)
        ascii(b, 8, "WAVE")
        ascii(b, 12, "fmt ")
        intLE(b, 16, 16)
        shortLE(b, 20, 1)   // PCM
        shortLE(b, 22, 1)   // mono
        intLE(b, 24, 16000) // sample rate
        intLE(b, 28, 32000) // byte rate
        shortLE(b, 32, 2)   // block align
        shortLE(b, 34, 16)  // bits per sample
        ascii(b, 36, "data")
        intLE(b, 40, dataSize)
        for (i in 0 until dataSize) b[44 + i] = ((i * 31 + seed) % 256).toByte()
        return b
    }

    private fun segment(id: String, startMs: Long = 500L, endMs: Long = 1500L): WavSegment =
        WavSegment(id = id, startOffsetMs = startMs, endOffsetMs = endMs, bytes = wavBytes())

    private fun ascii(b: ByteArray, offset: Int, s: String) {
        for (i in s.indices) b[offset + i] = s[i].code.toByte()
    }

    private fun intLE(b: ByteArray, offset: Int, value: Int) {
        b[offset] = value.toByte()
        b[offset + 1] = (value ushr 8).toByte()
        b[offset + 2] = (value ushr 16).toByte()
        b[offset + 3] = (value ushr 24).toByte()
    }

    private fun shortLE(b: ByteArray, offset: Int, value: Int) {
        b[offset] = value.toByte()
        b[offset + 1] = (value ushr 8).toByte()
    }

    private fun assertInsideRoot(filePath: String) {
        val canonical = File(filePath).canonicalFile
        assertEquals("file must live directly under root", root.canonicalFile, canonical.parentFile)
        assertTrue(canonical.path.startsWith(root.canonicalFile.path + File.separator))
    }

    private fun tempResidue(): List<File> = root.listFiles()!!.filter { it.name.contains(".tmp-") }

    // ---- save：原子写入 + 完整元数据 ---- //

    @Test
    fun `save writes final file atomically with no temp residue and returns db entity`() = runBlocking {
        val seg = segment("s1", startMs = 500L, endMs = 1500L)

        val saved = store.save(courseId = 7L, segment = seg, lastError = "NETWORK timeout")

        val files = root.listFiles()!!
        assertEquals("exactly one audio file in root", 1, files.size)
        assertTrue("no temp residue", tempResidue().isEmpty())
        val file = files.first()
        assertTrue("final file readable and byte-identical", file.readBytes().contentEquals(seg.bytes))
        // 返回实体带 DB 行 id，且 DAO 收到同一实体
        assertEquals(saved.id, dao.inserted.single().id)
        assertEquals(File(saved.filePath).canonicalFile, file.canonicalFile)
    }

    @Test
    fun `save records full pending metadata in dao`() = runBlocking {
        val seg = segment("s1", startMs = 500L, endMs = 1500L)

        store.save(courseId = 7L, segment = seg, lastError = "rate limited")

        val row = dao.inserted.single()
        assertEquals(7L, row.courseId)
        assertEquals("s1", row.segmentId)
        assertEquals(1000L, row.durationMs) // endOffset - startOffset
        assertEquals("PENDING", row.state)
        assertEquals(0, row.attempts)
        assertEquals("rate limited", row.lastError)
        assertEquals(now, row.createdTs) // 注入时钟
        assertInsideRoot(row.filePath)
        assertTrue("file on disk at recorded path", File(row.filePath).isFile)
    }

    @Test
    fun `segmentId path traversal is neutralized and file stays inside root`() = runBlocking {
        val seg = segment("../../escape", startMs = 0L, endMs = 100L)

        val saved = store.save(courseId = 7L, segment = seg)

        assertInsideRoot(saved.filePath)
        assertFalse("no file escaped root", File(root, "escape").exists())
        assertFalse("no file escaped outside", File(root.parentFile, "escape").exists())
        assertEquals("one file in root", 1, root.listFiles()!!.size)
    }

    @Test
    fun `duplicate save of same course and segment leaves exactly one file`() = runBlocking {
        val seg = segment("s1")
        val first = store.save(courseId = 7L, segment = seg)
        val second = store.save(courseId = 7L, segment = seg)

        assertEquals("same deterministic path", first.filePath, second.filePath)
        assertEquals("one file on disk", 1, root.listFiles()!!.size)
        assertTrue(File(first.filePath).readBytes().contentEquals(seg.bytes))
        assertEquals("both rows recorded", 2, dao.inserted.size)
    }

    // ---- delete：文件 + DB 行，幂等，不越界 ---- //

    @Test
    fun `delete removes file and db row and is idempotent`() = runBlocking {
        val saved = store.save(courseId = 7L, segment = segment("s1"))
        assertTrue(File(saved.filePath).isFile)

        store.delete(saved)

        assertFalse("audio file gone", File(saved.filePath).exists())
        assertEquals(saved, dao.deleted.single())
        assertTrue("db row gone", dao.inserted.none { it.id == saved.id })

        // 幂等：再删一次（文件已不存在）不得抛错
        store.delete(saved)
        assertEquals("still no db row", 0, dao.inserted.size)
    }

    @Test
    fun `delete never touches unrelated files or files outside root`() = runBlocking {
        val saved = store.save(courseId = 7L, segment = segment("s1"))
        val other = store.save(courseId = 7L, segment = segment("s2"))
        val unrelated = File(root, "unrelated.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val outside = File(tmp.root, "outside.wav").apply { writeBytes(wavBytes(seed = 1)) }
        val outsideEntity = PendingAudioEntity(
            id = 99L, courseId = 7L, segmentId = "evil", filePath = outside.absolutePath,
            durationMs = 100L, createdTs = now,
        )

        store.delete(saved)
        store.delete(outsideEntity)

        assertFalse(File(saved.filePath).exists())
        assertTrue("s2 file untouched", File(other.filePath).exists())
        assertTrue("unrelated file untouched", unrelated.exists())
        assertTrue("outside-root file untouched", outside.exists())
    }

    // ---- load：Success / Missing / Corrupt typed 结果 ---- //

    @Test
    fun `load returns Success with original bytes for saved file`() = runBlocking {
        val seg = segment("s1")
        val saved = store.save(courseId = 7L, segment = seg)

        val result = store.load(saved)

        assertTrue("must be Success, was $result", result is PendingAudioStore.LoadResult.Success)
        assertTrue((result as PendingAudioStore.LoadResult.Success).bytes.contentEquals(seg.bytes))
    }

    @Test
    fun `load returns Missing for absent file`() = runBlocking {
        val entity = PendingAudioEntity(
            id = 1L, courseId = 7L, segmentId = "s1", filePath = File(root, "never-written.wav").absolutePath,
            durationMs = 100L, createdTs = now,
        )
        assertEquals(PendingAudioStore.LoadResult.Missing, store.load(entity))
    }

    @Test
    fun `load returns Corrupt for truncated or header-broken wav`() = runBlocking {
        val tooShort = PendingAudioEntity(
            id = 1L, courseId = 7L, segmentId = "s1",
            filePath = File(root, "short.wav").apply { writeBytes(ByteArray(10) { 1 }) }.absolutePath,
            durationMs = 100L, createdTs = now,
        )
        val noRiff = PendingAudioEntity(
            id = 2L, courseId = 7L, segmentId = "s2",
            filePath = File(root, "no-riff.wav").apply { writeBytes(ByteArray(44) { 1 }) }.absolutePath,
            durationMs = 100L, createdTs = now,
        )
        val riffNoWave = PendingAudioEntity(
            id = 3L, courseId = 7L, segmentId = "s3",
            filePath = File(root, "riff-no-wave.wav").apply {
                val b = wavBytes()
                ascii(b, 8, "XXXX")
                writeBytes(b)
            }.absolutePath,
            durationMs = 100L, createdTs = now,
        )

        assertTrue(store.load(tooShort) is PendingAudioStore.LoadResult.Corrupt)
        assertTrue(store.load(noRiff) is PendingAudioStore.LoadResult.Corrupt)
        assertTrue(store.load(riffNoWave) is PendingAudioStore.LoadResult.Corrupt)
        // 损坏不得被静默当作成功 segment
        assertFalse(store.load(tooShort) is PendingAudioStore.LoadResult.Success)
    }

    @Test
    fun `load rejects entity pointing outside the private root`() = runBlocking {
        // 根目录外（tmp.root）的合法合成 WAV：私有 store 不得暴露/读取外部文件，
        // 对根目录外路径必须返回 Missing 而非 Success。
        val outside = File(tmp.root, "outside-valid.wav").apply { writeBytes(wavBytes(seed = 3)) }
        val entity = PendingAudioEntity(
            id = 4L, courseId = 7L, segmentId = "outside",
            filePath = outside.absolutePath,
            durationMs = 100L, createdTs = now,
        )

        assertEquals(
            "outside-root file must not be exposed as Success, was ${store.load(entity)}",
            PendingAudioStore.LoadResult.Missing,
            store.load(entity),
        )
    }

    @Test
    fun `playbackFile only exposes an existing valid-size file inside private root`() = runBlocking {
        val saved = store.save(courseId = 7L, segment = segment("s1"))
        assertEquals(File(saved.filePath).canonicalFile, store.playbackFile(saved)?.canonicalFile)

        val outside = File(tmp.root, "outside.wav").apply { writeBytes(wavBytes()) }
        val outsideEntity = saved.copy(filePath = outside.absolutePath)
        assertNull(store.playbackFile(outsideEntity))

        val missingEntity = saved.copy(filePath = File(root, "missing.wav").absolutePath)
        assertNull(store.playbackFile(missingEntity))
    }

    // ---- DB insert 失败清理：无孤儿、不误删 ---- //

    @Test
    fun `db insert failure deletes freshly written file leaving no orphan`() = runBlocking {
        dao.failInsertCount = 1
        try {
            store.save(courseId = 9L, segment = segment("s9"))
            fail("insert failure must propagate")
        } catch (e: IOException) {
            // expected
        }
        assertTrue("no orphan final file", root.listFiles()!!.isEmpty())
        assertTrue("no temp residue", tempResidue().isEmpty())
        assertTrue("no db row recorded", dao.inserted.isEmpty())
    }

    @Test
    fun `db insert failure on duplicate save keeps the pre-existing file`() = runBlocking {
        val seg = segment("s1")
        val first = store.save(courseId = 7L, segment = seg) // row + file
        dao.failInsertCount = 1

        try {
            store.save(courseId = 7L, segment = seg)
            fail("insert failure must propagate")
        } catch (e: IOException) {
            // expected
        }

        // 重复保存时 DB 唯一索引会拒绝第二行：清理只应针对本次新建，已有文件必须保留
        assertTrue("pre-existing file kept", File(first.filePath).isFile)
        assertTrue(File(first.filePath).readBytes().contentEquals(seg.bytes))
        assertEquals("exactly one file", 1, root.listFiles()!!.size)
        assertEquals("first row intact", 1, dao.inserted.size)
    }

    // ---- 回归：save 返回值必须携带 DAO 自增 id（否则 delete(saved) 按 id=0 删不到行） ---- //

    @Test
    fun `save returns entity carrying the dao auto-increment row id`() = runBlocking {
        val saved = store.save(courseId = 7L, segment = segment("s1"))

        // FakeDao.insert 返回 inserted.size.toLong() + 1，即真实 Room @Insert 的自增 row id
        // （SQLite AUTOINCREMENT 首行从 1 起）；save() 必须把该 id 复制回返回值，
        // 而不是返回 id=0 的原始实体。
        assertEquals("saved.id must equal dao-assigned row id", 1L, saved.id)
        assertEquals("dao row must be the one actually recorded", saved, dao.inserted.single())
        assertEquals("dao recorded row id must be the same", saved.id, dao.inserted.single().id)

        // 契约：save 的返回值可直接用于 delete(saved)，且按该 id 命中真实 DB 行
        store.delete(saved)
        assertTrue("row deleted by returned id", dao.inserted.none { it.id == saved.id })
    }

    @Test
    fun `save can record a retained successful segment without making it pending work`() = runBlocking {
        val saved = store.save(courseId = 7L, segment = segment("s1"), state = "RETAINED")

        assertEquals("RETAINED", saved.state)
        assertEquals("RETAINED", dao.inserted.single().state)
        assertTrue("retained audio remains playable", store.playbackFile(saved)?.isFile == true)
    }
}
