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
import java.io.IOException

class LlmClientTest {

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

    private fun cfg() = LlmConfig(server.url("/v1").toString(), "sk-test", "gpt-4o-mini")

    @Test
    fun `streams sse deltas in order until DONE`() = runTest {
        val sse = """
            data: {"choices":[{"delta":{"content":"傅里"}}]}

            data: {"choices":[{"delta":{"content":"叶变换"}}]}

            data: [DONE]

        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sse)
                .addHeader("Content-Type", "text/event-stream"),
        )
        val chunks = LlmClient().streamChat(
            listOf(mapOf("role" to "user", "content" to "hi")), cfg(),
        ).toList()
        assertEquals(listOf("傅里", "叶变换"), chunks)
    }

    @Test
    fun `skips empty and non-content deltas`() = runTest {
        val sse = """
            : keepalive comment

            data: {"choices":[{"delta":{"role":"assistant"}}]}

            data: {"choices":[{"delta":{}}]}

            data: {"choices":[{"delta":{"content":"A"}}]}

            data: [DONE]

        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(sse))
        val chunks = LlmClient().streamChat(
            listOf(mapOf("role" to "user", "content" to "hi")), cfg(),
        ).toList()
        assertEquals(listOf("A"), chunks)
    }

    @Test
    fun `throws IOException with status code on non 2xx`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("provider body must not escape"))
        val err = runCatching {
            LlmClient().streamChat(listOf(mapOf("role" to "user", "content" to "hi")), cfg()).toList()
        }.exceptionOrNull()
        assertTrue(err is IOException)
        assertTrue(err!!.message!!.contains("500"))
        assertTrue(!err.message!!.contains("provider body"))
    }

    @Test
    fun `Command Code preset disables thinking in the request payload`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"))
        val commandCode = AiProviderPreset.COMMAND_CODE
            .toLlmConfig(apiKey = "command-code-test-key")
            .copy(baseUrl = server.url("/v1").toString())

        LlmClient().streamChat(
            listOf(mapOf("role" to "user", "content" to "hi")),
            commandCode,
        ).toList()

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("deepseek/deepseek-v4-flash", body.getString("model"))
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
    }

    @Test
    fun `answer service sends system and user messages`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"))
        AnswerService().answer(
            question = "什么是傅里叶变换",
            context = "高等数学课堂",
            style = AnswerStyle.TERSENESS,
            cfg = cfg(),
        ).toList()

        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))

        val body = JSONObject(req.body.readUtf8())
        assertEquals("gpt-4o-mini", body.getString("model"))
        assertEquals(true, body.getBoolean("stream"))

        val msgs = body.getJSONArray("messages")
        assertEquals(2, msgs.length())
        val system = msgs.getJSONObject(0)
        assertEquals("system", system.getString("role"))
        assertTrue(system.getString("content").contains("课堂即时答题助手"))

        val user = msgs.getJSONObject(1)
        assertEquals("user", user.getString("role"))
        val userContent = user.getString("content")
        assertTrue(userContent.contains("什么是傅里叶变换"))
        assertTrue(userContent.contains("高等数学课堂"))
    }

    @Test
    fun `academic style uses different system prompt`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"))
        AnswerService().answer(
            question = "介绍下导数",
            context = "微积分课堂",
            style = AnswerStyle.ACADEMIC,
            cfg = cfg(),
        ).toList()
        val body = JSONObject(server.takeRequest().body.readUtf8())
        val system = body.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system.contains("要点化"))
        assertTrue(system.contains("200字"))
    }
}
