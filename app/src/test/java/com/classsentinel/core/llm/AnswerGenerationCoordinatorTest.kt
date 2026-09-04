package com.classsentinel.core.llm

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnswerGenerationCoordinatorTest {

    @Test
    fun `same event id shares in flight job and retry keeps the id without a second insertion`() = runTest {
        val requests = mutableListOf<Long>()
        val results = mutableListOf<Pair<Long, AnswerResult>>()
        val request = AnswerRequest(
            eventId = 42L,
            question = "问题",
            context = "上下文",
        )
        val coordinator = AnswerGenerationCoordinator(
            scope = this,
            generate = { current ->
                requests += current.eventId
                flowOf("答案")
            },
            onResult = { current, result -> results += current.eventId to result },
        )

        val first = coordinator.submit(request)
        val duplicate = coordinator.submit(request)
        advanceUntilIdle()
        val retry = coordinator.submit(request)
        advanceUntilIdle()

        assertSame(first, duplicate)
        assertEquals(listOf(42L, 42L), requests)
        assertEquals(
            listOf(
                42L to AnswerResult.Generating,
                42L to AnswerResult.Succeeded("答案"),
                42L to AnswerResult.Generating,
                42L to AnswerResult.Succeeded("答案"),
            ),
            results,
        )
        assertEquals(42L, request.eventId)
        assertEquals(true, retry?.isCompleted)
    }
}
