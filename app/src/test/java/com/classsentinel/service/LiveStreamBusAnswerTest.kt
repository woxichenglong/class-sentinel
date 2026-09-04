package com.classsentinel.service

import com.classsentinel.core.llm.AnswerResult
import com.classsentinel.core.pipeline.PipelineState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveStreamBusAnswerTest {

    @After
    fun tearDown() {
        LiveStreamBus.clear()
        LiveStreamBus.pipelineState.value = PipelineState.Idle
    }

    @Test
    fun `latest answer state is replaced for the same event id`() {
        LiveStreamBus.pushAnswer(
            eventId = 42L,
            question = "问题",
            context = "依据",
            timestampMs = 123L,
            result = AnswerResult.Generating,
        )
        LiveStreamBus.pushAnswer(
            eventId = 42L,
            question = "问题",
            context = "依据",
            timestampMs = 123L,
            result = AnswerResult.Succeeded("答案"),
        )

        assertEquals(
            LiveAnswerState(42L, "问题", "依据", 123L, AnswerResult.Succeeded("答案")),
            LiveStreamBus.latestAnswer.value,
        )
    }

    @Test
    fun `clear removes current answer without changing pipeline state`() {
        val state = PipelineState.Listening(1)
        LiveStreamBus.pushState(state)
        LiveStreamBus.pushAnswer(7L, "问题", "依据", 1L, AnswerResult.Insufficient("问题"))

        LiveStreamBus.clear()

        assertEquals(null, LiveStreamBus.latestAnswer.value)
        assertEquals(state, LiveStreamBus.pipelineState.value)
    }
}
