package com.classsentinel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingConsumerMatrixTest {

    @Test
    fun `every visible setting has exactly one documented production consumer`() {
        assertEquals(
            SettingConsumerMatrix.visibleKeys,
            SettingConsumerMatrix.consumers.keys,
        )
        assertTrue(SettingConsumerMatrix.consumers.values.all { it.isNotBlank() })
        assertFalse(SettingConsumerMatrix.visibleKeys.contains("ringtoneVolume"))
    }

    @Test
    fun `student settings omit retired audio and extension controls`() {
        val retired = setOf(
            "vadDb",
            "segmentMaxSec",
            "asrEngine",
            "channel.ringtone",
            "channel.flash",
            "channel.ear",
            "lockscreenNotify",
            "autoSummary",
            "summaryTemplate",
            "retentionDays",
            "audioRetentionPolicy",
        )

        assertTrue(retired.none { it in SettingConsumerMatrix.visibleKeys })
        assertTrue("ai.baseUrl" in SettingConsumerMatrix.visibleKeys)
        assertTrue("ai.apiKey" in SettingConsumerMatrix.visibleKeys)
        assertTrue("ai.model" in SettingConsumerMatrix.visibleKeys)
        assertTrue("channel.notify" in SettingConsumerMatrix.visibleKeys)
        assertEquals(
            "SettingsRepository → ListenServiceHandleFactory/SherpaOnnxStreamingEngine",
            SettingConsumerMatrix.consumers["localAsrModel"],
        )
    }
}
