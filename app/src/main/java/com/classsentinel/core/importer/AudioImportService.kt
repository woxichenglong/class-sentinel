package com.classsentinel.core.importer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.classsentinel.core.audio.WavSegment
import com.classsentinel.core.speech.ProductionAsrFactory
import com.classsentinel.core.speech.SegmentSpeechRouter
import com.classsentinel.data.AppDatabase
import com.classsentinel.data.CourseRepository
import com.classsentinel.data.entities.TranscriptChunkEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream

/** SAF 只读音频输入边界；实现不得把 URI 内容转成日志或 WorkManager 参数。 */
fun interface AudioImportSource {
    fun open(uri: Uri): InputStream
}

/** 导入课程的最小持久化边界，便于 JVM 测试验证顺序与失败收尾。 */
interface AudioImportWriter {
    suspend fun createCourse(title: String, startTs: Long): Long
    suspend fun insertTranscript(chunk: TranscriptChunkEntity): Long
    suspend fun finalizeCourse(courseId: Long, endTs: Long)
    suspend fun abortCourse(courseId: Long, endTs: Long)
}

sealed interface AudioImportResult {
    data class Success(
        val courseId: Long,
        val segments: Int,
        val transcriptChunks: Int,
    ) : AudioImportResult

    data class Rejected(val reason: Rejection) : AudioImportResult

    data class Failed(
        val reason: Failure,
        val courseId: Long?,
    ) : AudioImportResult

    enum class Rejection {
        INVALID_WAV,
        UNSUPPORTED_FORMAT,
        TOO_LARGE,
        EMPTY_AUDIO,
    }

    enum class Failure {
        SOURCE_UNAVAILABLE,
        TRANSCRIPTION_FAILED,
        PERSISTENCE_FAILED,
    }
}

/**
 * Task 30：从 SAF 选择的本地 WAV 流式切段，复用生产 [SegmentSpeechRouter]，
 * 将结果写成一门带“导入录音”标识的历史课程。
 *
 * 仅接受 16kHz / mono / PCM16 WAV；输入有明确上限，单次只在内存中保留一个段，
 * 不申请存储宽权限，也不把原始音频或课堂文字写入返回错误。
 */
