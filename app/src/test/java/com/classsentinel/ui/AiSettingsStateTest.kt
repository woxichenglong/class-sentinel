package com.classsentinel.ui

import com.classsentinel.core.config.AppConfig
import com.classsentinel.data.SettingsRepository
import com.classsentinel.ui.screens.defaultAiSettingsForUi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

class AiSettingsStateTest {

    private var previousAsrKey: String = ""

    @Before
    fun setUp() {
        previousAsrKey = AppConfig.siliconApiKey
    }

    @After
    fun tearDown() {
        AppConfig.siliconApiKey = previousAsrKey
    }

    @Test
    fun `settings screen initial AI state comes from AI defaults not ASR config`() {
        AppConfig.siliconApiKey = "asr-only-key"

        val initial = defaultAiSettingsForUi()

        assertEquals(SettingsRepository.DEFAULT_AI_SETTINGS, initial)
        assertNotEquals(AppConfig.siliconApiKey, initial.apiKey)
        assertEquals("", initial.apiKey)
    }
}
