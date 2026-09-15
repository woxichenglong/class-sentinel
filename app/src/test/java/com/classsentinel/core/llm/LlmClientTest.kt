package com.classsentinel.core.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test


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
    fun `throws safe typed error on non 2xx`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("provider body must not escape"))
        val err = runCatching {
            LlmClient().streamChat(listOf(mapOf("role" to "user", "content" to "hi")), cfg()).toList()
        }.exceptionOrNull()
        assertTrue(err is LlmException)
        assertEquals(LlmError.Kind.SERVER, (err as LlmException).error.kind)
        assertTrue(!err.message.orEmpty().contains("provider body"))
    }

    @Test
    fun `HTTP 599 response is SERVER and not NETWORK`() = runTest {
        server.enqueue(MockResponse().setResponseCode(599).setBody("provider body must not escape"))

        val err = runCatching {
            LlmClient().streamChat(
                listOf(mapOf("role" to "user", "content" to "hi")),
                cfg(),
            ).toList()
        }.exceptionOrNull()

        assertTrue(err is LlmException)
        assertEquals(LlmError.Kind.SERVER, (err as LlmException).error.kind)
        assertEquals("SERVER", err.message)
    }

    @Test
    fun `401 is classified as authentication failure`() = runTest {
        assertHttpError(401, "{\"error\":{\"code\":\"invalid_api_key\"}}", LlmError.Kind.AUTH)
    }

    @Test
    fun `403 is classified as forbidden rather than authentication failure`() = runTest {
        assertHttpError(403, "{\"error\":{\"code\":\"insufficient_scope\"}}", LlmError.Kind.FORBIDDEN)
    }

    @Test
    fun `404 endpoint is classified as not found`() = runTest {
        assertHttpError(404, "{\"detail\":\"route not found\"}", LlmError.Kind.NOT_FOUND)
    }

    @Test
    fun `404 model error is classified as unsupported model`() = runTest {
        assertHttpError(
            404,
            "{\"error\":{\"code\":\"model_not_found\",\"message\":\"model does not exist\"}}",
            LlmError.Kind.MODEL_UNSUPPORTED,
        )
    }

    @Test
    fun `429 rate limit is distinct from quota exhaustion`() = runTest {
        assertHttpError(
            429,
            "{\"error\":{\"code\":\"rate_limit_exceeded\",\"message\":\"too many requests\"}}",
            LlmError.Kind.RATE_LIMIT,
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("{\"error\":{\"code\":\"insufficient_quota\",\"message\":\"quota exhausted\"}}"),
        )
        val quota = runCatching {
            LlmClient().streamChat(
                listOf(mapOf("role" to "user", "content" to "hi")),
                cfg(),
            ).toList()
        }.exceptionOrNull()
        assertTrue(quota is LlmException)
        assertEquals(LlmError.Kind.QUOTA_EXHAUSTED, (quota as LlmException).error.kind)
    }

    @Test
    fun `rate limit preserves a bounded retry after hint`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "3")
                .setBody("{\"error\":{\"code\":\"rate_limit_exceeded\"}}"),
        )
        val err = runCatching {
            LlmClient().streamChat(
                listOf(mapOf("role" to "user", "content" to "hi")),
                cfg(),
            ).toList()
        }.exceptionOrNull()

        assertTrue(err is LlmException)
        assertEquals(LlmError.Kind.RATE_LIMIT, (err as LlmException).error.kind)
        assertEquals(3_000L, err.error.retryAfterMs)
    }

    @Test
    fun `bad request naming an unsupported model is not generic config`() = runTest {
        assertHttpError(
            400,
            "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"model not supported\"}}",
            LlmError.Kind.MODEL_UNSUPPORTED,
        )
    }

    @Test
    fun `unknown host is classified as DNS`() = runTest {
        val dnsClient = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    throw UnknownHostException("synthetic DNS failure")
            })
            .build()
        val err = runCatching {
            LlmClient(dnsClient).streamChat(
                listOf(mapOf("role" to "user", "content" to "hi")),
                LlmConfig("http://dns.test/v1", "sk-test", "gpt-4o-mini"),
            ).toList()
        }.exceptionOrNull()

        assertTrue(err is LlmException)
        assertEquals(LlmError.Kind.DNS, (err as LlmException).error.kind)
    }

    @Test
    fun `socket timeout is classified as timeout`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("data: [DONE]\n\n")
                .setBodyDelay(1, TimeUnit.SECONDS),
        )
        val timeoutClient = OkHttpClient.Builder()
            .readTimeout(50, TimeUnit.MILLISECONDS)
            .build()
        val err = runCatching {
            LlmClient(timeoutClient).streamChat(
                listOf(mapOf("role" to "user", "content" to "hi")),
                cfg(),
            ).toList()
        }.exceptionOrNull()

        assertTrue(err is LlmException)
        assertEquals(LlmError.Kind.TIMEOUT, (err as LlmException).error.kind)
    }

    @Test
    fun `OkHttp connection IOException is NETWORK`() = runTest {
        val err = runCatching {
            LlmClient().streamChat(
                listOf(mapOf("role" to "user", "content" to "hi")),
                LlmConfig("http://127.0.0.1:1/v1", "sk-test", "gpt-4o-mini"),
            ).toList()
        }.exceptionOrNull()

        assertTrue(err is LlmException)
        assertEquals(LlmError.Kind.NETWORK, (err as LlmException).error.kind)
        assertEquals("NETWORK", err.message)
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
    fun `structured output config sends JSON object response format`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("data: [DONE]\n\n"))

        LlmClient().streamChat(
            listOf(mapOf("role" to "user", "content" to "name")),
            cfg().copy(responseFormatJsonObject = true),
        ).toList()

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
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

    private suspend fun assertHttpError(status: Int, body: String, expected: LlmError.Kind) {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
        val err = runCatching {
            LlmClient().streamChat(
                listOf(mapOf("role" to "user", "content" to "hi")),
                cfg(),
            ).toList()
        }.exceptionOrNull()

        assertTrue(err is LlmException)
        assertEquals(expected, (err as LlmException).error.kind)
        assertEquals(expected.name, err.message)
    }
}
