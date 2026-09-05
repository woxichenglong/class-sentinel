package com.classsentinel.core.audio

import com.classsentinel.data.PendingAudioDao
import com.classsentinel.data.entities.PendingAudioEntity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * v0.2 Task 16：失败/未转写语音分段的私有文件存储边界。
 *
 * 只持久化**单个失败 [WavSegment]**（不保存完整课程录音）：先写同目录临时文件、
 * 完整 flush+fsync 后再原子 rename 到最终文件，DB insert 成功后才算保存成功；
 * insert 失败会清理本次新建的文件（不误删重复保存时已有的文件）。
 *
 * - 文件路径由安全文件名派生（`c<courseId>-s<segmentId>.wav`，segmentId 按字符
 *   白名单清理），始终位于注入的 [rootDir] 内，segmentId 不能造成路径穿越；
 *   重复 (courseId, segmentId) 映射到同一确定性路径，不会留下多个音频文件。
 * - [delete] 先删 DB 行、成功后才删文件（成功转写后调用），幂等；DAO 删除
 *   失败时异常原样抛出且不触碰文件；绝不删除根目录以外或与本次实体无关的文件。
 * - [load] 对缺失文件返回 [LoadResult.Missing]、对损坏 WAV（RIFF/WAVE 头或
 *   最小长度不符）返回 [LoadResult.Corrupt]，让 Worker 能直接置终态失败，
 *   不会把损坏字节静默当作成功 segment。
 * - 红线：本类不打印、不记录原始音频字节；错误信息只描述类别，不含音频内容。
 */
