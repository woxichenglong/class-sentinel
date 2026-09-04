package com.classsentinel.core.importer

import android.net.Uri
import com.classsentinel.core.audio.WavSegment
import com.classsentinel.core.speech.AsrError
import com.classsentinel.core.speech.AsrException
import com.classsentinel.core.speech.SegmentSpeechEngine
import com.classsentinel.core.speech.SegmentSpeechRouter
import com.classsentinel.data.entities.TranscriptChunkEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioImportServiceTest {

    @Test
    fun `imports pcm wav as ordered segments through the same router`() = runBlocking {
        val writer = RecordingWriter()
        val engine = RecordingEngine()
        val service = service(engine = engine, writer = writer)

        val result = service.importAudio(
            uri = Uri.parse("content://test/lecture.wav"),
            title = "宏观经济学",
        )

        val success = result as AudioImportResult.Success
        assertEquals(7L, success.courseId)
        assertEquals(3, success.segments)
        assertEquals(3, success.transcriptChunks)
        assertEquals("导入录音：宏观经济学", writer.createdTitle)
        assertEquals(listOf("s1", "s2", "s3"), engine.seenIds)
        assertEquals(listOf("s1", "s2", "s3"), writer.chunks.map { it.segmentId })
        assertEquals(listOf(0L, 1000L, 2000L), writer.chunks.map { it.startOffsetMs })
        assertEquals(listOf(1000L, 2000L, 2500L), writer.chunks.map { it.endOffsetMs })
        assertEquals(7L, writer.finalizedCourseId)
        assertTrue(writer.abortedCourseId == null)
    }

    @Test
    fun `rejects non wav input before creating a course`() = runBlocking {
        val writer = RecordingWriter()
        val service = service(writer = writer, input = byteArrayOf(1, 2, 3, 4))

        val result = service.importAudio(Uri.parse("content://test/not-audio.bin"), "录音")

        assertEquals(AudioImportResult.Rejection.INVALID_WAV, (result as AudioImportResult.Rejected).reason)
        assertTrue(writer.createdTitle == null)
    }

    @Test
    fun `rejects input over bounded size`() = runBlocking {
        val writer = RecordingWriter()
        val service = service(writer = writer, maxInputBytes = 100L)

        val result = service.importAudio(Uri.parse("content://test/large.wav"), "录音")

        assertEquals(AudioImportResult.Rejection.TOO_LARGE, (result as AudioImportResult.Rejected).reason)
        assertTrue(writer.createdTitle == null)
    }

    @Test
    fun `transcription failure aborts imported course without fabricating a chunk`() = runBlocking {
        val writer = RecordingWriter()
        val engine = RecordingEngine(failOn = "s2")
        val service = service(engine = engine, writer = writer)

        val result = service.importAudio(Uri.parse("content://test/lecture.wav"), "录音")

        val failed = result as AudioImportResult.Failed
        assertEquals(AudioImportResult.Failure.TRANSCRIPTION_FAILED, failed.reason)
        assertEquals(7L, failed.courseId)
        assertEquals(listOf("s1", "s2"), engine.seenIds)
        assertEquals(listOf("s1"), writer.chunks.map { it.segmentId })
        assertEquals(7L, writer.abortedCourseId)
        assertTrue(writer.finalizedCourseId == null)
    }

    private fun service(
        engine: RecordingEngine = RecordingEngine(),
        writer: RecordingWriter = RecordingWriter(),
        input: ByteArray = wavBytes(durationMs = 2_500L),
        maxInputBytes: Long = 2_000_000L,
    ): AudioImportService = AudioImportService(
        source = ByteArrayAudioSource(input),
        router = SegmentSpeechRouter(primary = engine),
        writer = writer,
        clock = { 1_700_000_000_000L },
        segmentDurationMs = 1_000L,
        maxInputBytes = maxInputBytes,
    )

    private class ByteArrayAudioSource(private val bytes: ByteArray) : AudioImportSource {
        override fun open(uri: Uri): InputStream = ByteArrayInputStream(bytes)
    }

    private class RecordingEngine(private val failOn: String? = null) : SegmentSpeechEngine {
        val seenIds = mutableListOf<String>()
        override val name: String = "fake"

        override suspend fun transcribeSegment(segment: WavSegment): Result<String> {
            seenIds += segment.id
            return if (segment.id == failOn) {
                Result.failure(AsrException(AsrError(AsrError.Kind.SERVER, retriable = false, message = "synthetic")))
            } else {
                Result.success("text-${segment.id}")
            }
        }
    }

    private class RecordingWriter : AudioImportWriter {
        var createdTitle: String? = null
        var finalizedCourseId: Long? = null
        var abortedCourseId: Long? = null
        val chunks = mutableListOf<TranscriptChunkEntity>()

        override suspend fun createCourse(title: String, startTs: Long): Long {
            createdTitle = title
            return 7L
        }

        override suspend fun insertTranscript(chunk: TranscriptChunkEntity): Long {
            chunks += chunk.copy(id = chunks.size.toLong() + 1L)
            return chunks.last().id
        }

        override suspend fun finalizeCourse(courseId: Long, endTs: Long) {
            finalizedCourseId = courseId
        }

        override suspend fun abortCourse(courseId: Long, endTs: Long) {
            abortedCourseId = courseId
        }
    }

    private fun wavBytes(durationMs: Long): ByteArray {
        val samples = (durationMs * 16L).toInt()
        val dataSize = samples * 2
        val output = ByteArray(44 + dataSize)
        ascii(output, 0, "RIFF")
        intLe(output, 4, 36 + dataSize)
        ascii(output, 8, "WAVE")
        ascii(output, 12, "fmt ")
        intLe(output, 16, 16)
        shortLe(output, 20, 1)
        shortLe(output, 22, 1)
        intLe(output, 24, 16_000)
        intLe(output, 28, 32_000)
        shortLe(output, 32, 2)
        shortLe(output, 34, 16)
        ascii(output, 36, "data")
        intLe(output, 40, dataSize)
        for (i in 44 until output.size) output[i] = (i % 31).toByte()
        return output
    }

    private fun ascii(bytes: ByteArray, offset: Int, text: String) {
        text.toByteArray().copyInto(bytes, offset)
    }

    private fun intLe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    private fun shortLe(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }
}
