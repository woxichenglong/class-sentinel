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

    private class FakeRecognizer(
        private val fakeStream: FakeStream,
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
}
