package com.classsentinel.worker

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrSettingsActionCoordinatorTest {
    @Test
    fun `successful nonblank key save resumes after persistence`() = runBlocking {
        val events = mutableListOf<String>()
        val coordinator = AsrSettingsActionCoordinator(
            persistSiliconKey = { events += "save-key:$it" },
            persistEngine = { events += "save-engine:$it" },
            currentEngine = { "telespeech" },
            isRecoveryReady = { true },
            resumeAfterConfigChange = {
                events += "resume"
                true
            },
        )

        assertTrue(coordinator.saveSiliconKey(" key "))
        assertEquals(listOf("save-key:key", "resume"), events)
    }

    @Test
    fun `save failure and blank key never resume`() = runBlocking {
        var resumeCount = 0
        val coordinator = AsrSettingsActionCoordinator(
            persistSiliconKey = { key ->
                if (key == "bad") error("persist failed")
            },
            persistEngine = {},
            currentEngine = { "telespeech" },
            isRecoveryReady = { true },
            resumeAfterConfigChange = {
                resumeCount++
                true
            },
        )

        try {
            coordinator.saveSiliconKey("bad")
            error("expected persistence failure")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertFalse(coordinator.saveSiliconKey("   "))
        assertEquals(0, resumeCount)
    }

    @Test
    fun `ready engine switch resumes but unconfigured provider does not`() = runBlocking {
        var resumeCount = 0
        val coordinator = AsrSettingsActionCoordinator(
            persistSiliconKey = {},
            persistEngine = {},
            currentEngine = { "telespeech" },
            isRecoveryReady = { engine -> engine != "xunfei" },
            resumeAfterConfigChange = {
                resumeCount++
                true
            },
        )

        assertTrue(coordinator.saveEngine("sensevoice"))
        assertFalse(coordinator.saveEngine("xunfei"))
        assertEquals(1, resumeCount)
    }
}
