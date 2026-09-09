package com.classsentinel.core.llm

import com.classsentinel.data.AiSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameVariantGeneratorTest {

    @Test
    fun `normal JSON is parsed with structured request contract`() = runTest {
        var capturedMessages: List<Map<String, String>>? = null
        var capturedConfig: LlmConfig? = null
        val generator = generator(
            raw = json(listOf("梁津干", "梁金干", "良津干")),
            chat = { messages, config ->
                capturedMessages = messages
                capturedConfig = config
                flowOf(json(listOf("梁津干", "梁金干", "良津干")))
            },
        )

        val result = generator.generate("梁津淦")

        assertEquals(
            listOf("梁津干", "梁金干", "良津干"),
            (result as NameVariantGenerationResult.Success).asrVariants,
        )
        assertTrue(capturedConfig!!.responseFormatJsonObject)
        assertEquals(256, capturedConfig!!.maxTokens)
        val systemPrompt = capturedMessages!!.first { it["role"] == "system" }["content"].orEmpty()
        assertTrue(systemPrompt.contains("仅用于 ASR 容错"))
        assertTrue(systemPrompt.contains("禁止生成：昵称、别名、外号、英文名"))
        assertTrue(systemPrompt.contains("宁缺毋滥"))
    }

    @Test
    fun `sanitation trims deduplicates removes display empty and obvious non-ASR values`() = runTest {
        val raw = json(
            listOf(
                " 梁津干 ",
                "梁津干",
                "梁津淦",
                "",
                "梁先生",
                "阿淦",
                "梁金刚",
                "梁@干",
                "梁津干这是很长的一整句文本",
            ),
        )

        val result = generator(raw).generate("梁津淦")

        assertEquals(
            listOf("梁津干"),
            (result as NameVariantGenerationResult.Success).asrVariants,
        )
    }

    @Test
    fun `sanitation keeps at most ten candidates`() = runTest {
        val candidates = (0..11).map { index -> "甲乙${('甲'.code + index).toChar()}" }
        val result = generator(json(candidates)).generate("梁津淦")

        assertEquals(10, (result as NameVariantGenerationResult.Success).asrVariants.size)
        assertEquals(candidates.take(10), result.asrVariants)
    }

    @Test
    fun `empty filtered result is generation failure`() = runTest {
        val result = generator(json(listOf("梁津淦", "阿淦", "梁@干"))).generate("梁津淦")

        assertEquals(
            NameVariantFailureCode.EMPTY_RESPONSE,
            (result as NameVariantGenerationResult.Failure).code,
        )
    }

    @Test
    fun `invalid JSON is generation failure`() = runTest {
        val result = generator("not-json").generate("梁津淦")

        assertEquals(
            NameVariantFailureCode.INVALID_JSON,
            (result as NameVariantGenerationResult.Failure).code,
        )
    }

    @Test
    fun `network error is generation failure`() = runTest {
        val result = generator(
            chat = { _, _ -> throw LlmException(LlmError(LlmError.Kind.NETWORK)) },
        ).generate("梁津淦")

        assertEquals(
            NameVariantFailureCode.NETWORK,
            (result as NameVariantGenerationResult.Failure).code,
        )
    }

    @Test
    fun `request timeout is generation failure`() = runTest {
        val result = generator(
            chat = { _, _ ->
                flow {
                    delay(20_000)
                    emit(json(listOf("梁津干")))
                }
            },
        ).generate("梁津淦")

        assertEquals(
            NameVariantFailureCode.TIMEOUT,
            (result as NameVariantGenerationResult.Failure).code,
        )
    }

    @Test
    fun `missing API key fails locally without invoking network`() = runTest {
        var chatCalled = false
        val generator = LlmNameVariantGenerator(
            settingsProvider = {
                AiSettings(
                    baseUrl = "https://example.test/v1",
                    apiKey = "",
                    model = "test-model",
                )
            },
            streamChat = { _, _ ->
                chatCalled = true
                flowOf(json(listOf("梁津干")))
            },
        )

        val result = generator.generate("梁津淦")

        assertEquals(
            NameVariantFailureCode.CONFIG,
            (result as NameVariantGenerationResult.Failure).code,
        )
        assertFalse(chatCalled)
    }

    private fun generator(
        raw: String = json(listOf("梁津干")),
        chat: ((List<Map<String, String>>, LlmConfig) -> Flow<String>)? = null,
    ): LlmNameVariantGenerator = LlmNameVariantGenerator(
        settingsProvider = {
            AiSettings(
                baseUrl = "https://example.test/v1",
                apiKey = "configured-test-key",
                model = "test-model",
            )
        },
        streamChat = chat ?: { _, _ -> flowOf(raw) },
    )

    private fun json(values: List<String>): String =
        JSONObject().put("asrVariants", JSONArray(values)).toString()
}