class AudioImportService(
    private val source: AudioImportSource,
    private val router: SegmentSpeechRouter,
    private val writer: AudioImportWriter,
    private val clock: () -> Long = System::currentTimeMillis,
    private val segmentDurationMs: Long = DEFAULT_SEGMENT_DURATION_MS,
    private val maxInputBytes: Long = DEFAULT_MAX_INPUT_BYTES,
) {
    init {
        require(segmentDurationMs in MIN_SEGMENT_DURATION_MS..MAX_SEGMENT_DURATION_MS)
        require(maxInputBytes > WAV_HEADER_BYTES)
    }

    suspend fun importAudio(uri: Uri, title: String): AudioImportResult {
        var courseId: Long? = null
        var nextSeq = 0
        var segmentCount = 0
        var chunkCount = 0
        val startedAt = clock()
        val safeTitle = importedTitle(title)

        try {
            source.open(uri).use { input ->
                withContext(Dispatchers.IO) {
                    WavPcmReader(input, maxInputBytes).forEachSegment(
                        segmentDurationMs = segmentDurationMs,
                    ) { segment ->
                        if (courseId == null) {
                            courseId = writer.createCourse(safeTitle, startedAt)
                        }
                        val currentCourseId = courseId ?: error("course creation returned no id")
                        val routed = router.transcribeSegment(segment)
                        val result = routed.getOrElse { throw ImportTranscriptionFailure() }
                        val text = result.text.trim()
                        if (text.isBlank()) throw ImportTranscriptionFailure()
                        writer.insertTranscript(
                            TranscriptChunkEntity(
                                courseId = currentCourseId,
                                seq = nextSeq++,
                                text = text,
                                ts = clock(),
                                segmentId = segment.id,
                                startOffsetMs = segment.startOffsetMs,
                                endOffsetMs = segment.endOffsetMs,
                            ),
                        )
                        segmentCount++
                        chunkCount++
                    }
                }
            }

            val completedCourseId = courseId
                ?: return AudioImportResult.Rejected(AudioImportResult.Rejection.EMPTY_AUDIO)
            writer.finalizeCourse(completedCourseId, clock())
            return AudioImportResult.Success(
                courseId = completedCourseId,
                segments = segmentCount,
                transcriptChunks = chunkCount,
            )
        } catch (e: CancellationException) {
            abortQuietly(courseId)
            throw e
        } catch (e: ImportRejected) {
            abortQuietly(courseId)
            return AudioImportResult.Rejected(e.reason)
        } catch (_: ImportTranscriptionFailure) {
            abortQuietly(courseId)
            return AudioImportResult.Failed(
                reason = AudioImportResult.Failure.TRANSCRIPTION_FAILED,
                courseId = courseId,
            )
        } catch (_: IOException) {
            abortQuietly(courseId)
            return AudioImportResult.Failed(
                reason = if (courseId == null) {
                    AudioImportResult.Failure.SOURCE_UNAVAILABLE
                } else {
                    AudioImportResult.Failure.PERSISTENCE_FAILED
                },
                courseId = courseId,
            )
        } catch (_: Exception) {
            abortQuietly(courseId)
            return AudioImportResult.Failed(
                reason = AudioImportResult.Failure.PERSISTENCE_FAILED,
                courseId = courseId,
            )
        }
    }

    private suspend fun abortQuietly(courseId: Long?) {
        if (courseId == null) return
        runCatching { writer.abortCourse(courseId, clock()) }
    }

    companion object {
        const val DEFAULT_SEGMENT_DURATION_MS = 4_000L
        const val DEFAULT_MAX_INPUT_BYTES = 50L * 1024L * 1024L
        private const val MIN_SEGMENT_DURATION_MS = 100L
        private const val MAX_SEGMENT_DURATION_MS = 60_000L
        private const val WAV_HEADER_BYTES = 44

        /** 生产装配：SAF ContentResolver + 当前配置的单段 Router + Room。 */
        suspend fun create(context: Context): AudioImportService {
            val appContext = context.applicationContext
            return AudioImportService(
                source = ContentResolverAudioSource(appContext.contentResolver),
                router = ProductionAsrFactory.createRouter(appContext),
                writer = RoomAudioImportWriter(AppDatabase.get(appContext)),
            )
        }

        fun importedTitle(title: String): String {
            val normalized = title.trim().replace(Regex("\\s+"), " ").take(100)
            return "导入录音：${normalized.ifBlank { "本地音频" }}"
        }
    }
}

