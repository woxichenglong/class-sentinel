package com.classsentinel.core.speech

import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Direct PCM16 mono WAV source for replay; deliberately bypasses the legacy importer/VAD. */
internal object PcmReplayWavSource {
    fun chunks(
        input: InputStream,
        expectedSampleRate: Int,
        chunkMs: Int,
    ): Flow<ShortArray> = flow {
        require(expectedSampleRate > 0) { "REPLAY_SAMPLE_RATE_INVALID" }
        require(chunkMs > 0) { "REPLAY_CHUNK_INVALID" }
        val chunkSamples = (expectedSampleRate.toLong() * chunkMs / 1_000L)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

        input.use { source ->
            val header = ByteArray(RIFF_HEADER_BYTES)
            readRequired(source, header, "REPLAY_WAV_HEADER")
            require(header.copyOfRange(0, 4).contentEquals(RIFF)) { "REPLAY_WAV_FORMAT" }
            require(header.copyOfRange(8, 12).contentEquals(WAVE)) { "REPLAY_WAV_FORMAT" }

            var format: WavFormat? = null
            var emittedData = false
            while (true) {
                val chunkHeader = ByteArray(CHUNK_HEADER_BYTES)
                val first = source.read(chunkHeader, 0, 1)
                if (first < 0) break
                require(first == 1) { "REPLAY_WAV_TRUNCATED" }
                readRequired(source, chunkHeader, 1, CHUNK_HEADER_BYTES - 1, "REPLAY_WAV_TRUNCATED")
                val size = readUnsignedIntLe(chunkHeader, 4)

                when {
                    chunkHeader.copyOfRange(0, 4).contentEquals(FMT) -> {
                        require(size in 16L..MAX_FORMAT_CHUNK_BYTES) { "REPLAY_WAV_FORMAT" }
                        val bytes = ByteArray(size.toInt())
                        readRequired(source, bytes, "REPLAY_WAV_TRUNCATED")
                        format = parseFormat(bytes, expectedSampleRate)
                    }
                    chunkHeader.copyOfRange(0, 4).contentEquals(DATA) -> {
                        require(format != null) { "REPLAY_WAV_FORMAT" }
                        require(size % 2L == 0L) { "REPLAY_WAV_FORMAT" }
                        emitPcm(source, size, chunkSamples) { emit(it) }
                        emittedData = true
                    }
                    else -> skipRequired(source, size)
                }
                if (size % 2L != 0L) skipRequired(source, 1L)
            }

            require(format != null && emittedData) { "REPLAY_WAV_DATA_MISSING" }
        }
    }

    private suspend fun emitPcm(
        source: InputStream,
        byteCount: Long,
        chunkSamples: Int,
        emitChunk: suspend (ShortArray) -> Unit,
    ) {
        val chunkBytes = chunkSamples * 2
        var remaining = byteCount
        while (remaining > 0L) {
            val size = minOf(remaining, chunkBytes.toLong()).toInt()
            val bytes = ByteArray(size)
            readRequired(source, bytes, "REPLAY_WAV_TRUNCATED")
            val samples = ShortArray(size / 2) { index ->
                val offset = index * 2
                ((bytes[offset].toInt() and 0xFF) or (bytes[offset + 1].toInt() shl 8)).toShort()
            }
            emitChunk(samples)
            remaining -= size
        }
    }

    private fun parseFormat(bytes: ByteArray, expectedSampleRate: Int): WavFormat {
        val audioFormat = readUnsignedShortLe(bytes, 0)
        val channels = readUnsignedShortLe(bytes, 2)
        val sampleRate = readUnsignedIntLe(bytes, 4)
        val blockAlign = readUnsignedShortLe(bytes, 12)
        val bitsPerSample = readUnsignedShortLe(bytes, 14)
        require(
            audioFormat == PCM_FORMAT &&
                channels == MONO_CHANNELS &&
                sampleRate == expectedSampleRate.toLong() &&
                blockAlign == PCM_BLOCK_ALIGN &&
                bitsPerSample == PCM_BITS,
        ) { "REPLAY_WAV_FORMAT" }
        return WavFormat(sampleRate.toInt())
    }

    private fun readRequired(
        source: InputStream,
        target: ByteArray,
        message: String,
    ) = readRequired(source, target, 0, target.size, message)

    private fun readRequired(
        source: InputStream,
        target: ByteArray,
        offset: Int,
        length: Int,
        message: String,
    ) {
        var position = offset
        val end = offset + length
        while (position < end) {
            val count = source.read(target, position, end - position)
            require(count > 0) { message }
            position += count
        }
    }

    private fun skipRequired(source: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0L) {
            val skipped = source.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                require(source.read() >= 0) { "REPLAY_WAV_TRUNCATED" }
                remaining--
            }
        }
    }

    private fun readUnsignedShortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUnsignedIntLe(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)

    private data class WavFormat(val sampleRate: Int)

    private val RIFF = "RIFF".toByteArray()
    private val WAVE = "WAVE".toByteArray()
    private val FMT = "fmt ".toByteArray()
    private val DATA = "data".toByteArray()

    private const val RIFF_HEADER_BYTES = 12
    private const val CHUNK_HEADER_BYTES = 8
    private const val MAX_FORMAT_CHUNK_BYTES = 4_096L
    private const val PCM_FORMAT = 1
    private const val MONO_CHANNELS = 1
    private const val PCM_BLOCK_ALIGN = 2
    private const val PCM_BITS = 16
}
