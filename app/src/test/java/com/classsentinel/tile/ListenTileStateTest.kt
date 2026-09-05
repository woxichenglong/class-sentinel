package com.classsentinel.tile

import com.classsentinel.core.pipeline.PipelineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTileStateTest {

    @Test
    fun `listening-like states render active and never start a second session`() {
        val activeStates = listOf(
            PipelineState.Starting,
            PipelineState.Listening(sentences = 1, engine = "asr"),
            PipelineState.Recovering(engine = "asr", message = "恢复中"),
            PipelineState.Stopping,
        )

        activeStates.forEach { state ->
            assertEquals(TilePresentation.ACTIVE, tilePresentationFor(state, ready = true))
            assertEquals(ListenTileAction.STOP, tileActionFor(state, microphoneGranted = true, modelReady = true))
        }
    }

    @Test
    fun `idle ready tile starts and idle model-unready tile opens setup`() {
        assertEquals(
            TilePresentation.INACTIVE,
            tilePresentationFor(PipelineState.Idle, ready = true),
        )
        assertEquals(
            ListenTileAction.START,
            tileActionFor(PipelineState.Idle, microphoneGranted = true, modelReady = true),
        )
        assertEquals(
            ListenTileAction.OPEN_SETUP,
            tileActionFor(PipelineState.Idle, microphoneGranted = false, modelReady = true),
        )
        assertEquals(
            ListenTileAction.OPEN_SETUP,
            tileActionFor(PipelineState.Idle, microphoneGranted = true, modelReady = false),
        )
    }

    @Test
    fun `idle unready tile is unavailable without confusing it with active listening`() {
        assertEquals(
            TilePresentation.UNAVAILABLE,
            tilePresentationFor(PipelineState.Idle, ready = false),
        )
        assertTrue(!isListeningState(PipelineState.Idle))
        assertTrue(isListeningState(PipelineState.Stopping))
    }
}
