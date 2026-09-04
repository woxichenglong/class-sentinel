package com.classsentinel.ui

import com.classsentinel.core.pipeline.PipelineState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenUiStateTest {
    @Test
    fun `session active is derived from authoritative pipeline state`() {
        assertFalse(PipelineState.Idle.isSessionActive())
        assertTrue(PipelineState.Starting.isSessionActive())
        assertTrue(PipelineState.Listening(1).isSessionActive())
        assertTrue(PipelineState.Recovering("TeleSpeech", "恢复中").isSessionActive())
        assertTrue(PipelineState.Stopping.isSessionActive())
        assertFalse(PipelineState.Error("安全错误").isSessionActive())
    }
}
