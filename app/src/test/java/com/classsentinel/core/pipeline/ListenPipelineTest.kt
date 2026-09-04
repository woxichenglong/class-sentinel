package com.classsentinel.core.pipeline

import android.content.ContextWrapper
import com.classsentinel.core.audio.AudioStreamer
import com.classsentinel.core.speech.SpeechEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ListenPipelineTest {

    private class FakeStreamer(val chunks: List<ShortArray>) : AudioStreamer(context = ContextWrapper(null)) {
        override fun pcm(): Flow<ShortArray> = flow {
            chunks.forEach { emit(it) }
        }
    }

    private class FakeEngine(private val sentences: List<String>) : SpeechEngine {
        override val name = "fake"
        override fun transcribe(pcm: Flow<ShortArray>): Flow<String> = flow {
            pcm.collect { } // 消费完输入再出句（模拟听讲过程）
            sentences.forEach { s ->
                emit(s)
                yield() // 让状态观察者有机会观察到每次 Listening（模拟真实引擎的 IO 挂起）
            }
        }
    }

    @Test
    fun `sentences forwarded in order`() = runTest {
        val pipe = ListenPipeline(
            FakeStreamer(listOf(ShortArray(1600), ShortArray(1600))),
            FakeEngine(listOf("第一句", "第二句")),
            nowMillis = { 1_000L },
        )
        val got = mutableListOf<String>()
        val states = mutableListOf<PipelineState>()
        val job = launch {
            pipe.segments.collect { got += it }
        }
        val stateJob = launch { pipe.state.collect { states += it } }
        pipe.start(this)
        advanceUntilIdle()
        assertEquals(listOf("第一句", "第二句"), got)
        // 每句发布一次 Listening，最后正常结束回 Idle
        assertTrue(PipelineState.Listening(2, "fake", 0L, 0) in states)
        assertEquals(PipelineState.Idle, pipe.state.value)
        job.cancel()
        stateJob.cancel()
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

    @Test
    fun `start publishes Starting synchronously before collecting`() = runTest {
        val pipe = ListenPipeline(
            FakeStreamer(emptyList()),
            FakeEngine(emptyList()),
        )
        assertEquals(PipelineState.Idle, pipe.state.value)
        pipe.start(this)
        // 启动后、虚拟时间推进前：collector 尚未 emit 任何句子 → 只能是 Starting 或更早状态
        assertTrue(
            "expected Starting or Idle, got ${pipe.state.value}",
            pipe.state.value == PipelineState.Starting || pipe.state.value == PipelineState.Idle,
        )
        advanceUntilIdle()
        assertEquals(PipelineState.Idle, pipe.state.value)
    }

    @Test
    fun `repeated start does not create a second collector`() = runTest {
        val pipe = ListenPipeline(
            FakeStreamer(listOf(ShortArray(1600))),
            FakeEngine(listOf("句一")),
            nowMillis = { 1_000L },
        )
        val states = mutableListOf<PipelineState>()
        val stateJob = launch { pipe.state.collect { states += it } }
        pipe.start(this)
        pipe.start(this)
        pipe.start(this)
        advanceUntilIdle()
        // 单 collector：整批只发布一次 Listening
        val listenings = states.filterIsInstance<PipelineState.Listening>()
        assertEquals(1, listenings.size)
        assertEquals(PipelineState.Listening(1, "fake", 0L, 0), listenings.single())
        assertEquals(PipelineState.Idle, pipe.state.value)
        stateJob.cancel()
    }

    @Test
    fun `Listening carries engine name elapsed time and pending segments`() = runTest {
        var now = 1_000L
        val engine = object : SpeechEngine {
            override val name = "clocky"
            override fun transcribe(pcm: Flow<ShortArray>) = flow {
                pcm.collect { }
                emit("一")
                yield() // 让状态观察者观察到 Listening(1)
                now += 150 // 注入时钟推进（模拟真实时间流逝）
                emit("二")
                yield() // 让状态观察者观察到 Listening(2)
            }
        }
        val pipe = ListenPipeline(
            FakeStreamer(listOf(ShortArray(1600))),
            engine,
            nowMillis = { now },
        )
        val states = mutableListOf<PipelineState>()
        val job = launch { pipe.state.collect { states += it } }
        advanceUntilIdle() // 让状态收集器先挂上并收到初始 Idle
        pipe.start(this)
        advanceUntilIdle()

        val listenings = states.filterIsInstance<PipelineState.Listening>()
        assertEquals(2, listenings.size)
        assertEquals(listOf(1, 2), listenings.map { it.sentences })
        assertTrue(listenings.all { it.engine == "clocky" })
        assertTrue(listenings.all { it.pendingSegments == 0 })
        // elapsed 以 start 时刻为锚点，随注入时钟增长且不倒退
        assertEquals(0L, listenings[0].elapsedMs)
        assertEquals(150L, listenings[1].elapsedMs)
        job.cancel()
    }

    @Test
    fun `CancellationException propagates and does not become Error`() = runTest {
        val pipe = ListenPipeline(
            FakeStreamer(listOf(ShortArray(1600))),
            object : SpeechEngine {
                override val name = "cancel"
                override fun transcribe(pcm: Flow<ShortArray>) = flow<String> {
                    throw CancellationException("人工取消")
                }
            },
        )
        val job = pipe.start(this)
        advanceUntilIdle()
        assertTrue("state must not be Error, got ${pipe.state.value}", pipe.state.value !is PipelineState.Error)
        // 取消必须向上传播：start 返回的 Job 应处于取消状态
        assertTrue(job.isCancelled)
    }

    @Test
    fun `stop cancels active collection and returns to Idle`() = runTest {
        val neverDone = MutableSharedFlow<String>(extraBufferCapacity = 0)
        val pipe = ListenPipeline(
            FakeStreamer(listOf(ShortArray(1600))),
            object : SpeechEngine {
                override val name = "hang"
                override fun transcribe(pcm: Flow<ShortArray>): Flow<String> = neverDone
            },
        )
        val job = pipe.start(this)
        assertTrue(
            "expected Starting or Listening, got ${pipe.state.value}",
            pipe.state.value is PipelineState.Starting || pipe.state.value is PipelineState.Listening,
        )
        pipe.stop()
        assertTrue(job.isCancelled)
        assertEquals(PipelineState.Idle, pipe.state.value)
    }

    @Test
    fun `stop when not started is a no-op`() = runTest {
        val pipe = ListenPipeline(FakeStreamer(emptyList()), FakeEngine(emptyList()))
        pipe.stop()
        assertEquals(PipelineState.Idle, pipe.state.value)
    }

    @Test
    fun `repeated stop is idempotent`() = runTest {
        val neverDone = MutableSharedFlow<String>(extraBufferCapacity = 0)
        val pipe = ListenPipeline(
            FakeStreamer(listOf(ShortArray(1600))),
            object : SpeechEngine {
                override val name = "hang"
                override fun transcribe(pcm: Flow<ShortArray>): Flow<String> = neverDone
            },
        )
        pipe.start(this)
        pipe.stop()
        pipe.stop()
        pipe.stop()
        assertEquals(PipelineState.Idle, pipe.state.value)
    }

    @Test
    fun `stop after natural completion is a no-op`() = runTest {
        val pipe = ListenPipeline(
            FakeStreamer(listOf(ShortArray(1600))),
            FakeEngine(listOf("一句")),
        )
        pipe.start(this)
        advanceUntilIdle()
        assertEquals(PipelineState.Idle, pipe.state.value)
        pipe.stop()
        assertEquals(PipelineState.Idle, pipe.state.value)
    }

    @Test
    fun `Starting is published before collector launch so immediate completion is not overwritten`() =
        runTest(UnconfinedTestDispatcher()) {
            val pipe = ListenPipeline(
                FakeStreamer(emptyList()),
                FakeEngine(emptyList()),
            )
            assertEquals(PipelineState.Idle, pipe.state.value)
            pipe.start(this)
            // Unconfined：collector 在 start() 返回前已同步跑完空输入并回到 Idle；
            // 若 Starting 在 launch 之后才发布，就会迟到覆盖 Idle。
            assertEquals(PipelineState.Idle, pipe.state.value)
            advanceUntilIdle()
            assertEquals(PipelineState.Idle, pipe.state.value)
        }

    @Test
    fun `immediate collector never observes Starting after Listening or Idle`() =
        runTest(UnconfinedTestDispatcher()) {
            val pipe = ListenPipeline(
                FakeStreamer(listOf(ShortArray(1600))),
                FakeEngine(listOf("一句")),
            )
            val states = mutableListOf<PipelineState>()
            val stateJob = launch { pipe.state.collect { states += it } }
            pipe.start(this)
            advanceUntilIdle()
            assertEquals(PipelineState.Idle, pipe.state.value)
            val observed = states.drop(1) // 去掉订阅时的初始 Idle
            val firstReal =
                observed.indexOfFirst { it == PipelineState.Idle || it is PipelineState.Listening }
            val lastStarting = observed.lastIndexOf(PipelineState.Starting)
            assertTrue(
                "late Starting must not overwrite real state, observed=$observed",
                lastStarting < firstReal,
            )
            stateJob.cancel()
        }

    @Test
    fun `state observer receives startup and terminal transitions before first segment`() = runTest {
        val states = mutableListOf<PipelineState>()
        val pipe = ListenPipeline(
            FakeStreamer(emptyList()),
            FakeEngine(emptyList()),
            onStateChanged = { states += it },
        )

        pipe.start(this)
        advanceUntilIdle()

        assertEquals(listOf(PipelineState.Starting, PipelineState.Idle), states)
    }
}
