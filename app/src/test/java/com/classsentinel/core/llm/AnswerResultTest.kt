package com.classsentinel.core.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
            streamOutput = true,
        ).toList()

        assertEquals(
            listOf(
                AnswerResult.Generating,
                AnswerResult.Streaming(" 结论"),
                AnswerResult.Streaming(" 结论。 "),
                AnswerResult.Succeeded("结论。"),
            ),
            states,
        )
    }

    @Test
    fun `non streaming mode emits no intermediate states`() = runTest {
        val states = answerResults(
            question = "问题",
            deltas = flowOf("第一", "句"),
            streamOutput = false,
        ).toList()

        assertEquals(
            listOf(AnswerResult.Generating, AnswerResult.Succeeded("第一句")),
            states,
        )
    }

    @Test
    fun `strict insufficient sentinel becomes insufficient without streaming the sentinel`() = runTest {
        val states = answerResults(
            question = "问题",
            deltas = flowOf("[[INSUFFICIENT]]"),
            streamOutput = true,
        ).toList()

        assertEquals(
            listOf(AnswerResult.Generating, AnswerResult.Insufficient("问题")),
            states,
        )
    }

    @Test
    fun `uncertainty wording in an otherwise substantive answer remains succeeded`() = runTest {
        val states = answerResults(
            question = "问题",
            deltas = flowOf("这个结论存在不确定性，但课堂上老师给出了明确条件。"),
        ).toList()

        assertEquals(
            listOf(
                AnswerResult.Generating,
                AnswerResult.Succeeded("这个结论存在不确定性，但课堂上老师给出了明确条件。"),
            ),
            states,
        )
    }

    @Test
    fun `quoted insufficient wording in ordinary answer remains succeeded`() = runTest {
        val states = answerResults(
            question = "问题",
            deltas = flowOf("老师引用了“依据不足”这个标签，但随后给出了完整证明。"),
        ).toList()

        assertEquals(
            listOf(
                AnswerResult.Generating,
                AnswerResult.Succeeded("老师引用了“依据不足”这个标签，但随后给出了完整证明。"),
            ),
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
    fun `timeout after a delta has no late streaming or succeeded state`() = runTest {
        val states = answerResults(
            question = "问题",
            deltas = flow {
                emit("先到")
                delay(10_000L)
                emit("迟到")
            },
            timeoutMs = 1_000L,
            streamOutput = true,
        ).toList()

        assertEquals(
            listOf(
                AnswerResult.Generating,
                AnswerResult.Streaming("先到"),
                AnswerResult.Failed("LLM_TIMEOUT"),
            ),
            states,
        )
    }

    @Test
    fun `typed llm errors retain only their safe category`() = runTest {
        LlmError.Kind.values().forEach { kind ->
            val states = answerResults(
                question = "问题",
                deltas = flow { throw LlmException(LlmError(kind)) },
            ).toList()

            assertEquals(
                listOf(AnswerResult.Generating, AnswerResult.Failed(kind.name)),
                states,
            )
        }
    }

    @Test
    fun `ordinary exception maps to request failure without leaking its message`() = runTest {
        val leaked = "provider body https://provider.test classroom text"
        val states = answerResults(
            question = "问题",
            deltas = flow { throw IllegalStateException(leaked) },
        ).toList()

        assertEquals(
            listOf(AnswerResult.Generating, AnswerResult.Failed("LLM_REQUEST")),
            states,
        )
        assertTrue(states.last().toString().contains("LLM_REQUEST"))
        assertTrue(!states.last().toString().contains("provider body"))
        assertTrue(!states.last().toString().contains("classroom text"))
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

    @Test
    fun `the original cancellation exception is propagated`() = runBlocking {
        val cancellation = CancellationException("caller cancelled")
        val error = runCatching {
            answerResults("问题", flow { throw cancellation }, timeoutMs = 10_000L).toList()
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals(cancellation.message, error?.message)
        assertTrue(error === cancellation || error?.cause === cancellation)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `failed rejects an unallowlisted safe code`() {
        AnswerResult.Failed("NOT_SAFE")
    }
}
