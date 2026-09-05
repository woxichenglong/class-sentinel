package com.classsentinel.service

import android.app.Service
import android.content.Intent
import com.classsentinel.core.pipeline.PipelineState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
}
