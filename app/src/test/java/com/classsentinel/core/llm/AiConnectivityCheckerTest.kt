package com.classsentinel.core.llm

import com.classsentinel.data.AiSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiConnectivityCheckerTest {

    private val settings = AiSettings(
        baseUrl = "https://example.test/v1",
        apiKey = "configured-test-key",
        model = "test-model",
    )

    @Test
    fun `fixed structured check succeeds without sending a personal name`() = runTest {
        var capturedMessages: List<Map<String, String>>? = null
        var capturedConfig: LlmConfig? = null
        val checker = checker(chat = { messages, config ->
            capturedMessages = messages
            capturedConfig = config
            flowOf("{\"ok\":true}")
        })

        assertEquals(AiConnectivityResult.Success, checker.check(settings))
        assertTrue(capturedConfig!!.responseFormatJsonObject)
        assertEquals(8, capturedConfig!!.maxTokens)
        val requestText = capturedMessages!!.joinToString("\n") { it["content"].orEmpty() }
        assertTrue(requestText.contains("连接检查"))
        assertFalse(requestText.contains("梁津淦"))
    }

    @Test
    fun `default checker sends a minimal real completion request`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"ok\\\":true}\"}}]}\n\n" +
                            "data: [DONE]\n\n",
                    )
                    .addHeader("Content-Type", "text/event-stream"),
            )
            val transport = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val redirected = chain.request().newBuilder()
                        .url(server.url("/v1/chat/completions"))
                        .build()
                    chain.proceed(redirected)
                }
                .build()
            val checker = LlmAiConnectivityChecker(
                client = LlmClient(transport),
                retryPolicy = AiConnectivityRetryPolicy(maxAttempts = 1),
            )

            assertEquals(AiConnectivityResult.Success, checker.check(settings))

            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.path)
            val body = JSONObject(request.body.readUtf8())
            assertEquals(true, body.getBoolean("stream"))
            assertEquals(8, body.getInt("max_tokens"))
            assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
            assertTrue(body.getJSONArray("messages").getJSONObject(1).getString("content").contains("固定连接检查"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `false or malformed structured response fails safely`() = runTest {
        val falseResult = checker(chat = { _, _ -> flowOf("{\"ok\":false}") }).check(settings)
        val malformedResult = checker(chat = { _, _ -> flowOf("not-json") }).check(settings)

        assertEquals(
            AiConnectivityResult.Failure(AiSetupFailure.INVALID_RESPONSE),
            falseResult,
        )
        assertEquals(
            AiConnectivityResult.Failure(AiSetupFailure.INVALID_RESPONSE),
            malformedResult,
        )
    }

    @Test
    fun `provider failure is mapped to safe setup failure`() = runTest {
        val result = checker(chat = { _, _ ->
            throw LlmException(LlmError(LlmError.Kind.AUTH))
        }).check(settings)

        assertEquals(AiConnectivityResult.Failure(AiSetupFailure.AUTH), result)
    }

    @Test
    fun `transient failure is retried and succeeds on the final bounded attempt`() = runTest {
        var calls = 0
        val result = checker(
            chat = { _, _ ->
                calls += 1
                if (calls < 3) {
                    throw LlmException(LlmError(LlmError.Kind.NETWORK))
                }
                flowOf("{\"ok\":true}")
            },
            policy = AiConnectivityRetryPolicy(
                maxAttempts = 3,
                requestTimeoutMs = 1_000,
                initialBackoffMs = 0,
            ),
        ).check(settings)

        assertEquals(AiConnectivityResult.Success, result)
        assertEquals(3, calls)
    }

    @Test
    fun `authentication failure is terminal and is not retried`() = runTest {
        var calls = 0
        val result = checker(
            chat = { _, _ ->
                calls += 1
                throw LlmException(LlmError(LlmError.Kind.AUTH))
            },
            policy = AiConnectivityRetryPolicy(maxAttempts = 3, initialBackoffMs = 0),
        ).check(settings)

        assertEquals(
            AiConnectivityResult.Failure(AiSetupFailure.AUTH, attempts = 1),
            result,
        )
        assertEquals(1, calls)
    }

    @Test
    fun `request deadline is reported as timeout rather than generic network failure`() = runTest {
        val result = checker(
            chat = { _, _ ->
                flow {
                    delay(1_000)
                    emit("{\"ok\":true}")
                }
            },
            policy = AiConnectivityRetryPolicy(
                maxAttempts = 1,
                requestTimeoutMs = 100,
                initialBackoffMs = 0,
            ),
        ).check(settings)

        assertEquals(
            AiConnectivityResult.Failure(AiSetupFailure.TIMEOUT, attempts = 1),
            result,
        )
    }

    @Test
    fun `caller cancellation is propagated instead of becoming a timeout result`() = runTest {
        val cancellation = CancellationException("caller cancelled")
        val error = runCatching {
            checker(
                chat = { _, _ -> flow { throw cancellation } },
                policy = AiConnectivityRetryPolicy(maxAttempts = 2, initialBackoffMs = 0),
            ).check(settings)
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals(cancellation.message, error?.message)
    }

    @Test
    fun `retry lifecycle exposes checking retrying checking and terminal failure`() = runTest {
        val states = mutableListOf<AiConnectivityCheckState>()
        val result = checker(
            chat = { _, _ ->
                throw LlmException(LlmError(LlmError.Kind.SERVER))
            },
            policy = AiConnectivityRetryPolicy(
                maxAttempts = 2,
                requestTimeoutMs = 1_000,
                initialBackoffMs = 0,
            ),
        ).check(settings) { states += it }

        assertEquals(AiConnectivityResult.Failure(AiSetupFailure.SERVER, attempts = 2), result)
        assertEquals(
            listOf(
                AiConnectivityCheckState.Checking(attempt = 1, maxAttempts = 2),
                AiConnectivityCheckState.Retrying(
                    attempt = 2,
                    maxAttempts = 2,
                    reason = AiSetupFailure.SERVER,
                ),
                AiConnectivityCheckState.Checking(attempt = 2, maxAttempts = 2),
                AiConnectivityCheckState.Failed(
                    reason = AiSetupFailure.SERVER,
                    attempts = 2,
                ),
            ),
            states,
        )
    }

    private fun checker(
        chat: ((List<Map<String, String>>, LlmConfig) -> Flow<String>),
        policy: AiConnectivityRetryPolicy = AiConnectivityRetryPolicy(),
    ): LlmAiConnectivityChecker = LlmAiConnectivityChecker(
        streamChat = chat,
        retryPolicy = policy,
    )
}