class PendingAudioStore(
    private val rootDir: File,
    private val dao: PendingAudioDao,
    /** 注入时钟（测试确定性）；默认 System.currentTimeMillis。 */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** load 的 typed 结果：成功字节 / 文件缺失 / WAV 损坏（不可重试，应终态失败）。 */
    sealed interface LoadResult {
        data class Success(val bytes: ByteArray) : LoadResult {
            override fun equals(other: Any?): Boolean =
                other is Success && bytes.contentEquals(other.bytes)

            override fun hashCode(): Int = bytes.contentHashCode()
        }

        data object Missing : LoadResult
        data object Corrupt : LoadResult
    }

    /**
     * 原子保存单个失败段并登记 DAO 元数据；返回带 DB 行 id 的 [PendingAudioEntity]。
     *
     * 流程：安全文件名 → 写临时文件（flush+fsync）→ 原子 rename 到最终文件 →
     * DAO insert（PENDING / attempts=0 / lastError / createdTs=注入时钟）。
     * insert 失败抛 [IOException] 并清理本次新建的文件（若最终文件原本已存在，
     * 即重复保存场景，则保留原文件）。根目录不可写时抛 [IOException]。
     */
    suspend fun save(
        courseId: Long,
        segment: WavSegment,
        lastError: String? = null,
        state: String = "PENDING",
    ): PendingAudioEntity {
        if (!rootDir.exists() && !rootDir.mkdirs()) {
            throw IOException("pending audio root not writable: $rootDir")
        }
        val target = resolveFile(courseId, segment.id)
        val tmp = File(rootDir, ".tmp-${target.name}-${System.nanoTime()}")
        val createdNew = !target.exists()
        try {
            writeAtomically(tmp, target, segment.bytes)
            val entity = PendingAudioEntity(
                courseId = courseId,
                segmentId = segment.id,
                filePath = target.absolutePath,
                durationMs = (segment.endOffsetMs - segment.startOffsetMs).coerceAtLeast(0L),
                startOffsetMs = segment.startOffsetMs,
                endOffsetMs = segment.endOffsetMs,
                state = state,
                attempts = 0,
                lastError = lastError,
                createdTs = clock(),
            )
            val rowId = dao.insert(entity)
            return entity.copy(id = rowId)
        } catch (t: Throwable) {
            // DB/写入失败：清理本次写入。重复保存场景目标文件原本存在时保留（幂等不误删）。
            if (createdNew) {
                target.delete()
            }
            throw t
        }
    }

    /**
     * 显式删除 DB 行与私有音频文件（成功转写/清理后调用）。幂等：
     * 文件或行不存在时不抛错。绝不删除根目录之外或与实体无关的文件。
     *
     * 顺序契约（Task 16 数据丢失修复）：先删 DB 行、成功后才删文件。DAO 删除
     * 抛 [IOException] 时必须原样抛出且不得触碰文件——否则 Worker 按 retry
     * 返回时 PENDING 行仍在而音频已丢，重试只能 load 成 Missing（数据丢失）。
     * 文件删除失败不抛错（与历史行为一致，DAO 已成功删除，重试无残留）。
     */
    suspend fun delete(entity: PendingAudioEntity) {
        // 1) DB 行先删：失败则异常原样抛出，文件绝不动（数据保留契约）。
        dao.delete(entity)
        // 2) DB 行已删才删私有音频文件；仅限根目录内（防越界删除）。
        deleteFile(entity)
    }

    /**
     * 删除已由外层 Room 事务移除的实体对应文件，不操作 DAO。
     * 返回 false 表示路径越界或文件删除失败；文件已不存在视为成功。
     */
    fun deleteFile(entity: PendingAudioEntity): Boolean {
        val file = File(entity.filePath)
        if (!isInsideRoot(file)) return false
        return !file.exists() || file.delete()
    }

    /**
     * Safely sweep old final WAV files that are no longer referenced by any DB row.
     *
     * The caller supplies canonical DB references; this method owns root, filename, temp-file,
     * ordinary-file, age, and deletion rules so cleanup cannot grow a second path policy.
     */
    internal fun sweepOrphans(
        referencedPaths: Set<String>,
        nowMillis: Long,
        gracePeriodMs: Long,
    ): PendingAudioOrphanSweepResult {
        val files = rootDir.listFiles() ?: return PendingAudioOrphanSweepResult()
        var deleted = 0
        var failures = 0
        files.forEach { file ->
            if (!file.isFile || file.name.startsWith(".tmp-") || !PENDING_WAV_NAME.matches(file.name)) return@forEach
            if (!isInsideRoot(file)) return@forEach
            if (file.canonicalPath in referencedPaths) return@forEach
            val modified = file.lastModified()
            val age = nowMillis - modified
            if (modified <= 0L || age < 0L || age < gracePeriodMs) return@forEach
            if (file.delete()) deleted++ else failures++
        }
        return PendingAudioOrphanSweepResult(deleted = deleted, failures = failures)
    }

    /**
     * 返回可交给 [android.media.MediaPlayer] 的私有 WAV 文件。
     *
     * 回放和删除使用同一个根目录边界：数据库中的旧/恶意路径即使指向一个合法文件，
     * 也不能被 UI 打开。文件不存在或不是普通文件时返回 null，不把错误路径暴露给 UI。
     */
    fun playbackFile(entity: PendingAudioEntity): File? {
        val file = File(entity.filePath)
        return file.takeIf { isInsideRoot(it) && it.isFile && it.length() >= MIN_WAV_LENGTH }
    }

    /**
     * 读取已保存的失败段。缺失 → [LoadResult.Missing]；损坏 WAV → [LoadResult.Corrupt]
     * （至少校验 RIFF/WAVE 头与最小长度，二者都满足才算 Success）。IO 异常按损坏处理，
     * 让 Worker 置终态失败而不是无限重试。
     */
    suspend fun load(entity: PendingAudioEntity): LoadResult {
        val file = File(entity.filePath)
        // 根目录外的路径一律视为缺失：私有 store 不得读取/暴露外部文件。
        if (!isInsideRoot(file) || !file.isFile) return LoadResult.Missing
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < MIN_WAV_LENGTH) return LoadResult.Corrupt
                val header = ByteArray(12)
                raf.readFully(header)
                // RIFF 布局：bytes 0-3 = "RIFF"，bytes 4-7 = chunk size，bytes 8-11 = "WAVE"
                if (!header.copyOfRange(0, 4).contentEquals(RIFF_TAG) ||
                    !header.copyOfRange(8, 12).contentEquals(WAVE_TAG)
                ) {
                    return LoadResult.Corrupt
                }
                val data = ByteArray(raf.length().toInt())
                raf.seek(0)
                raf.readFully(data)
                LoadResult.Success(data)
            }
        } catch (e: IOException) {
            LoadResult.Corrupt
        }
    }

    /** 确定性安全文件名：`c<courseId>-s<segmentId>.wav`，非法字符替换为 `_`。 */
    private fun resolveFile(courseId: Long, segmentId: String): File {
        val safe = segmentId.map { c ->
            if (c.isLetterOrDigit() || c == '_' || c == '-') c else '_'
        }.joinToString("")
        val name = "c$courseId-s$safe.wav"
        return File(rootDir, name)
    }

    /** 文件必须位于根目录内（防越界删除）。 */
    private fun isInsideRoot(file: File): Boolean =
        file.canonicalFile.parentFile?.canonicalFile == rootDir.canonicalFile

    /** 写入临时文件（flush+fsync 保证落盘）后原子 rename 到目标。 */
    private fun writeAtomically(tmp: File, target: File, bytes: ByteArray) {
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                // Windows：renameTo 无法覆盖已存在文件；先删旧再 rename（写入已 fsync，安全）
                if (target.exists() && target.delete() && tmp.renameTo(target)) {
                    return
                }
                throw IOException("atomic rename failed: $tmp -> $target")
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private companion object {
        /** 最小合法 WAV 长度：44 字节标准头 + 1 个 PCM16 采样。 */
        private const val MIN_WAV_LENGTH = 46
        private val PENDING_WAV_NAME = Regex("^c\\d+-s[A-Za-z0-9_-]*\\.wav$")
        private val RIFF_TAG = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
        )
        private val WAVE_TAG = byteArrayOf(
            'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(),
        )
    }
}
