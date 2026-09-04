package com.classsentinel.core.study

import com.classsentinel.core.llm.LlmConfig
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BilingualSummaryTest {
    private val config = LlmConfig(
        baseUrl = "https://llm.invalid/v1",
        apiKey = "test-key",
        model = "test-model",
    )

    @Test
    fun `translation preserves exact original in result and persisted JSON`() = runBlocking {
        val original = "The Fourier transform maps a signal from time domain to frequency domain."
        val generator = StudyArtifactGenerator(
            streamChat = { _, _ -> flowOf("{\"translation\":\"傅里叶变换把信号从时域映射到频域。\"}") },
        )

        val result = generator.translateMarkedText(original, config)

        assertTrue(result is StudyGenerationResult.Success<*>)
        val success = result as StudyGenerationResult.Success<BilingualOutput>
        assertEquals(original, success.value.original)
        assertEquals(
            BilingualOutput(original, "傅里叶变换把信号从时域映射到频域。"),
            StudyArtifactGenerator.parseBilingual(success.contentJson),
        )
        assertFalse(success.contentJson.contains("replacement-original"))
    }

    @Test
    fun `empty marked text returns safe status without calling LLM`() = runBlocking {
        var calls = 0
        val generator = StudyArtifactGenerator(
            streamChat = { _, _ ->
                calls++
                flowOf("must not be called")
            },
        )

        val result = generator.translateMarkedText(" \n", config)

        assertEquals(StudyGenerationResult.Failed(StudyArtifactGenerator.ERROR_EMPTY_SOURCE), result)
        assertEquals(0, calls)
    }

    @Test
    fun `provider failure returns status and never replaces original`() = runBlocking {
        val original = "已标记的原文"
        val generator = StudyArtifactGenerator(
            streamChat = { _, _ -> flow { throw IllegalStateException("provider body") } },
        )

        val result = generator.translateMarkedText(original, config)

        assertEquals(StudyGenerationResult.Failed(StudyArtifactGenerator.ERROR_GENERATION), result)
        val preserved = StudyArtifactGenerator.encodeBilingual(original, null)
        assertEquals(original, StudyArtifactGenerator.parseBilingual(preserved).original)
        assertEquals(null, StudyArtifactGenerator.parseBilingual(preserved).translation)
        assertFalse(result.toString().contains("provider body"))
    }

    @Test
    fun `translation output length budget is enforced`() = runBlocking {
        val original = "marked text"
        val generator = StudyArtifactGenerator(
            streamChat = { _, _ -> flowOf("{\"translation\":\"三个字\"}") },
        )

        val result = generator.translateMarkedText(original, config, maxTranslationChars = 2)

        assertEquals(
            StudyGenerationResult.Failed(StudyArtifactGenerator.ERROR_OUTPUT_TOO_LONG),
            result,
        )
    }

    @Test
    fun `bilingual summary keeps full original even when source is compressed`() = runBlocking {
        val original = "课堂原文".repeat(1_100)
        val requests = mutableListOf<List<Map<String, String>>>()
        val generator = StudyArtifactGenerator(
            streamChat = { messages, _ ->
                requests += messages
                if (messages.first()["content"].orEmpty().contains("压缩")) {
                    flowOf("复习要点")
                } else {
                    flowOf("{\"translation\":\"双语总结\"}")
                }
            },
        )

        val result = generator.generateBilingualSummary(original, config)

        assertTrue(result is StudyGenerationResult.Success<*>)
        assertEquals(original, (result as StudyGenerationResult.Success<BilingualOutput>).value.original)
        assertEquals(3, requests.size)
    }
}
