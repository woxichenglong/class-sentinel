package com.classsentinel.service

import com.classsentinel.core.pipeline.PipelineState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveStreamBusMarkerStateTest {

    @After
    fun tearDown() {
        LiveStreamBus.clear()
        LiveStreamBus.finishCourse(LiveStreamBus.activeCourseId.value ?: -1L)
        LiveStreamBus.pipelineState.value = PipelineState.Idle
    }

    @Test
    fun `course lifecycle owns latest chunk marker eligibility without changing pipeline state`() {
        val listening = PipelineState.Listening(sentences = 2)
        LiveStreamBus.pipelineState.value = listening
        LiveStreamBus.startCourse(10L)
        LiveStreamBus.pushSegment("first")
        LiveStreamBus.pushLatestChunk(10L, 101L)

        LiveStreamBus.startCourse(20L)

        assertEquals(20L, LiveStreamBus.activeCourseId.value)
        assertEquals(null, LiveStreamBus.latestChunkId.value)
        assertEquals(emptyList<String>(), LiveStreamBus.segmentList.value)
        assertEquals(listening, LiveStreamBus.pipelineState.value)

        LiveStreamBus.pushLatestChunk(10L, 999L)
        assertEquals(null, LiveStreamBus.latestChunkId.value)
        LiveStreamBus.pushLatestChunk(20L, 202L)
        assertEquals(202L, LiveStreamBus.latestChunkId.value)

        LiveStreamBus.finishCourse(10L)
        assertEquals(20L, LiveStreamBus.activeCourseId.value)
        assertEquals(202L, LiveStreamBus.latestChunkId.value)

        LiveStreamBus.finishCourse(20L)
        assertEquals(null, LiveStreamBus.activeCourseId.value)
        assertEquals(null, LiveStreamBus.latestChunkId.value)
        assertEquals(listening, LiveStreamBus.pipelineState.value)
    }
}
