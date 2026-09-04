package com.classsentinel.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePreferenceTest {

    @Test
    fun `theme preference resolves explicit and system modes`() {
        assertTrue(darkThemeForPreference("on", systemIsDark = false))
        assertFalse(darkThemeForPreference("off", systemIsDark = true))
        assertTrue(darkThemeForPreference("system", systemIsDark = true))
        assertFalse(darkThemeForPreference("system", systemIsDark = false))
    }

    @Test
    fun `unknown theme preference follows system safely`() {
        assertTrue(darkThemeForPreference("invalid", systemIsDark = true))
        assertFalse(darkThemeForPreference("invalid", systemIsDark = false))
    }
}
