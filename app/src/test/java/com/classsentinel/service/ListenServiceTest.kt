package com.classsentinel.service

import android.app.Service
import android.content.Intent
import com.classsentinel.core.llm.AnswerRequest
import com.classsentinel.core.pipeline.PipelineState
import com.classsentinel.data.entities.EventEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ListenServiceTest {
    @After
    fun resetLiveStreamBus() {
        LiveStreamBus.activeCourseId.value?.let { LiveStreamBus.finishCourse(it) }
        LiveStreamBus.clear()
        LiveStreamBus.pipelineState.value = PipelineState.Idle
    }

    @Test
    fun `ACTION_STOP uses session controller before stopSelfResult`() = runTest {
        val events = mutableListOf<String>()
        val stopFinished = CompletableDeferred<Unit>()
        val handle = object : ListenSessionHandle {
            override suspend fun start(): Boolean = true
            override suspend fun stop(): Boolean {
                events += "handle.stop"
                stopFinished.complete(Unit)
                return true
            }
        }
        val injectedSession = ListenServiceSession(
            scope = CoroutineScope(coroutineContext),
            createHandle = { handle },
            stopSelfResult = {
                events += "stopSelfResult:$it"
                true
            },
        )
        injectedSession.start().join()
        val service = ListenService().apply { sessionOverride = injectedSession }

        val result = service.onStartCommand(
            Intent().setAction(ListenService.ACTION_STOP),
            0,
            17,
        )
        runCurrent()
        stopFinished.await()
        runCurrent()

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(listOf("handle.stop", "stopSelfResult:17"), events)
    }

    @Test
    fun `ACTION_START starts the injected session after foreground setup`() = runTest {
        val events = mutableListOf<String>()
        val startFinished = CompletableDeferred<Unit>()
        val handle = object : ListenSessionHandle {
            override suspend fun start(): Boolean {
                events += "handle.start"
                startFinished.complete(Unit)
                return true
            }
            override suspend fun stop(): Boolean = true
        }
        val injectedSession = ListenServiceSession(
            scope = CoroutineScope(coroutineContext),
            createHandle = { handle },
            stopSelfResult = { true },
        )
        val service = ListenService().apply {
            sessionOverride = injectedSession
            foregroundOverride = {
                events += "foregroundStarting:${LiveStreamBus.pipelineState.value == PipelineState.Starting}"
            }
        }

        val result = service.onStartCommand(
            Intent().setAction(ListenService.ACTION_START),
            0,
            23,
        )
        runCurrent()
        startFinished.await()
        runCurrent()

        // 听讲必须由用户重新启动；服务被系统回收后不能重放 ACTION_START 自动开麦。
        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(listOf("foregroundStarting:true", "handle.start"), events)
    }

    @Test
    fun `unknown action does not start foreground recording`() = runTest {
        val events = mutableListOf<String>()
        val handle = object : ListenSessionHandle {
            override suspend fun start(): Boolean {
                events += "handle.start"
                return true
            }

            override suspend fun stop(): Boolean = true
        }
        val injectedSession = ListenServiceSession(
            scope = CoroutineScope(coroutineContext),
            createHandle = { handle },
            stopSelfResult = { true },
        )
        val service = ListenService().apply {
            sessionOverride = injectedSession
            foregroundOverride = { events += "foreground" }
        }

        val result = service.onStartCommand(Intent().setAction("com.classsentinel.action.UNKNOWN"), 0, 31)
        runCurrent()

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `a repeated START after startup does not restart or kill the running session`() = runTest {
        var starts = 0
        var foregrounds = 0
        val injectedSession = ListenServiceSession(
            scope = this,
            createHandle = {
                object : ListenSessionHandle {
                    override suspend fun start(): Boolean { starts++; return true }
                    override suspend fun stop(): Boolean = true
                }
            },
            stopSelfResult = { true },
        )
        val service = ListenService().apply {
            sessionOverride = injectedSession
            foregroundOverride = { foregrounds++ }
        }
        service.onStartCommand(Intent().setAction(ListenService.ACTION_START), 0, 1)
        runCurrent()
        LiveStreamBus.pushState(PipelineState.Listening(1))
        service.onStartCommand(Intent().setAction(ListenService.ACTION_START), 0, 2)
        runCurrent()

        assertEquals(1, starts)
        assertEquals(1, foregrounds)
        assertEquals(PipelineState.Listening(1), LiveStreamBus.pipelineState.value)
    }

    @Test
    fun `retry answer request reuses the assembled question persisted in the event`() {
        val assembled = "what is the difference between machine learning and deep learning，请你解释一下。"
        val persisted = EventEntity(
            id = 17L,
            courseId = 1L,
            type = "QUESTION",
            triggerText = assembled,
            contextText = "课堂上下文",
            notifiedAt = 2_000L,
            ts = 2_000L,
        )

        val retryEvent = persisted.toRetryQuestionEvent()
        val request = AnswerRequest(
            eventId = persisted.id,
            question = retryEvent.triggerText,
            context = retryEvent.context,
        )

        assertEquals(persisted.triggerText, request.question)
        assertEquals(persisted.contextText, request.context)
    }

    @Test
    fun `idle retry owns the service and stops only after answer completion`() = runTest {
        val stopIds = mutableListOf<Int>()
        val answerFinished = CompletableDeferred<Unit>()
        val persisted = EventEntity(
            id = 17L,
            courseId = 1L,
            type = "QUESTION",
            triggerText = "问题",
            contextText = "上下文",
            notifiedAt = 2_000L,
            ts = 2_000L,
        )

        val retryJob = launch {
            executeRetry(
                eventId = persisted.id,
                startId = 31,
                wasActiveAtStart = false,
                loadEvent = { persisted },
                generateAnswer = { answerFinished.await() },
                isActive = { false },
                stopSelfResult = { stopIds += it; true },
            )
        }
        runCurrent()
        assertEquals(emptyList<Int>(), stopIds)

        answerFinished.complete(Unit)
        retryJob.join()

        assertEquals(listOf(31), stopIds)
    }

    @Test
    fun `retry does not stop a service that is active when the answer finishes`() = runTest {
        val stopIds = mutableListOf<Int>()
        var active = false
        val persisted = EventEntity(
            id = 18L,
            courseId = 1L,
            type = "QUESTION",
            triggerText = "问题",
            contextText = "上下文",
            notifiedAt = 2_000L,
            ts = 2_000L,
        )

        executeRetry(
            eventId = persisted.id,
            startId = 32,
            wasActiveAtStart = false,
            loadEvent = { persisted },
            generateAnswer = { active = true },
            isActive = { active },
            stopSelfResult = { stopIds += it; true },
        )

        assertEquals(emptyList<Int>(), stopIds)
    }

    @Test
    fun `runtime failure closes and finalizes before exposing retry and ignores late states`() = runTest {
        val events = mutableListOf<String>()
        val controller = ListenSessionController(
            store = object : CourseSessionStore {
                override suspend fun createCourse(): Long = 7L
                override suspend fun finalizeCourse(courseId: Long, endTs: Long) { events += "finalize" }
            },
            pipeline = object : SessionPipeline {
                override suspend fun start() = Unit
                override suspend fun stop() { events += "release" }
            },
        )
        val injectedSession = ListenServiceSession(
            scope = this,
            createHandle = {
                object : ListenSessionHandle {
                    override suspend fun start() = controller.start()
                    override suspend fun stop() = controller.stop()
                }
            },
            stopSelfResult = { events += "stopSelf:$it"; true },
        )
        val service = ListenService().apply {
            sessionOverride = injectedSession
            foregroundOverride = {}
        }
        service.onStartCommand(Intent().setAction(ListenService.ACTION_START), 0, 10)
        runCurrent()
        service.onPipelineStateChanged(PipelineState.Error("转写中断"))
        assertEquals(PipelineState.Stopping, LiveStreamBus.pipelineState.value)
        service.onPipelineStateChanged(PipelineState.Listening(1))
        assertEquals(PipelineState.Stopping, LiveStreamBus.pipelineState.value)
        runCurrent()
        assertEquals(listOf("release", "finalize", "stopSelf:10"), events)
        assertEquals(SessionState.Idle, controller.state.value)
        service.onDestroy()
        assertEquals(PipelineState.Error("转写中断"), LiveStreamBus.pipelineState.value)
    }
}
