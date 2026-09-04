package com.classsentinel.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2b-1a/1b/1b-2：ListenSessionController 并发 start 幂等 + 成功 stop/finalize
 * + start 失败补偿收尾。纯 Kotlin 单测，不触碰 Room / Service / 网络 / 凭证。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ListenSessionControllerTest {

    private class FakeStore(
        private val events: MutableList<String> = mutableListOf(),
    ) : CourseSessionStore {
        var createCount = 0
        val createOrder = mutableListOf<Int>()
        var finalizeCount = 0
        val finalizeCalls = mutableListOf<Pair<Long, Long>>()

        override suspend fun createCourse(): Long {
            createCount++
            createOrder += createCount
            events.add("create")
            return 42L
        }

        override suspend fun finalizeCourse(courseId: Long, endTs: Long) {
            finalizeCount++
            finalizeCalls += courseId to endTs
            events.add("finalize")
        }
    }

    private class FakePipeline(
        private val events: MutableList<String> = mutableListOf(),
    ) : SessionPipeline {
        val gate = CompletableDeferred<Unit>()
        var startCount = 0
        var stopCount = 0

        override suspend fun start() {
            startCount++
            events.add("start")
            gate.await()
        }

        override suspend fun stop() {
            stopCount++
            events.add("pipeline.stop")
        }
    }

    private class FailingStore : CourseSessionStore {
        override suspend fun createCourse(): Long {
            // 普通异常，message 携带敏感标记（凭证/用户内容模拟）。
            // 该标记绝不允许进入 SessionState，也不得出现在测试输出中。
            throw RuntimeException("SENSITIVE-TOKEN-7f3a9c provider detail")
        }

        override suspend fun finalizeCourse(courseId: Long, endTs: Long) = Unit
    }

    private class FailingStartPipeline(
        private val events: MutableList<String> = mutableListOf(),
    ) : SessionPipeline {
        var startCount = 0
        var stopCount = 0

        override suspend fun start() {
            startCount++
            events.add("start")
            throw RuntimeException("SENSITIVE-TOKEN-7f3a9c start failure detail")
        }

        override suspend fun stop() {
            stopCount++
            events.add("pipeline.stop")
        }
    }

    private class FailingFinalizeStore(
        private val events: MutableList<String> = mutableListOf(),
    ) : CourseSessionStore {
        var createCount = 0
        var finalizeCount = 0

        override suspend fun createCourse(): Long {
            createCount++
            events.add("create")
            return 42L
        }

        override suspend fun finalizeCourse(courseId: Long, endTs: Long) {
            finalizeCount++
            events.add("finalize")
            throw RuntimeException("SENSITIVE-TOKEN-7f3a9c finalize failure detail")
        }
    }

    private class FailingCreateStore : CourseSessionStore {
        override suspend fun createCourse(): Long {
            throw RuntimeException("SENSITIVE-TOKEN-7f3a9c create failure detail")
        }

        override suspend fun finalizeCourse(courseId: Long, endTs: Long) {
            throw AssertionError("finalizeCourse must NOT be called when createCourse failed")
        }
    }

    @Test
    fun `ordinary exception from store yields Error with fixed safe message`() = runTest {
        val controller = ListenSessionController(FailingStore(), FakePipeline())
        val ok = controller.start()

        assertTrue(!ok)
        val state = controller.state.value
        assertTrue(state is SessionState.Error)
        assertEquals("课程启动失败", (state as SessionState.Error).message)
        assertTrue(!state.message.contains("SENSITIVE-TOKEN-7f3a9c"))
        assertTrue(!state.message.contains("provider detail"))
    }

    @Test
    fun `five concurrent starts create one course and start pipeline once`() = runTest {
        val store = FakeStore()
        val pipeline = FakePipeline()
        val controller = ListenSessionController(store, pipeline)
        val results = mutableListOf<Boolean>()

        coroutineScope {
            repeat(5) {
                launch(UnconfinedTestDispatcher(testScheduler)) {
                    results += controller.start()
                }
            }
            // Unconfined 下 launch 体立即执行到首个挂起点：
            // 赢家已把 Running 记录好并挂在 pipeline.start() 上，其余 4 个立即返回 false。
            assertEquals(1, store.createCount)
            assertEquals(1, pipeline.startCount)
            assertEquals(SessionState.Running(42L), controller.state.value)
            pipeline.gate.complete(Unit)
        }

        assertEquals(1, results.count { it })
        assertEquals(4, results.count { !it })
        assertTrue(store.createOrder == listOf(1))
        assertEquals(SessionState.Running(42L), controller.state.value)
    }

    @Test
    fun `successful stop stops pipeline then finalizes once and returns to Idle`() = runTest {
        val events = mutableListOf<String>()
        val store = FakeStore(events)
        val pipeline = FakePipeline(events)
        val controller = ListenSessionController(store, pipeline, nowMillis = { 1_700_000_000_000L })

        pipeline.gate.complete(Unit)
        assertTrue(controller.start())
        assertEquals(SessionState.Running(42L), controller.state.value)

        val ok = controller.stop()

        assertTrue(ok)
        assertEquals(1, pipeline.stopCount)
        assertEquals(1, store.finalizeCount)
        assertEquals(listOf(42L to 1_700_000_000_000L), store.finalizeCalls)
        // 严格顺序：pipeline.stop 先于 store.finalizeCourse。
        assertEquals(listOf("create", "start", "pipeline.stop", "finalize"), events)
        assertEquals(SessionState.Idle, controller.state.value)
    }

    @Test
    fun `course finalized hook runs only after durable finalize`() = runTest {
        val events = mutableListOf<String>()
        val store = FakeStore(events)
        val pipeline = FakePipeline(events)
        val controller = ListenSessionController(
            store = store,
            pipeline = pipeline,
            onCourseFinalized = { events.add("summary") },
        )

        pipeline.gate.complete(Unit)
        assertTrue(controller.start())
        assertTrue(controller.stop())

        assertEquals(listOf("create", "start", "pipeline.stop", "finalize", "summary"), events)
    }

    @Test
    fun `stop samples endTs from injected clock at stop time`() = runTest {
        var now = 1_000L
        val store = FakeStore()
        val pipeline = FakePipeline()
        val controller = ListenSessionController(store, pipeline, nowMillis = { now })

        pipeline.gate.complete(Unit)
        assertTrue(controller.start())
        now = 2_000L

        assertTrue(controller.stop())

        // endTs 是 stop 时刻采样的 nowMillis，而非 start 时的值。
        assertEquals(listOf(42L to 2_000L), store.finalizeCalls)
        assertEquals(SessionState.Idle, controller.state.value)
    }

    @Test
    fun `repeated stop after success calls nothing and returns false`() = runTest {
        val store = FakeStore()
        val pipeline = FakePipeline()
        val controller = ListenSessionController(store, pipeline)

        pipeline.gate.complete(Unit)
        assertTrue(controller.start())
        assertTrue(controller.stop())

        val stopCountAfterFirst = pipeline.stopCount
        val finalizeCountAfterFirst = store.finalizeCount

        assertFalse(controller.stop())
        assertFalse(controller.stop())

        assertEquals(stopCountAfterFirst, pipeline.stopCount)
        assertEquals(finalizeCountAfterFirst, store.finalizeCount)
        assertEquals(SessionState.Idle, controller.state.value)
    }

    @Test
    fun `stop retries pipeline stop after ordinary failure and finalizes once`() = runTest {
        val events = mutableListOf<String>()
        val store = FakeStore(events)
        val pipeline = object : SessionPipeline {
            var stopCount = 0
            override suspend fun start() {
                events.add("start")
            }
            override suspend fun stop() {
                stopCount++
                events.add("pipeline.stop")
                if (stopCount == 1) {
                    // 普通异常，message 携带敏感标记（凭证/用户内容模拟），不得进入状态。
                    throw RuntimeException("SENSITIVE-TOKEN-7f3a9c stop failure detail")
                }
            }
        }
        val controller = ListenSessionController(store, pipeline, nowMillis = { 1_700_000_000_000L })

        assertTrue(controller.start())
        assertEquals(SessionState.Running(42L), controller.state.value)

        // 第一次 stop：pipeline.stop 抛普通异常 → false + 安全 Error，active course 保留。
        val ok1 = controller.stop()
        assertFalse(ok1)
        val state1 = controller.state.value
        assertTrue(state1 is SessionState.Error)
        assertEquals("课程停止失败", (state1 as SessionState.Error).message)
        assertTrue(!state1.message.contains("SENSITIVE-TOKEN-7f3a9c"))
        assertTrue(!state1.message.contains("stop failure detail"))
        assertEquals(1, pipeline.stopCount)
        assertEquals(0, store.finalizeCount) // pipeline.stop 失败时不得提前 finalize

        // 第二次 stop 重试：再次调用 pipeline.stop，成功后 finalize 恰好一次 → Idle。
        val ok2 = controller.stop()
        assertTrue(ok2)
        assertEquals(2, pipeline.stopCount)
        assertEquals(1, store.finalizeCount)
        assertEquals(listOf(42L to 1_700_000_000_000L), store.finalizeCalls)
        assertEquals(listOf("create", "start", "pipeline.stop", "pipeline.stop", "finalize"), events)
        assertEquals(SessionState.Idle, controller.state.value)
    }

    @Test
    fun `stop retries finalize only after pipeline stop already succeeded`() = runTest {
        val events = mutableListOf<String>()
        val store = object : CourseSessionStore {
            var createCount = 0
            var finalizeCount = 0
            override suspend fun createCourse(): Long {
                createCount++
                events.add("create")
                return 42L
            }
            override suspend fun finalizeCourse(courseId: Long, endTs: Long) {
                finalizeCount++
                events.add("finalize")
                if (finalizeCount == 1) {
                    // 普通异常，message 携带敏感标记（凭证/用户内容模拟），不得进入状态。
                    throw RuntimeException("SENSITIVE-TOKEN-7f3a9c finalize failure detail")
                }
            }
        }
        val pipeline = FakePipeline(events) // pipeline.stop 成功
        val controller = ListenSessionController(store, pipeline, nowMillis = { 1_700_000_000_000L })

        pipeline.gate.complete(Unit)
        assertTrue(controller.start())

        // 第一次 stop：pipeline.stop 成功、finalize 抛普通异常 → false + 安全 Error。
        val ok1 = controller.stop()
        assertFalse(ok1)
        val state1 = controller.state.value
        assertTrue(state1 is SessionState.Error)
        assertEquals("课程停止失败", (state1 as SessionState.Error).message)
        assertTrue(!state1.message.contains("SENSITIVE-TOKEN-7f3a9c"))
        assertTrue(!state1.message.contains("finalize failure detail"))
        assertEquals(1, pipeline.stopCount)

        // 第二次 stop：不重复已成功的 pipeline.stop，只重试 finalize，最终恰好一次成功 → Idle。
        val ok2 = controller.stop()
        assertTrue(ok2)
        assertEquals(1, pipeline.stopCount) // pipeline.stop 恰好一次，绝不重复
        assertEquals(2, store.finalizeCount) // finalize 共调用两次：第一次失败，第二次成功
        assertEquals(listOf("create", "start", "pipeline.stop", "finalize", "finalize"), events)
        assertEquals(SessionState.Idle, controller.state.value)
    }

    @Test
    fun `stop with no active session calls nothing and returns false`() = runTest {
        val store = FakeStore()
        val pipeline = FakePipeline()
        val controller = ListenSessionController(store, pipeline)

        assertFalse(controller.stop())

        assertEquals(0, pipeline.stopCount)
        assertEquals(0, store.finalizeCount)
        assertEquals(SessionState.Idle, controller.state.value)
    }

    @Test
    fun `start failure after course created finalizes once and never stops pipeline`() = runTest {
        val events = mutableListOf<String>()
        val store = FakeStore(events)
        val pipeline = FailingStartPipeline(events)
        val controller = ListenSessionController(store, pipeline, nowMillis = { 1_700_000_000_000L })

        val ok = controller.start()

        assertFalse(ok)
        assertEquals(1, store.createCount)
        assertEquals(1, pipeline.startCount)
        assertEquals(0, pipeline.stopCount)
        assertEquals(1, store.finalizeCount)
        assertEquals(listOf(42L to 1_700_000_000_000L), store.finalizeCalls)
        // 严格顺序：start 尝试先发生，finalize 恰好一次补偿收尾，绝无 pipeline.stop。
        assertEquals(listOf("create", "start", "finalize"), events)
        val state = controller.state.value
        assertTrue(state is SessionState.Error)
        assertEquals("课程启动失败", (state as SessionState.Error).message)
        assertTrue(!state.message.contains("SENSITIVE-TOKEN-7f3a9c"))
        assertTrue(!state.message.contains("start failure detail"))
    }

    @Test
    fun `start failure after course created is retryable with a fresh course`() = runTest {
        val store = FakeStore()
        val pipeline = FailingStartPipeline()
        val controller = ListenSessionController(store, pipeline)

        assertFalse(controller.start())
        assertEquals(1, store.finalizeCount)
        assertEquals(SessionState.Error("课程启动失败"), controller.state.value)

        // Error 状态允许再次 start：新一轮 create → start 失败 → finalize 补偿。
        assertFalse(controller.start())

        assertEquals(2, store.createCount)
        assertEquals(2, pipeline.startCount)
        assertEquals(2, store.finalizeCount)
        assertEquals(SessionState.Error("课程启动失败"), controller.state.value)
    }

    @Test
    fun `finalize failure still yields safe Error and does not leak exception text`() = runTest {
        val events = mutableListOf<String>()
        val store = FailingFinalizeStore(events)
        val pipeline = FailingStartPipeline(events)
        val controller = ListenSessionController(store, pipeline, nowMillis = { 1_700_000_000_000L })

        val ok = controller.start()

        assertFalse(ok)
        assertEquals(1, store.finalizeCount)
        val state = controller.state.value
        assertTrue(state is SessionState.Error)
        assertEquals("课程启动失败", (state as SessionState.Error).message)
        assertTrue(!state.message.contains("SENSITIVE-TOKEN-7f3a9c"))
        assertTrue(!state.message.contains("finalize failure detail"))
    }

    @Test
    fun `create failure does not finalize and yields safe Error`() = runTest {
        val store = FailingCreateStore()
        val pipeline = FakePipeline()
        val controller = ListenSessionController(store, pipeline)

        val ok = controller.start()

        assertFalse(ok)
        assertEquals(0, pipeline.startCount)
        val state = controller.state.value
        assertTrue(state is SessionState.Error)
        assertEquals("课程启动失败", (state as SessionState.Error).message)
        assertTrue(!state.message.contains("SENSITIVE-TOKEN-7f3a9c"))
    }

    @Test
    fun `cancellation during start still propagates but finalizes created course once`() = runTest {
        val events = mutableListOf<String>()
        val store = FakeStore(events)
        val pipeline = object : SessionPipeline {
            override suspend fun start() {
                events.add("start")
                throw CancellationException("start cancelled")
            }

            override suspend fun stop() {
                events.add("pipeline.stop")
            }
        }
        val controller = ListenSessionController(store, pipeline, nowMillis = { 1_700_000_000_000L })

        val thrown = try {
            controller.start()
            null
        } catch (e: CancellationException) {
            e
        }

        assertTrue("CancellationException must propagate", thrown != null)
        assertEquals(1, store.createCount)
        assertEquals(1, store.finalizeCount)
        assertEquals(listOf(42L to 1_700_000_000_000L), store.finalizeCalls)
        assertEquals(listOf("create", "start", "finalize"), events)
    }

    @Test
    fun `cancellation during start resets to Idle and allows a fresh start`() = runTest {
        val events = mutableListOf<String>()
        val store = FakeStore(events)
        val pipeline = object : SessionPipeline {
            var startCount = 0
            override suspend fun start() {
                startCount++
                events.add("start")
                if (startCount == 1) throw CancellationException("start cancelled")
            }

            override suspend fun stop() {
                events.add("pipeline.stop")
            }
        }
        val controller = ListenSessionController(store, pipeline, nowMillis = { 1_700_000_000_000L })

        val thrown = try {
            controller.start()
            null
        } catch (e: CancellationException) {
            e
        }

        assertTrue("CancellationException must propagate", thrown != null)
        assertEquals("start cancelled", thrown?.message)
        assertEquals(1, store.createCount)
        assertEquals(1, store.finalizeCount)
        assertEquals(listOf(42L to 1_700_000_000_000L), store.finalizeCalls)
        // 取消清理后最终状态必须回到 Idle（而不是停在 Starting）。
        assertEquals(SessionState.Idle, controller.state.value)

        // 之后可再次 start 新会话。
        assertTrue(controller.start())
        assertEquals(2, store.createCount)
        assertEquals(SessionState.Running(42L), controller.state.value)
    }

    @Test
    fun `cancellation during stop propagates, finalizes current course once and resets to Idle`() = runTest {
        val events = mutableListOf<String>()
        val store = FakeStore(events)
        val pipeline = object : SessionPipeline {
            val gate = CompletableDeferred<Unit>()
            var startCount = 0
            var stopCount = 0
            override suspend fun start() {
                startCount++
                events.add("start")
                gate.await()
            }

            override suspend fun stop() {
                stopCount++
                events.add("pipeline.stop")
                if (stopCount == 1) throw CancellationException("stop cancelled")
            }
        }
        val controller = ListenSessionController(store, pipeline, nowMillis = { 1_700_000_000_000L })

        pipeline.gate.complete(Unit)
        assertTrue(controller.start())
        assertEquals(SessionState.Running(42L), controller.state.value)

        val thrown = try {
            controller.stop()
            null
        } catch (e: CancellationException) {
            e
        }

        assertTrue("CancellationException must propagate", thrown != null)
        assertEquals("stop cancelled", thrown?.message)
        // 第一次 pipeline.stop 抛取消；NonCancellable 清理阶段按成功标志重试该阶段后成功。
        assertEquals(2, pipeline.stopCount)
        // 取消路径在 NonCancellable 中对当前 course finalize 恰好一次。
        assertEquals(1, store.finalizeCount)
        assertEquals(listOf(42L to 1_700_000_000_000L), store.finalizeCalls)
        assertEquals(SessionState.Idle, controller.state.value)

        // 取消清理后可以再次 start 新会话。
        assertTrue(controller.start())
        assertEquals(2, store.createCount)
        assertEquals(SessionState.Running(42L), controller.state.value)
    }

    @Test
    fun `stop during Starting returns handled and cancels the in-flight start`() = runTest {
        val createEntered = CompletableDeferred<Unit>()
        val startResult = CompletableDeferred<Result<Boolean>>()
        val store = object : CourseSessionStore {
            var createCount = 0
            var finalizeCount = 0
            override suspend fun createCourse(): Long {
                createCount++
                createEntered.complete(Unit)
                awaitCancellation()
            }

            override suspend fun finalizeCourse(courseId: Long, endTs: Long) {
                finalizeCount++
            }
        }
        val pipeline = object : SessionPipeline {
            var startCount = 0
            var stopCount = 0
            override suspend fun start() {
                startCount++
            }

            override suspend fun stop() {
                stopCount++
            }
        }
        val controller = ListenSessionController(store, pipeline)

        // 在子 job（Unconfined）中启动 start：createCourse 挂起在 awaitCancellation()，
        // 观察窗口落在 "active id 尚未产生、state=Starting" 的竞态区间。
        val startJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                startResult.complete(Result.success(controller.start()))
            } catch (e: CancellationException) {
                startResult.complete(Result.failure(e))
            }
        }
        createEntered.await()
        assertEquals(SessionState.Starting, controller.state.value)
        assertEquals(1, store.createCount)

        try {
            // 目标语义：stop 必须被处理（true）并取消仍挂起在 createCourse 中的 start。
            // 当前生产实现 stop 在 Starting 直接返回 false —— 本断言即 RED 点。
            val stopHandled = controller.stop()
            assertTrue("stop during Starting must be handled", stopHandled)

            // start 必须被取消，并原样收到 CancellationException（不得被吞成 false/Error）。
            val outcome = startResult.await()
            assertTrue(
                "start job must be cancelled with CancellationException",
                outcome.exceptionOrNull() is CancellationException,
            )
        } finally {
            // 旧实现（stop=false）下 start 仍挂在 awaitCancellation()：仅在此清理阶段
            // 取消并回收 start job，保证任何实现下测试都不会留下挂起的活跃协程。
            if (startJob.isActive) {
                startJob.cancel()
                try {
                    startJob.join()
                } catch (e: CancellationException) {
                    // join 已取消 job 时的正常重抛，清理路径直接忽略。
                }
            }
        }

        assertEquals(SessionState.Idle, controller.state.value)
        assertEquals(0, pipeline.startCount)
        assertEquals(0, pipeline.stopCount)
        assertEquals(0, store.finalizeCount)
    }

    @Test
    fun `stop handles Starting after atomic start job publication`() = runTest {
        val startingPublished = CompletableDeferred<Unit>()
        val releaseStart = CompletableDeferred<Unit>()
        val startOutcome = CompletableDeferred<Throwable?>()
        val store = FakeStore()
        val pipeline = FakePipeline()
        val controller = ListenSessionController(store, pipeline).apply {
            onStartingPublished = {
                startingPublished.complete(Unit)
                releaseStart.await()
            }
        }
        val startJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                controller.start()
                startOutcome.complete(null)
            } catch (e: CancellationException) {
                startOutcome.complete(e)
            }
        }

        startingPublished.await()
        try {
            assertEquals(SessionState.Starting, controller.state.value)
            assertTrue("stop during Starting must be handled", controller.stop())

            assertTrue("start must complete with CancellationException", startOutcome.await() is CancellationException)
            assertEquals(SessionState.Idle, controller.state.value)
            assertEquals(0, pipeline.startCount)
            assertEquals(0, pipeline.stopCount)
            assertEquals(0, store.finalizeCount)
        } finally {
            releaseStart.complete(Unit)
            startJob.cancel()
            startJob.join()
        }
    }

    @Test
    fun `cancellation from finalize during start compensation is not swallowed and state is safe`() = runTest {
        val store = object : CourseSessionStore {
            var createCount = 0
            var finalizeCount = 0
            override suspend fun createCourse(): Long {
                createCount++
                return 42L
            }

            override suspend fun finalizeCourse(courseId: Long, endTs: Long) {
                finalizeCount++
                throw CancellationException("finalize cancelled")
            }
        }
        val pipeline = object : SessionPipeline {
            override suspend fun start() {
                throw CancellationException("start cancelled")
            }

            override suspend fun stop() = Unit
        }
        val controller = ListenSessionController(store, pipeline, nowMillis = { 1_700_000_000_000L })

        val thrown = try {
            controller.start()
            null
        } catch (e: CancellationException) {
            e
        }

        // 取消不被吞掉：原始 start 取消原样传播。
        assertTrue("CancellationException must propagate", thrown != null)
        assertEquals("start cancelled", thrown?.message)
        assertEquals(1, store.createCount)
        assertEquals(1, store.finalizeCount)
        // 明确安全结果：state 回到 Idle，Error 文案绝不含取消/异常原文。
        assertEquals(SessionState.Idle, controller.state.value)
    }

    @Test
    fun `stop during Starting handles cancelled but not completed start job`() = runTest {
        val startingEntered = CompletableDeferred<Unit>()
        val releaseStarting = CompletableDeferred<Unit>()
        val startOutcome = CompletableDeferred<Throwable?>()
        val store = object : CourseSessionStore {
            var createCount = 0
            var finalizeCount = 0
            override suspend fun createCourse(): Long {
                createCount++
                awaitCancellation()
            }
            override suspend fun finalizeCourse(courseId: Long, endTs: Long) {
                finalizeCount++
            }
        }
        val pipeline = object : SessionPipeline {
            var startCount = 0
            var stopCount = 0
            override suspend fun start() { startCount++ }
            override suspend fun stop() { stopCount++ }
        }
        val controller = ListenSessionController(store, pipeline).apply {
            onStartingPublished = {
                startingEntered.complete(Unit)
                withContext(NonCancellable) { releaseStarting.await() }
            }
        }
        val startJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                controller.start()
                startOutcome.complete(null)
            } catch (e: CancellationException) {
                startOutcome.complete(e)
            }
        }

        try {
            startingEntered.await()
            assertEquals(SessionState.Starting, controller.state.value)

            // Cancel externally while the hook remains in NonCancellable: the Job is inactive,
            // but the observed Starting session has not completed yet.
            startJob.cancel()
            val stopJob = async(UnconfinedTestDispatcher(testScheduler)) { controller.stop() }
            releaseStarting.complete(Unit)

            assertTrue("stop must handle observed Starting even when candidate is inactive", stopJob.await())
            assertTrue("start must complete with CancellationException", startOutcome.await() is CancellationException)
            startJob.join()

            assertEquals(SessionState.Idle, controller.state.value)
            assertEquals(0, pipeline.startCount)
            assertEquals(0, pipeline.stopCount)
            assertEquals(0, store.finalizeCount)
        } finally {
            releaseStarting.complete(Unit)
            if (startJob.isActive) startJob.cancel()
            startJob.join()
        }
    }

    @Test
    fun `stop during Running cancels in flight pipeline start before stopping`() = runTest {
        val startEntered = CompletableDeferred<Unit>()
        val startOutcome = CompletableDeferred<Throwable?>()
        val store = FakeStore()
        lateinit var controller: ListenSessionController
        val pipeline = object : SessionPipeline {
            var startCount = 0
            var stopCount = 0
            override suspend fun start() {
                startCount++
                assertEquals(SessionState.Running(42L), controller.state.value)
                startEntered.complete(Unit)
                awaitCancellation()
            }
            override suspend fun stop() {
                stopCount++
            }
        }
        controller = ListenSessionController(store, pipeline)
        val startJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            try {
                controller.start()
                startOutcome.complete(null)
            } catch (e: CancellationException) {
                startOutcome.complete(e)
            }
        }

        try {
            startEntered.await()
            val stopHandled = controller.stop()

            assertTrue("stop must be handled while pipeline.start is in flight", stopHandled)
            assertTrue(
                "stop must cancel and await the in-flight start job",
                startOutcome.isCompleted,
            )
            assertTrue("start must complete with CancellationException", startOutcome.await() is CancellationException)
            startJob.join()
            assertEquals(SessionState.Idle, controller.state.value)
            assertEquals(1, store.createCount)
            assertEquals(1, store.finalizeCount)
            assertEquals(1, pipeline.startCount)
            assertEquals(0, pipeline.stopCount)
        } finally {
            if (startJob.isActive) startJob.cancel()
            startJob.join()
        }
    }

    @Test
    fun `stop rechecks a start that completes after cancellation request`() = runTest {
        val startEntered = CompletableDeferred<Unit>()
        val releaseStart = CompletableDeferred<Unit>()
        val store = FakeStore()
        val pipeline = object : SessionPipeline {
            var startCount = 0
            var stopCount = 0

            override suspend fun start() {
                startCount++
                startEntered.complete(Unit)
                // Simulate a non-cooperative startup operation: stop() cancels the start Job,
                // but the underlying startup still completes before start() can clear its ref.
                withContext(NonCancellable) { releaseStart.await() }
            }

            override suspend fun stop() {
                stopCount++
            }
        }
        val controller = ListenSessionController(store, pipeline)
        val startJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.start()
        }

        try {
            startEntered.await()
            assertEquals(SessionState.Running(42L), controller.state.value)

            // The stop coroutine captures and cancels the in-flight start, then waits for it.
            val stopJob = async(UnconfinedTestDispatcher(testScheduler)) { controller.stop() }
            assertFalse("stop must wait for the cancelled start to finish", stopJob.isCompleted)

            // Startup completes despite the cancellation request. stop() must not trust its
            // earlier in-flight classification; it must stop/finalize the now-running session.
            releaseStart.complete(Unit)

            assertTrue("stop must handle the completed startup", stopJob.await())
            startJob.join()
            assertEquals(SessionState.Idle, controller.state.value)
            assertEquals(1, pipeline.startCount)
            assertEquals(1, pipeline.stopCount)
            assertEquals(1, store.finalizeCount)
        } finally {
            releaseStart.complete(Unit)
            if (startJob.isActive) startJob.cancel()
            startJob.join()
        }
    }

    @Test
    fun `early return after course creation finalizes the discarded course`() = runTest {
        val createEntered = CompletableDeferred<Unit>()
        val releaseCreate = CompletableDeferred<Unit>()
        val finalizedIds = mutableListOf<Long>()
        val store = object : CourseSessionStore {
            override suspend fun createCourse(): Long {
                createEntered.complete(Unit)
                releaseCreate.await()
                return 42L
            }
            override suspend fun finalizeCourse(courseId: Long, endTs: Long) {
                finalizedIds += courseId
            }
        }
        val pipeline = object : SessionPipeline {
            var startCount = 0
            override suspend fun start() { startCount++ }
            override suspend fun stop() = Unit
        }
        val controller = ListenSessionController(store, pipeline)
        val activeCourseField = ListenSessionController::class.java
            .getDeclaredField("activeCourseId")
            .apply { isAccessible = true }
        val startResult = CompletableDeferred<Boolean>()
        val startJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            startResult.complete(controller.start())
        }

        try {
            createEntered.await()
            // Force the defensive branch that represents another active session winning while
            // this start already created course 42. The new course must not be orphaned.
            activeCourseField.set(controller, 99L)
            releaseCreate.complete(Unit)

            assertFalse(startResult.await())
            startJob.join()
            assertEquals(listOf(42L), finalizedIds)
            assertEquals(0, pipeline.startCount)
        } finally {
            releaseCreate.complete(Unit)
            if (startJob.isActive) startJob.cancel()
            startJob.join()
            activeCourseField.set(controller, null)
        }
    }
}