private class ContentResolverAudioSource(
    private val resolver: ContentResolver,
) : AudioImportSource {
    override fun open(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw IOException("audio source unavailable")
}

private class RoomAudioImportWriter(
    private val db: AppDatabase,
) : AudioImportWriter {
    private val courses = CourseRepository(db)

    override suspend fun createCourse(title: String, startTs: Long): Long =
        courses.createRunningCourse(title, startTs)

    override suspend fun insertTranscript(chunk: TranscriptChunkEntity): Long =
        withContext(Dispatchers.IO) { db.transcriptDao().insert(chunk) }

    override suspend fun finalizeCourse(courseId: Long, endTs: Long) {
        courses.finalizeCourse(courseId, endTs)
    }

    override suspend fun abortCourse(courseId: Long, endTs: Long) {
        withContext(Dispatchers.IO) {
            db.withTransaction {
                db.courseDao().getById(courseId)?.let { current ->
                    if (current.status == "RUNNING" && current.endTs == null) {
                        db.courseDao().update(current.copy(status = "ABORTED", endTs = endTs))
                    }
                }
            }
        }
    }
}

private class ImportRejected(val reason: AudioImportResult.Rejection) : Exception()

private class ImportTranscriptionFailure : Exception()

private data class WavFormat(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
) {
    val bytesPerSample: Int get() = channels * bitsPerSample / 8
}

/** 受 maxInputBytes 约束的 RIFF/WAVE PCM reader；只把一个分段留在内存中。 */
private class WavPcmReader(
    private val input: InputStream,
    private val maxInputBytes: Long,
) {
    private var bytesRead = 0L

    suspend fun forEachSegment(
        segmentDurationMs: Long,
        onSegment: suspend (WavSegment) -> Unit,
    ) {
        val riffHeader = readExact(12) ?: throw ImportRejected(AudioImportResult.Rejection.INVALID_WAV)
        if (!riffHeader.startsWithAscii("RIFF") || !riffHeader.sliceArray(8 until 12).startsWithAscii("WAVE")) {
            throw ImportRejected(AudioImportResult.Rejection.INVALID_WAV)
        }

        var format: WavFormat? = null
        var foundData = false
        var segmentIndex = 0
        var totalSamples = 0L

        while (true) {
            val chunkHeader = readExact(8) ?: break
            val chunkId = chunkHeader.sliceArray(0 until 4).ascii()
            val chunkSize = uint32LE(chunkHeader, 4)
            when (chunkId) {
                "fmt " -> {
                    if (chunkSize < 16L || chunkSize > MAX_FORMAT_CHUNK_BYTES) {
                        throw ImportRejected(AudioImportResult.Rejection.INVALID_WAV)
                    }
                    val fmt = readExact(chunkSize.toInt())
                        ?: throw ImportRejected(AudioImportResult.Rejection.INVALID_WAV)
                    format = parseFormat(fmt)
                }

                "data" -> {
                    val currentFormat = format
                        ?: throw ImportRejected(AudioImportResult.Rejection.INVALID_WAV)
                    foundData = true
                    val dataSamples = readPcmData(
                        size = chunkSize,
                        format = currentFormat,
                        segmentDurationMs = segmentDurationMs,
                        segmentIndex = segmentIndex,
                        totalSamples = totalSamples,
                        onSegment = onSegment,
                    )
                    segmentIndex += dataSamples.segmentsEmitted
                    totalSamples += dataSamples.samplesRead
                }

                else -> skipFully(chunkSize)
            }
            if (chunkSize % 2L != 0L) skipFully(1L)
        }

        if (!foundData) throw ImportRejected(AudioImportResult.Rejection.EMPTY_AUDIO)
    }

    private suspend fun readPcmData(
        size: Long,
        format: WavFormat,
        segmentDurationMs: Long,
        segmentIndex: Int,
        totalSamples: Long,
        onSegment: suspend (WavSegment) -> Unit,
    ): DataReadResult {
        if (size == 0L) return DataReadResult(0L, 0)
        if (size % format.bytesPerSample.toLong() != 0L) {
            throw ImportRejected(AudioImportResult.Rejection.INVALID_WAV)
        }
        val segmentPcmBytes = (segmentDurationMs * format.sampleRate / 1_000L * format.bytesPerSample)
            .toInt()
            .coerceAtLeast(format.bytesPerSample)
        val pcm = ByteArray(segmentPcmBytes)
        var remaining = size
        var filled = 0
        var samplesRead = 0L
        var segmentsEmitted = 0

        while (remaining > 0L) {
            val requested = minOf(remaining, (pcm.size - filled).toLong()).toInt()
            val count = readAtMost(pcm, filled, requested)
            if (count <= 0) throw ImportRejected(AudioImportResult.Rejection.INVALID_WAV)
            filled += count
            remaining -= count
            samplesRead += count / format.bytesPerSample
            if (filled == pcm.size) {
                val start = totalSamples + samplesRead - (filled / format.bytesPerSample)
                val end = totalSamples + samplesRead
                onSegment(
                    WavSegment(
                        id = "s${segmentIndex + segmentsEmitted + 1}",
                        startOffsetMs = samplesToMillis(start, format.sampleRate),
                        endOffsetMs = samplesToMillis(end, format.sampleRate),
                        bytes = pcmToWav(pcm, format),
                    ),
                )
                segmentsEmitted++
                filled = 0
            }
        }

        if (filled > 0) {
            val leftoverSamples = filled / format.bytesPerSample
            val start = totalSamples + samplesRead - leftoverSamples
            val end = totalSamples + samplesRead
            onSegment(
                WavSegment(
                    id = "s${segmentIndex + segmentsEmitted + 1}",
                    startOffsetMs = samplesToMillis(start, format.sampleRate),
                    endOffsetMs = samplesToMillis(end, format.sampleRate),
                    bytes = pcmToWav(pcm.copyOf(filled), format),
                ),
            )
            segmentsEmitted++
        }
        return DataReadResult(samplesRead, segmentsEmitted)
    }

    private fun parseFormat(bytes: ByteArray): WavFormat {
        val audioFormat = uint16LE(bytes, 0)
        val channels = uint16LE(bytes, 2)
        val sampleRate = uint32LE(bytes, 4).toInt()
        val bitsPerSample = uint16LE(bytes, 14)
        if (audioFormat != PCM_FORMAT || channels != MONO_CHANNELS ||
            sampleRate != SUPPORTED_SAMPLE_RATE || bitsPerSample != PCM_BITS
        ) {
            throw ImportRejected(AudioImportResult.Rejection.UNSUPPORTED_FORMAT)
        }
        return WavFormat(sampleRate, channels, bitsPerSample)
    }

    private fun readExact(size: Int): ByteArray? {
        val output = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(output, offset, size - offset)
            if (count < 0) return null
            if (count == 0) continue
            countBytes(count)
            offset += count
        }
        return output
    }

    private fun readAtMost(buffer: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0) return 0
        val count = input.read(buffer, offset, size)
        if (count > 0) countBytes(count)
        return count
    }

    private fun skipFully(size: Long) {
        var remaining = size
        val scratch = ByteArray(SKIP_BUFFER_BYTES)
        while (remaining > 0L) {
            val requested = minOf(remaining, scratch.size.toLong()).toInt()
            val count = input.read(scratch, 0, requested)
            if (count < 0) throw ImportRejected(AudioImportResult.Rejection.INVALID_WAV)
            if (count == 0) continue
            countBytes(count)
            remaining -= count
        }
    }

    private fun countBytes(count: Int) {
        bytesRead += count
        if (bytesRead > maxInputBytes) throw ImportRejected(AudioImportResult.Rejection.TOO_LARGE)
    }

    private data class DataReadResult(val samplesRead: Long, val segmentsEmitted: Int)

    private companion object {
        const val PCM_FORMAT = 1
        const val MONO_CHANNELS = 1
        const val SUPPORTED_SAMPLE_RATE = 16_000
        const val PCM_BITS = 16
        const val MAX_FORMAT_CHUNK_BYTES = 4_096L
        const val SKIP_BUFFER_BYTES = 8_192
    }
}

