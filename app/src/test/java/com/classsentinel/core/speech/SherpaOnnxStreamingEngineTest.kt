package com.classsentinel.core.speech

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaOnnxStreamingEngineTest {

    @Test
    fun `continuous engine normalizes pcm decodes all ready frames and emits final once`() = runTest {
        val stream = FakeStream()
        val recognizer = FakeRecognizer(stream)
        val engine = SherpaOnnxStreamingEngine(recognizerFactory = { recognizer })

        val events = engine.transcribe(
            flowOf(
                shortArrayOf(0, Short.MAX_VALUE, Short.MIN_VALUE),
                shortArrayOf(1, -1),
                shortArrayOf(100, -100),
            ),
        ).toList()

        assertEquals(3, stream.acceptedSamples.size)
        assertEquals(listOf(16_000, 16_000, 16_000), stream.acceptedRates)
        assertEquals(0f, stream.acceptedSamples[0][0], 0f)
        assertEquals(Short.MAX_VALUE / 32_768f, stream.acceptedSamples[0][1], 0f)
        assertEquals(-1f, stream.acceptedSamples[0][2], 0f)
        assertEquals("every ready frame must be decoded", 6, stream.decodeCalls)
        assertEquals(
            listOf(
                StreamingAsrEvent.Partial(1, "张", 0L),
                StreamingAsrEvent.Partial(1, "张三", 0L),
                StreamingAsrEvent.Final(1, "张三你来回答这个问题", 0L, 0L),
            ),
            events,
        )
        assertEquals(1, stream.resetCalls)
        assertEquals(1, stream.inputFinishedCalls)
        assertEquals(1, stream.releaseCalls)
        assertEquals(1, recognizer.releaseCalls)
        assertTrue(events.none { it is StreamingAsrEvent.Failed })
    }

    @Test
    fun `normal completion flushes pending utterance as final`() = runTest {
        val stream = FlushStream()
        val engine = SherpaOnnxStreamingEngine(
            recognizerFactory = { FakeRecognizer(stream) },
        )

        val events = engine.transcribe(flowOf(shortArrayOf(100))).toList()

        assertEquals(
            listOf(
                StreamingAsrEvent.Partial(1, "尾句", 0L),
                StreamingAsrEvent.Final(1, "尾句", 0L, 0L),
            ),
            events,
        )
        assertEquals(1, stream.inputFinishedCalls)
        assertEquals(1, stream.decodeCalls)
    }

    @Test
    fun `engine identity and sample rate come from model profile`() = runTest {
        val stream = FakeStream()
        val profile = ModelProfiles.ZIPFORMER_ZH_14M.copy(
            id = "test-8k-profile",
            recognizer = ModelProfiles.ZIPFORMER_ZH_14M.recognizer.copy(sampleRate = 8_000),
        )
        val engine = SherpaOnnxStreamingEngine(
            profile = profile,
            recognizerFactory = { FakeRecognizer(stream) },
        )

        engine.transcribe(flowOf(shortArrayOf(100))).toList()

        assertEquals(profile.id, engine.modelProfileId)
        assertEquals(8_000, engine.sampleRate)
        assertEquals(listOf(8_000), stream.acceptedRates)
    }

    @Test
    fun `engine reports recognizer init and decode timing separately`() = runTest {
        val clock = FakeClock()
        val stream = FlushStream { clock.advanceMs(13L) }
        val engine = SherpaOnnxStreamingEngine(
            recognizerFactory = {
                clock.advanceMs(7L)
                FakeRecognizer(stream)
            },
            nowNanos = { clock.nowNanos },
        )

        engine.transcribe(flowOf(shortArrayOf(100))).toList()

        assertEquals(7L, engine.lastReplayTimings?.recognizerInitMs)
        assertEquals(13L, engine.lastReplayTimings?.decodeElapsedMs)
    }

    @Test
    fun `decode timing excludes time waiting for the next pcm packet`() = runTest {
        val clock = FakeClock()
        val stream = FakeStream()
        val engine = SherpaOnnxStreamingEngine(
            recognizerFactory = { FakeRecognizer(stream) },
            nowNanos = { clock.nowNanos },
        )
        val pcm = kotlinx.coroutines.flow.flow {
            emit(shortArrayOf(100))
            clock.advanceMs(100L)
            emit(shortArrayOf(100))
        }

        engine.transcribe(pcm).toList()

        assertEquals(0L, engine.lastReplayTimings?.decodeElapsedMs)
    }

    private class FakeClock(var nowNanos: Long = 0L) {
        fun advanceMs(ms: Long) {
            nowNanos += ms * 1_000_000L
        }
    }

    private class FakeRecognizer(
        private val fakeStream: SherpaOnlineStreamPort,
    ) : SherpaOnlineRecognizerPort {
        var releaseCalls = 0

        override fun createStream(): SherpaOnlineStreamPort = fakeStream

        override fun release() {
            releaseCalls++
        }
    }

    private class FakeStream : SherpaOnlineStreamPort {
        val acceptedSamples = mutableListOf<FloatArray>()
        val acceptedRates = mutableListOf<Int>()
        var decodeCalls = 0
        var resetCalls = 0
        var inputFinishedCalls = 0
        var releaseCalls = 0
        private var inputCount = 0
        private var readyFrames = 0

        override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
            acceptedSamples += samples
            acceptedRates += sampleRate
            inputCount++
            readyFrames = 2
        }

        override fun isReady(): Boolean = readyFrames > 0

        override fun decode() {
            decodeCalls++
            readyFrames--
        }

        override fun resultText(): String = when (inputCount) {
            1 -> "张"
            2 -> "张三"
            else -> "张三你来回答这个问题"
        }

        override fun isEndpoint(): Boolean = inputCount == 3

        override fun reset() {
            resetCalls++
        }

        override fun inputFinished() {
            inputFinishedCalls++
        }

        override fun release() {
            releaseCalls++
        }
    }

    private class FlushStream(
        private val onAccept: () -> Unit = {},
    ) : SherpaOnlineStreamPort {
        var inputFinishedCalls = 0
        var decodeCalls = 0
        private var readyFrames = 0

        override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
            onAccept()
        }

        override fun isReady(): Boolean = readyFrames > 0

        override fun decode() {
            decodeCalls++
            readyFrames--
        }

        override fun resultText(): String = "尾句"

        override fun isEndpoint(): Boolean = false

        override fun reset() = Unit

        override fun inputFinished() {
            inputFinishedCalls++
            readyFrames = 1
        }

        override fun release() = Unit
    }
}
