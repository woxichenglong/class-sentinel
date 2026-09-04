package com.classsentinel.core.llm

import com.classsentinel.data.AiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderPresetTest {

    @Test
    fun `Command Code preset uses the configured endpoint and v4 flash model`() {
        val preset = AiProviderPreset.COMMAND_CODE

        assertEquals("Command Code", preset.label)
        assertEquals("https://api.commandcode.ai/provider/v1", preset.baseUrl)
        assertEquals("deepseek/deepseek-v4-flash", preset.model)
        assertTrue(preset.thinkingDisabled)
    }

    @Test
    fun `built in presets keep all supported providers`() {
        assertEquals(
            listOf(
                AiProviderPreset.DEEPSEEK_OFFICIAL,
                AiProviderPreset.SILICON_FLOW,
                AiProviderPreset.COMMAND_CODE,
            ),
            AiProviderPreset.BUILT_INS,
        )
        assertTrue(AiProviderPreset.BUILT_INS.all { it.baseUrl.startsWith("https://") })
        assertTrue(AiProviderPreset.BUILT_INS.all { it.model.isNotBlank() })
    }

    @Test
    fun `normalization trims whitespace and a trailing slash without changing the key`() {
        val normalized = AiProviderPreset.normalizeSettings(
            AiSettings(
                baseUrl = " https://example.test/v1/ ",
                apiKey = " key-for-ai ",
                model = " model-name ",
            ),
        )

        assertEquals("https://example.test/v1", normalized.baseUrl)
        assertEquals("key-for-ai", normalized.apiKey)
        assertEquals("model-name", normalized.model)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `normalization rejects non HTTPS endpoints`() {
        AiProviderPreset.normalizeSettings(AiSettings("http://example.test/v1", "key", "model"))
    }

    @Test
    fun `validation rejects blank model`() {
        assertFalse(AiProviderPreset.isValid("https://example.test/v1", " "))
        assertTrue(AiProviderPreset.isValid("https://example.test/v1", "model"))
    }

    @Test
    fun `preset conversion only uses the explicitly supplied AI key`() {
        val settings = AiProviderPreset.COMMAND_CODE.toAiSettings(apiKey = "ai-key")

        assertEquals("ai-key", settings.apiKey)
        assertEquals("deepseek/deepseek-v4-flash", settings.model)
    }
}
