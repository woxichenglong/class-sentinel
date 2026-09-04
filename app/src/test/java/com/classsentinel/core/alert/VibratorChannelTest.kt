package com.classsentinel.core.alert

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class VibratorChannelTest {

    @Test
    fun `vibration modes map to distinct bounded waveforms`() {
        assertArrayEquals(longArrayOf(0L, 120L, 100L, 120L), vibrationPattern("gentle"))
        assertArrayEquals(longArrayOf(0L, 300L, 150L, 300L, 150L, 600L), vibrationPattern("normal"))
        assertArrayEquals(longArrayOf(0L, 500L, 100L, 500L, 100L, 900L), vibrationPattern("strong"))
    }

    @Test
    fun `unknown vibration mode falls back to normal`() {
        assertArrayEquals(vibrationPattern("normal"), vibrationPattern("not-a-mode"))
    }
}
