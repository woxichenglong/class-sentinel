package com.classsentinel.core.pipeline

import com.classsentinel.core.audio.AudioStreamer
import com.classsentinel.core.speech.StreamingAsrErrorKind
import com.classsentinel.core.speech.StreamingAsrEvent
import com.classsentinel.core.speech.StreamingSpeechEngine
import com.classsentinel.core.speech.SherpaOnnxStreamingEngine
import com.classsentinel.core.speech.SherpaOnlineRecognizerPort
import com.classsentinel.core.speech.SherpaOnlineStreamPort
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StreamingListenPipelineTest {

    @Test
    fun `partial is replaceable state while final increments sentence count and is forwarded once`() = runTest {
        val speech = FakeStreamingSpeech(
            flowOf(
                StreamingAsrEvent.Partial(1, "张", 100L),
                StreamingAsrEvent.Partial(1, "张三", 200L),
                StreamingAsrEvent.Final(1, "张三你来回答这个问题", 0L, 1_200L),
            ),
        )
        val finals = mutableListOf<StreamingAsrEvent.Final>()
        val pipeline = StreamingListenPipeline(
            streamer = FakeStreamer(flowOf(ShortArray(1))),
            speech = speech,
            onFinal = { finals += it },
        )

        pipeline.start(this)
        advanceUntilIdle()

        assertEquals(1, pipeline.finalCount)
        assertEquals(listOf("张三你来回答这个问题"), finals.map { it.text })
        assertEquals("转写中断", (pipeline.state.value as PipelineState.Error).message)
    }

    @Test
    fun `repeated start creates one collector and stop releases streaming source without error`() = runTest {
        val streamer = HoldOpenStreamer()
        val speech = FakeStreamingSpeech(
            source = flowOf(StreamingAsrEvent.Partial(1, "当前句", 100L)),
            keepAliveWithPcm = true,
        )
        val pipeline = StreamingListenPipeline(
            streamer = streamer,
            speech = speech,
        )

        val first = pipeline.start(this)
        pipeline.start(this)
        pipeline.start(this)
        advanceUntilIdle()
        pipeline.stop()

        assertEquals(1, speech.collectorCount)
        assertEquals(1, speech.releaseCount)
        assertEquals(1, streamer.stopCalls)
        assertTrue(first.isCompleted)
        assertTrue(!first.isCancelled)
        assertTrue(pipeline.state.value === PipelineState.Idle)
        assertTrue(pipeline.state.value !is PipelineState.Error)
    }

    @Test
    fun `non cancellation exception becomes safe error`() = runTest {
        val pipeline = StreamingListenPipeline(
            streamer = FakeStreamer(flowOf(ShortArray(1))),
            speech = object : StreamingSpeechEngine {
                override val name = "boom"
                override fun transcribe(pcm: Flow<ShortArray>): Flow<StreamingAsrEvent> = flow {
                    throw IOException("provider body must not escape")
                }
            },
        )

        pipeline.start(this)
        advanceUntilIdle()

        assertEquals(PipelineState.Error("转写中断"), pipeline.state.value)
    }

    @Test
    fun `failed event is terminal and late events cannot restore listening state`() = runTest {
        val pipeline = StreamingListenPipeline(
            streamer = FakeStreamer(flowOf(ShortArray(1))),
            speech = object : StreamingSpeechEngine {
                override val name = "broken"

                override fun transcribe(pcm: Flow<ShortArray>): Flow<StreamingAsrEvent> = flow {
                    emit(StreamingAsrEvent.Failed(StreamingAsrErrorKind.ASR_RUNTIME))
                    emit(StreamingAsrEvent.Partial(1, "迟到事件", 100L))
                }
            },
        )

        pipeline.start(this)
        advanceUntilIdle()

        assertEquals(PipelineState.Error("转写中断"), pipeline.state.value)
    }

    @Test
    fun `cancellation is not converted to error`() = runTest {
        val pipeline = StreamingListenPipeline(
            streamer = FakeStreamer(flowOf(ShortArray(1))),
            speech = object : StreamingSpeechEngine {
                override val name = "cancel"
                override fun transcribe(pcm: Flow<ShortArray>): Flow<StreamingAsrEvent> = flow {
                    throw CancellationException("user stop")
                }
            },
        )

        val job = pipeline.start(this)
        advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertTrue(pipeline.state.value !is PipelineState.Error)
    }

    @Test
    fun `stop gracefully completes pcm and flushes the final utterance`() = runTest {
        val streamer = HoldOpenStreamer()
        val stream = FlushOnStopStream()
        val finals = mutableListOf<StreamingAsrEvent.Final>()
        val pipeline = StreamingListenPipeline(
            streamer = streamer,
            speech = SherpaOnnxStreamingEngine(
                recognizerFactory = {
                    object : SherpaOnlineRecognizerPort {
                        override fun createStream(): SherpaOnlineStreamPort = stream

                        override fun release() = Unit
                    }
                },
            ),
            onFinal = { finals += it },
        )

        pipeline.start(this)
        advanceUntilIdle()

        pipeline.stop()

        assertEquals(1, streamer.stopCalls)
        assertEquals(listOf("尾句"), finals.map { it.text })
        assertEquals(1, stream.inputFinishedCalls)
        assertEquals(1, stream.decodeCalls)
        assertTrue(pipeline.state.value === PipelineState.Idle)
    }

    @Test
    fun `natural ASR flow completion is reported as interruption rather than idle`() = runTest {
        val pipeline = StreamingListenPipeline(
            streamer = FakeStreamer(flowOf(ShortArray(1))),
            speech = FakeStreamingSpeech(flowOf(StreamingAsrEvent.Final(1, "一句", 0L, 10L))),
        )

        pipeline.start(this)
        advanceUntilIdle()

        assertEquals(PipelineState.Error("转写中断"), pipeline.state.value)
    }

    private class FakeStreamer(
        private val source: Flow<ShortArray>,
    ) : AudioStreamer(context = null) {
        override fun pcm(): Flow<ShortArray> = source
    }

    private class HoldOpenStreamer : AudioStreamer(context = null) {
        private var stopSignal = CompletableDeferred<Unit>()
        var stopCalls = 0

        override fun prepareForCapture() {
            super.prepareForCapture()
            stopSignal = CompletableDeferred()
        }

        override fun pcm(): Flow<ShortArray> = flow {
            emit(ShortArray(1) { 100 })
            stopSignal.await()
        }

        override fun stop() {
            stopCalls++
            stopSignal.complete(Unit)
        }
    }

    private class FlushOnStopStream : SherpaOnlineStreamPort {
        var inputFinishedCalls = 0
        var decodeCalls = 0
        private var readyFrames = 0

        override fun acceptWaveform(samples: FloatArray, sampleRate: Int) = Unit

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

    private class FakeStreamingSpeech(
        private val source: Flow<StreamingAsrEvent>,
        private val keepAliveWithPcm: Boolean = false,
    ) : StreamingSpeechEngine {
        override val name = "fake-streaming"
        var collectorCount = 0
        var releaseCount = 0

        override fun transcribe(pcm: Flow<ShortArray>): Flow<StreamingAsrEvent> = flow {
            collectorCount++
            try {
                source.collect { emit(it) }
                if (keepAliveWithPcm) pcm.collect { }
            } finally {
                releaseCount++
            }
        }
    }
}
