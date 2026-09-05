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
        val requests = mutableListOf<Long?>()
        val results = mutableListOf<Pair<Long?, AnswerResult>>()
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
                42L to AnswerResult.Streaming("答案"),
                42L to AnswerResult.Succeeded("答案"),
                42L to AnswerResult.Generating,
                42L to AnswerResult.Streaming("答案"),
                42L to AnswerResult.Succeeded("答案"),
            ),
            results,
        )
        assertEquals(42L, request.eventId)
        assertEquals(true, retry?.isCompleted)
    }

    @Test
    fun `request stream output setting reaches result coordinator`() = runTest {
        val results = mutableListOf<AnswerResult>()
        val request = AnswerRequest(
            eventId = 7L,
            question = "问题",
            context = "上下文",
            streamOutput = false,
        )
        val coordinator = AnswerGenerationCoordinator(
            scope = this,
            generate = { flowOf("第一", "句") },
            onResult = { _, result -> results += result },
        )

        coordinator.submit(request)?.join()

        assertEquals(
            listOf(AnswerResult.Generating, AnswerResult.Succeeded("第一句")),
            results,
        )
    }

    @Test
    fun `synchronous typed generation error keeps its safe category`() = runTest {
        val results = mutableListOf<AnswerResult>()
        val coordinator = AnswerGenerationCoordinator(
            scope = this,
            generate = { throw LlmException(LlmError(LlmError.Kind.AUTH)) },
            onResult = { _, result -> results += result },
        )

        coordinator.submit(
            AnswerRequest(eventId = 8L, question = "问题", context = "上下文"),
        )?.join()

        assertEquals(listOf(AnswerResult.Failed("AUTH")), results)
    }

    @Test
    fun `transient request key deduplicates generation without a database event id`() = runTest {
        var generationCalls = 0
        val results = mutableListOf<AnswerResult>()
        val request = AnswerRequest(
            eventId = null,
            requestKey = "transient-1",
            question = "问题",
            context = "上下文",
        )
        val coordinator = AnswerGenerationCoordinator(
            scope = this,
            generate = {
                generationCalls++
                flowOf("临时答案")
            },
            onResult = { _, result -> results += result },
        )

        val first = coordinator.submit(request)
        val duplicate = coordinator.submit(request)
        advanceUntilIdle()

        assertSame(first, duplicate)
        assertEquals(1, generationCalls)
        assertEquals(null, request.eventId)
        assertEquals(
            listOf(
                AnswerResult.Generating,
                AnswerResult.Streaming("临时答案"),
                AnswerResult.Succeeded("临时答案"),
            ),
            results,
        )
    }
}