private fun samplesToMillis(samples: Long, sampleRate: Int): Long = samples * 1_000L / sampleRate

private fun pcmToWav(pcm: ByteArray, format: WavFormat): ByteArray {
    val dataSize = pcm.size
    val output = ByteArray(44 + dataSize)
    "RIFF".toByteArray().copyInto(output, 0)
    writeIntLE(output, 4, 36 + dataSize)
    "WAVE".toByteArray().copyInto(output, 8)
    "fmt ".toByteArray().copyInto(output, 12)
    writeIntLE(output, 16, 16)
    writeShortLE(output, 20, 1)
    writeShortLE(output, 22, format.channels)
    writeIntLE(output, 24, format.sampleRate)
    writeIntLE(output, 28, format.sampleRate * format.bytesPerSample)
    writeShortLE(output, 32, format.bytesPerSample)
    writeShortLE(output, 34, format.bitsPerSample)
    "data".toByteArray().copyInto(output, 36)
    writeIntLE(output, 40, dataSize)
    pcm.copyInto(output, 44)
    return output
}

private fun writeIntLE(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value ushr 8).toByte()
    bytes[offset + 2] = (value ushr 16).toByte()
    bytes[offset + 3] = (value ushr 24).toByte()
}

private fun writeShortLE(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value ushr 8).toByte()
}

private fun uint16LE(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

private fun uint32LE(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xFFL) or
        ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFFL) shl 24)

private fun ByteArray.ascii(): String = toString(Charsets.US_ASCII)

private fun ByteArray.startsWithAscii(expected: String): Boolean =
    size >= expected.length && expected.toByteArray().contentEquals(copyOf(expected.length))
