package com.classsentinel.core.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnswerServiceTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun cfg() = LlmConfig(server.url("/v1").toString(), "test-key", "test-model")

    @Test
    fun `short non-streaming answer uses short budget and emits one final update`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(sse("A", "B")))

        val updates = AnswerService().answer(
            question = "问题",
            context = "课堂上下文",
            style = AnswerStyle.TERSENESS,
            cfg = cfg(),
            answerLength = "short",
            streamOutput = false,
        ).toList()

        assertEquals(listOf("AB"), updates)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(128, body.getInt("max_tokens"))
        val system = body.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system.contains("≤60字"))
    }

    @Test
    fun `long streaming answer uses long budget and preserves deltas`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(sse("A", "B")))

        val updates = AnswerService().answer(
            question = "问题",
            context = "课堂上下文",
            style = AnswerStyle.TERSENESS,
            cfg = cfg(),
            answerLength = "long",
            streamOutput = true,
        ).toList()

        assertEquals(listOf("A", "B"), updates)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(512, body.getInt("max_tokens"))
        val system = body.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system.contains("≤160字"))
    }

    @Test
    fun `live classroom prompt is concise grounded and mentions question exactly once`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(sse("答案")))
        val question = "QUESTION-UNIQUE-42"
        val context = "CONTEXT-UNIQUE-17"

        AnswerService().answer(
            question = question,
            context = context,
            style = AnswerStyle.TERSENESS,
            cfg = cfg(),
        ).toList()

        val body = JSONObject(server.takeRequest().body.readUtf8())
        val messages = body.getJSONArray("messages")
        val system = messages.getJSONObject(0).getString("content")
        val user = messages.getJSONObject(1).getString("content")
        val allPromptText = (0 until messages.length())
            .joinToString("\n") { messages.getJSONObject(it).getString("content") }

        assertTrue(system.contains("你是课堂即时答题助手"))
        assertTrue(system.contains("先给出一句可直接口头回答的短结论"))
        assertTrue(system.contains("只根据用户提供的课堂上下文和问题回答"))
        assertTrue(system.contains("依据不足"))
        assertTrue(system.contains("不确定"))
        assertTrue(system.contains("不要猜测"))
        assertTrue(system.contains("不要输出 Markdown 长文"))
        assertTrue(user.contains(context))
        assertEquals(1, allPromptText.windowed(question.length).count { it == question })
    }

    private fun sse(vararg pieces: String): String = buildString {
        pieces.forEach { piece ->
            append("data: {\"choices\":[{\"delta\":{\"content\":\"$piece\"}}]}\n\n")
        }
        append("data: [DONE]\n\n")
    }
}
