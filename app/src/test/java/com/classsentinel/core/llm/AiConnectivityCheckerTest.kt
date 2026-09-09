package com.classsentinel.core.llm

import com.classsentinel.data.AiSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
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
        val checker = checker { messages, config ->
            capturedMessages = messages
            capturedConfig = config
            flowOf("{\"ok\":true}")
        }

        assertEquals(AiConnectivityResult.Success, checker.check(settings))
        assertTrue(capturedConfig!!.responseFormatJsonObject)
        assertEquals(32, capturedConfig!!.maxTokens)
        val requestText = capturedMessages!!.joinToString("\n") { it["content"].orEmpty() }
        assertTrue(requestText.contains("连接检查"))
        assertFalse(requestText.contains("梁津淦"))
    }

    @Test
    fun `false or malformed structured response fails safely`() = runTest {
        val falseResult = checker { _, _ -> flowOf("{\"ok\":false}") }.check(settings)
        val malformedResult = checker { _, _ -> flowOf("not-json") }.check(settings)

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
        val result = checker { _, _ ->
            throw LlmException(LlmError(LlmError.Kind.AUTH))
        }.check(settings)

        assertEquals(AiConnectivityResult.Failure(AiSetupFailure.AUTH), result)
    }

    private fun checker(
        chat: ((List<Map<String, String>>, LlmConfig) -> Flow<String>),
    ): LlmAiConnectivityChecker = LlmAiConnectivityChecker(streamChat = chat)
}
