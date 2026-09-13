package com.classsentinel.ui.screens

import com.classsentinel.core.pipeline.PipelineState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ListeningStartTest {
    @Test
    fun `permission missing requests permission instead of starting service`() = runTest {
        val calls = mutableListOf<String>()
        val result = requestListeningStart(
            currentState = { PipelineState.Idle },
            ensureReady = { calls += "prepare"; true },
            microphoneGranted = { false },
            requestPermission = { calls += "permission" },
            start = { calls += "start" },
        )
        assertEquals(ListeningStartOutcome.PERMISSION_REQUESTED, result)
        assertEquals(listOf("prepare", "permission"), calls)
    }

    @Test
    fun `model failure does not request permission or start`() = runTest {
        val calls = mutableListOf<String>()
        assertEquals(ListeningStartOutcome.MODEL_NOT_READY, requestListeningStart(
            currentState = { PipelineState.Idle }, ensureReady = { false },
            microphoneGranted = { true }, requestPermission = { calls += "permission" }, start = { calls += "start" },
        ))
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `state is rechecked after suspend preparation before starting`() = runTest {
        var state: PipelineState = PipelineState.Idle
        var starts = 0
        assertEquals(ListeningStartOutcome.BUSY, requestListeningStart(
            currentState = { state }, ensureReady = { state = PipelineState.Stopping; true },
            microphoneGranted = { true }, requestPermission = {}, start = { starts++ },
        ))
        assertEquals(0, starts)
    }

    @Test
    fun `ready idle starts exactly once and active skips preparation`() = runTest {
        var state: PipelineState = PipelineState.Idle
        val calls = mutableListOf<String>()
        suspend fun request() = requestListeningStart(
            currentState = { state }, ensureReady = { calls += "prepare"; true },
            microphoneGranted = { true }, requestPermission = {},
            start = { calls += "start"; state = PipelineState.Starting },
        )
        assertEquals(ListeningStartOutcome.STARTED, request())
        assertEquals(ListeningStartOutcome.BUSY, request())
        assertEquals(listOf("prepare", "start"), calls)
    }
}
