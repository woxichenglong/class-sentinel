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
        val engine = object : ProfileBoundStreamingSpeechEngine {
            override val name = "fake"
            override val modelProfileId = ModelProfiles.ZIPFORMER_ZH_14M.id
            override val sampleRate = ModelProfiles.ZIPFORMER_ZH_14M.recognizer.sampleRate

            override fun transcribe(pcm: Flow<ShortArray>): Flow<StreamingAsrEvent> = flow {
                pcm.toList()
                clock.advanceMs(12L)
                emit(StreamingAsrEvent.Partial(1, "CAPM", 500L))
                clock.advanceMs(8L)
                emit(StreamingAsrEvent.Final(1, "CAPM beta", 0L, 1_000L))
            }
        }
        val runner = PcmReplayRunner(nowNanos = { clock.nowNanos })
        val preparedModel = PreparedModel.from(ModelProfiles.ZIPFORMER_ZH_14M, engine)

        val result = runner.run(
            preparedModel = preparedModel,
            gitCommitSha = "abc1234",
            runId = "run-001",
            phase = ReplayPhase.WARM,
            pcm = flowOf(ShortArray(16_000)),
        )

        assertEquals("sherpa-zh-14m", result.modelProfileId)
        assertEquals("abc1234", result.gitCommitSha)
        assertEquals("run-001", result.runId)
        assertEquals(ReplayPhase.WARM, result.phase)
        assertEquals(1_000L, result.inputDurationMs)
        assertEquals(20L, result.totalElapsedMs)
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
        val waits = mutableListOf<Long>()
        val engine = object : ProfileBoundStreamingSpeechEngine {
            override val name = "fake"
            override val modelProfileId = ModelProfiles.ZIPFORMER_ZH_14M.id
            override val sampleRate = ModelProfiles.ZIPFORMER_ZH_14M.recognizer.sampleRate

            override fun transcribe(pcm: Flow<ShortArray>): Flow<StreamingAsrEvent> = flow {
                pcm.collect { chunk -> chunkSizes += chunk.size }
                emit(StreamingAsrEvent.Final(1, "固定回放", 0L, 2L))
            }
        }
        val runner = PcmReplayRunner(
            nowNanos = { 0L },
            delayBetweenPackets = { waits += it },
        )
        val profile = ModelProfiles.ZIPFORMER_ZH_14M
        val preparedModel = PreparedModel.from(profile, engine)

        val result = runner.runWav(
            preparedModel = preparedModel,
            gitCommitSha = "abc1234",
            runId = "run-wav-001",
            phase = ReplayPhase.COLD,
            wav = ByteArrayInputStream(wavPcm(ShortArray(33) { it.toShort() })),
            config = ReplayInputConfig(inputPacketMs = 1),
        )

        assertEquals(listOf(16, 16, 1), chunkSizes)
        assertEquals(33L, result.inputSamples)
        assertEquals(2L, result.inputDurationMs)
        assertEquals("run-wav-001", result.runId)
        assertEquals(emptyList<Long>(), waits)
        assertEquals(ReplayMode.FAST, result.replayMode)
    }

    @Test
    fun `replay input packet config chunks by packet ms independently of recognizer artifact chunk`() = runTest {
        val receivedChunks = mutableListOf<Int>()
        val engine = object : ProfileBoundStreamingSpeechEngine {
            override val name = "fake"
            override val modelProfileId = ModelProfiles.ZIPFORMER_ZH_14M.id
            override val sampleRate = 1_000

            override fun transcribe(pcm: Flow<ShortArray>): Flow<StreamingAsrEvent> = flow {
                pcm.collect { chunk -> receivedChunks += chunk.size }
                emit(StreamingAsrEvent.Final(1, "分组回放", 0L, 2L))
            }
        }
        val runner = PcmReplayRunner(nowNanos = { 0L })

        // Future API: recognizer opts into artifact streaming at 960-sample chunks; the
        // independent ReplayInputConfig packet must win over this recognizer-level chunking.
        val baseProfile = ModelProfiles.ZIPFORMER_ZH_14M
        val profile = baseProfile.copy(recognizer = baseProfile.recognizer.copy(artifactStreamingChunkMs = 960, sampleRate = 1_000))
        val preparedModel = PreparedModel.from(profile, engine)

        val result = runner.runWav(
            preparedModel = preparedModel,
            gitCommitSha = "abc1234",
            runId = "run-wav-packet-100",
            phase = ReplayPhase.COLD,
            wav = ByteArrayInputStream(wavPcm(ShortArray(250) { it.toShort() }, sampleRate = 1_000)),
            config = ReplayInputConfig(inputPacketMs = 100),
        )

        // 1000Hz * 100ms = 100 samples/packet; 250 samples => 100+100+50, not one 250-sample recognizer chunk.
        assertEquals(listOf(100, 100, 50), receivedChunks)
        assertEquals(100, result.inputPacketMs)
        assertEquals(250L, result.inputSamples)
    }

    @Test
    fun `realtime wav replay waits between transport packets`() = runTest {
        val clock = FakeClock()
        val waits = mutableListOf<Long>()
        val engine = object : ProfileBoundStreamingSpeechEngine {
            override val name = "fake"
            override val modelProfileId = ModelProfiles.ZIPFORMER_ZH_14M.id
            override val sampleRate = 1_000

            override fun transcribe(pcm: Flow<ShortArray>): Flow<StreamingAsrEvent> = flow {
                val processingMs = listOf(35L, 120L, 0L)
                var index = 0
                pcm.collect {
                    clock.advanceMs(processingMs[index++])
                }
                emit(StreamingAsrEvent.Final(1, "实时回放", 0L, 250L))
            }
        }
        val runner = PcmReplayRunner(
            nowNanos = { clock.nowNanos },
            delayBetweenPackets = {
                waits += it
                clock.advanceMs(it)
            },
        )

        val profile = ModelProfiles.ZIPFORMER_ZH_14M.copy(
            recognizer = ModelProfiles.ZIPFORMER_ZH_14M.recognizer.copy(sampleRate = 1_000),
        )
        val preparedModel = PreparedModel.from(profile, engine)
        val result = runner.runWav(
            preparedModel = preparedModel,
            gitCommitSha = "abc1234",
            runId = "run-wav-realtime",
            phase = ReplayPhase.WARM,
            wav = ByteArrayInputStream(wavPcm(ShortArray(250), sampleRate = 1_000)),
            config = ReplayInputConfig(inputPacketMs = 100, mode = ReplayMode.REALTIME),
        )

        assertEquals(listOf(65L), waits)
        assertEquals(ReplayMode.REALTIME, result.replayMode)
        assertEquals(100, result.inputPacketMs)
    }

    @Test
    fun `direct realtime pcm uses absolute audio time from cumulative samples`() = runTest {
        val clock = FakeClock()
        val waits = mutableListOf<Long>()
        val receivedChunks = mutableListOf<Int>()
        val engine = object : ProfileBoundStreamingSpeechEngine {
            override val name = "fake"
            override val modelProfileId = ModelProfiles.ZIPFORMER_ZH_14M.id
            override val sampleRate = 1_000

            override fun transcribe(pcm: Flow<ShortArray>): Flow<StreamingAsrEvent> = flow {
                val processingMs = listOf(35L, 20L, 0L)
                var index = 0
                pcm.collect { chunk ->
                    receivedChunks += chunk.size
                    clock.advanceMs(processingMs[index++])
                }
                emit(StreamingAsrEvent.Final(1, "直接回放", 0L, 250L))
            }
        }
        val profile = ModelProfiles.ZIPFORMER_ZH_14M.copy(
            recognizer = ModelProfiles.ZIPFORMER_ZH_14M.recognizer.copy(sampleRate = 1_000),
        )
        val runner = PcmReplayRunner(
            nowNanos = { clock.nowNanos },
            delayBetweenPackets = {
                waits += it
                clock.advanceMs(it)
            },
        )

        val result = runner.run(
            preparedModel = PreparedModel.from(profile, engine),
            gitCommitSha = "abc1234",
            runId = "run-direct-realtime",
            phase = ReplayPhase.WARM,
            pcm = flowOf(
                ShortArray(100),
                ShortArray(50),
                ShortArray(100),
            ),
            config = ReplayInputConfig(inputPacketMs = 100, mode = ReplayMode.REALTIME),
        )

        assertEquals(listOf(100, 50, 100), receivedChunks)
        assertEquals(listOf(65L, 30L), waits)
        assertEquals(250L, result.inputSamples)
        assertEquals(250L, result.inputDurationMs)
    }

    @Test
    fun `runner separates recognizer init decode and total elapsed timings`() = runTest {
        val clock = FakeClock()
        val engine = object : ProfileBoundStreamingSpeechEngine, ReplayTimingSource {
            override val name = "timed-fake"
            override val modelProfileId = ModelProfiles.ZIPFORMER_ZH_14M.id
            override val sampleRate = ModelProfiles.ZIPFORMER_ZH_14M.recognizer.sampleRate
            override val lastReplayTimings = StreamingAsrTimings(
                recognizerInitMs = 7L,
                decodeElapsedMs = 13L,
            )

            override fun transcribe(pcm: Flow<ShortArray>): Flow<StreamingAsrEvent> = flow {
                pcm.toList()
                clock.advanceMs(20L)
                emit(StreamingAsrEvent.Final(1, "计时", 0L, 1_000L))
            }
        }
        val result = PcmReplayRunner(nowNanos = { clock.nowNanos }).run(
            preparedModel = PreparedModel.from(ModelProfiles.ZIPFORMER_ZH_14M, engine),
            gitCommitSha = "abc1234",
            runId = "run-timing-001",
            phase = ReplayPhase.STEADY,
            pcm = flowOf(ShortArray(16_000)),
        )

        assertEquals(7L, result.recognizerInitMs)
        assertEquals(13L, result.decodeElapsedMs)
        assertEquals(20L, result.totalElapsedMs)
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
