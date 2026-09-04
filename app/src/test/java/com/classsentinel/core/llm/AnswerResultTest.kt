package com.classsentinel.core.llm

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerResultTest {

    @Test
    fun `generating is observable without exposing question text`() {
        val state: AnswerResult = AnswerResult.Generating

        assertTrue(state is AnswerResult.Generating)
    }

    @Test
    fun `succeeded carries trimmed answer`() {
        val state = AnswerResult.Succeeded("结论")

        assertEquals("结论", state.answer)
    }

    @Test
    fun `insufficient carries the question for retry`() {
        val state = AnswerResult.Insufficient("问题内容")

        assertEquals("问题内容", state.question)
    }

    @Test
    fun `failed carries only safe code`() {
        val state: AnswerResult = AnswerResult.Failed("LLM_TIMEOUT")

        assertEquals("LLM_TIMEOUT", (state as AnswerResult.Failed).safeCode)
        assertTrue(!state.toString().contains("api-key"))
        assertTrue(!state.toString().contains("provider body"))
    }

    @Test
    fun `answer result flow emits generating then succeeded with joined trimmed text`() = runTest {
        val states = answerResults(
            question = "问题",
            deltas = flow {
                emit(" 结论")
                emit("。 ")
            },
        ).toList()

        assertEquals(
            listOf(AnswerResult.Generating, AnswerResult.Succeeded("结论。")),
            states,
        )
    }

    @Test
    fun `blank answer becomes insufficient instead of succeeded`() = runTest {
        val states = answerResults("问题", flow { emit(" \n ") }).toList()

        assertEquals(
            listOf(AnswerResult.Generating, AnswerResult.Insufficient("问题")),
            states,
        )
    }

    @Test
    fun `timeout becomes terminal safe failure`() = runTest {
        val states = answerResults(
            question = "问题",
            deltas = flow {
                delay(10_000L)
                emit("不会到达")
            },
            timeoutMs = 1_000L,
        ).toList()

        assertEquals(
            listOf(AnswerResult.Generating, AnswerResult.Failed("LLM_TIMEOUT")),
            states,
        )
    }

    @Test
    fun `external cancellation is propagated rather than converted to failure`() = runTest {
        val job = launch {
            answerResults("问题", flow { awaitCancellation() }, timeoutMs = 10_000L).toList()
        }

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
    }
}
