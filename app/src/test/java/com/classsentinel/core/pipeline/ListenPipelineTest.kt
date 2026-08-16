package com.classsentinel.core.pipeline

import com.classsentinel.core.audio.AudioStreamer
import com.classsentinel.core.speech.SpeechEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ListenPipelineTest {

    private class FakeStreamer(val chunks: List<ShortArray>) : AudioStreamer() {
        override fun pcm(): Flow<ShortArray> = flow {
            chunks.forEach { emit(it) }
        }
    }

    private class FakeEngine(private val sentences: List<String>) : SpeechEngine {
        override val name = "fake"
        override fun transcribe(pcm: Flow<ShortArray>): Flow<String> = flow {
            pcm.collect { } // 消费完输入再出句（模拟听讲过程）
            sentences.forEach { emit(it) }
        }
    }

    @Test
    fun `sentences forwarded in order`() = runTest {
        val pipe = ListenPipeline(
            FakeStreamer(listOf(ShortArray(1600), ShortArray(1600))),
            FakeEngine(listOf("第一句", "第二句")),
        )
        val got = mutableListOf<String>()
        val job = launch { pipe.segments.collect { got += it } }
        pipe.start(this)
        advanceUntilIdle()
        assertEquals(listOf("第一句", "第二句"), got)
        assertEquals(PipelineState.Listening(2), pipe.state.value)
        job.cancel()
    }

    @Test
    fun `engine failure sets error state`() = runTest {
        val pipe = ListenPipeline(
            FakeStreamer(listOf(ShortArray(1600))),
            object : SpeechEngine {
                override val name = "boom"
                override fun transcribe(pcm: Flow<ShortArray>) = flow<String> {
                    throw IOException("boom")
                }
            },
        )
        pipe.start(this)
        advanceUntilIdle()
        assertTrue(pipe.state.value is PipelineState.Error)
    }
}
