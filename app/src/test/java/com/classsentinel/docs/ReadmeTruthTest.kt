package com.classsentinel.docs

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadmeTruthTest {

    @Test
    fun `readme describes the current notification and app card boundary`() {
        val readme = readRepositoryFile("README.md")

        assertTrue(readme.contains("系统通知"))
        assertTrue(readme.contains("App 内答案卡"))
        assertFalse(readme.contains("悬浮窗权限"))
        assertFalse(readme.contains("浮窗回答"))
        assertFalse(readme.contains("浮窗答案"))
        assertFalse(readme.contains("SYSTEM_ALERT_WINDOW"))
        assertFalse(readme.contains("overlay service"))
    }

    @Test
    fun `readme records current schema catalog ci and generated artifact checks`() {
        val readme = readRepositoryFile("README.md")

        assertTrue(readme.contains("Room schema v5"))
        assertTrue(readme.contains("MIGRATION_1_2"))
        assertTrue(readme.contains("MIGRATION_2_3"))
        assertTrue(readme.contains("MIGRATION_3_4"))
        assertTrue(readme.contains("MIGRATION_4_5"))
        assertTrue(readme.contains("sherpa-zh-14m"))
        assertTrue(readme.contains("sherpa-small-bilingual-zh-en"))
        assertTrue(readme.contains("x-asr-480"))
        assertTrue(readme.contains("x-asr-960"))
        assertTrue(readme.contains("Android CI"))
        assertTrue(readme.contains("test-results/testDebugUnitTest"))
        assertTrue(readme.contains("sha256sum"))
        assertTrue(readme.contains("stat"))
        assertFalse(readme.contains("90 个测试类、502 个用例"))
        assertFalse(readme.contains("223,657,379"))
        assertFalse(readme.contains("8601c32c8b138af369f2493ecf3edfa8b6fbc039c0b6bd022c26d2cfae1f00d7"))
    }

    @Test
    fun `onboarding and settings do not advertise a nonexistent overlay permission`() {
        val sourceFiles = listOf(
            readRepositoryFile("app/src/main/java/com/classsentinel/ui/screens/OnboardingScreen.kt"),
            readRepositoryFile("app/src/main/java/com/classsentinel/ui/screens/SettingsScreen.kt"),
        )
        val source = sourceFiles.joinToString("\n")

        assertFalse(source.contains("悬浮窗权限"))
        assertFalse(source.contains("浮窗回答"))
        assertFalse(source.contains("SYSTEM_ALERT_WINDOW"))
        assertFalse(source.contains("ACTION_MANAGE_OVERLAY_PERMISSION"))
    }

    private fun readRepositoryFile(relativePath: String): String {
        var directory: File? = File(System.getProperty("user.dir") ?: ".")
        while (directory != null) {
            val file = File(directory, relativePath)
            if (file.isFile) return file.readText()
            directory = directory.parentFile
        }
        error("Repository file not found: $relativePath")
    }
}
