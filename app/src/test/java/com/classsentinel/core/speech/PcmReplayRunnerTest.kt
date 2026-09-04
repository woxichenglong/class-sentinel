package com.classsentinel.core.speech

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PcmReplayRunnerTest {

    @Test
    fun `replay records profile commit phase events and first latencies`() = runTest {
        val clock = FakeClock()
        val engine = object : StreamingSpeechEngine {
            override val name = "fake"

            override fun transcribe(pcm: Flow<ShortArray>): Flow<StreamingAsrEvent> = flow {
                pcm.toList()
                clock.advanceMs(12L)
                emit(StreamingAsrEvent.Partial(1, "CAPM", 500L))
                clock.advanceMs(8L)
                emit(StreamingAsrEvent.Final(1, "CAPM beta", 0L, 1_000L))
            }
        }
        val runner = PcmReplayRunner(nowNanos = { clock.nowNanos })

        val result = runner.run(
            profile = ModelProfiles.ZIPFORMER_ZH_14M,
            gitCommitSha = "abc1234",
            runId = "run-001",
            phase = ReplayPhase.WARM,
            pcm = flowOf(ShortArray(16_000)),
            engine = engine,
        )

        assertEquals("sherpa-zh-14m", result.modelProfileId)
        assertEquals("abc1234", result.gitCommitSha)
        assertEquals("run-001", result.runId)
        assertEquals(ReplayPhase.WARM, result.phase)
        assertEquals(1_000L, result.inputDurationMs)
        assertEquals(20L, result.elapsedMs)
        assertEquals(
            listOf(
                StreamingAsrEvent.Partial(1, "CAPM", 500L),
                StreamingAsrEvent.Final(1, "CAPM beta", 0L, 1_000L),
            ),
            result.observations.map { it.event },
        )
        assertEquals(12L, result.firstPartialLatencyMs)
        assertEquals(20L, result.firstFinalLatencyMs)
        assertNotNull(result.observations.first().observedLatencyMs)
    }

    @Test
    fun `wav replay feeds fixed pcm chunks without legacy importer`() = runTest {
        val chunkSizes = mutableListOf<Int>()
        val engine = object : StreamingSpeechEngine {
            override val name = "fake"

            override fun transcribe(pcm: Flow<ShortArray>): Flow<StreamingAsrEvent> = flow {
                pcm.collect { chunk -> chunkSizes += chunk.size }
                emit(StreamingAsrEvent.Final(1, "固定回放", 0L, 2L))
            }
        }
        val runner = PcmReplayRunner(nowNanos = { 0L })

        val result = runner.runWav(
            profile = ModelProfiles.ZIPFORMER_ZH_14M,
            gitCommitSha = "abc1234",
            runId = "run-wav-001",
            phase = ReplayPhase.COLD,
            wav = ByteArrayInputStream(wavPcm(ShortArray(33) { it.toShort() })),
            chunkMs = 1,
            engine = engine,
        )

        assertEquals(listOf(16, 16, 1), chunkSizes)
        assertEquals(33L, result.inputSamples)
        assertEquals(2L, result.inputDurationMs)
        assertEquals("run-wav-001", result.runId)
    }

    private class FakeClock(var nowNanos: Long = 0L) {
        fun advanceMs(ms: Long) {
            nowNanos += ms * 1_000_000L
        }
    }

    private fun wavPcm(samples: ShortArray, sampleRate: Int = 16_000): ByteArray {
        val dataBytes = samples.size * 2
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        writeIntLe(out, 36 + dataBytes)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        writeIntLe(out, 16)
        writeShortLe(out, 1)
        writeShortLe(out, 1)
        writeIntLe(out, sampleRate)
        writeIntLe(out, sampleRate * 2)
        writeShortLe(out, 2)
        writeShortLe(out, 16)
        out.write("data".toByteArray())
        writeIntLe(out, dataBytes)
        samples.forEach { writeShortLe(out, it.toInt()) }
        return out.toByteArray()
    }

    private fun writeIntLe(out: ByteArrayOutputStream, value: Int) {
        repeat(4) { index -> out.write((value ushr (index * 8)) and 0xFF) }
    }

    private fun writeShortLe(out: ByteArrayOutputStream, value: Int) {
        repeat(2) { index -> out.write((value ushr (index * 8)) and 0xFF) }
    }
}
