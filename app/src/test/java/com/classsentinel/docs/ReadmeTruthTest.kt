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
        assertTrue(readme.contains("ModelProfiles.PRODUCTION"))
        assertTrue(readme.contains("x-asr-480"))
        assertTrue(readme.contains("不提供下载或选择"))
        assertFalse(readme.contains("sherpa-zh-14m"))
        assertFalse(readme.contains("sherpa-small-bilingual-zh-en"))
        assertFalse(readme.contains("x-asr-960"))
        assertTrue(readme.contains("Android CI"))
        assertTrue(readme.contains("test-results/testDebugUnitTest"))
        assertTrue(readme.contains("sha256sum"))
        assertTrue(readme.contains("stat"))
        assertFalse(readme.contains("90 个测试类、502 个用例"))
        assertFalse(readme.contains("223,657,379"))
        assertFalse(readme.contains("8601c32c8b138af369f2493ecf3edfa8b6fbc039c0b6bd022c26d2cfae1f00d7"))
    }

    @Test
    fun `settings exposes only the bundled X ASR model fact`() {
        val settings = readRepositoryFile("app/src/main/java/com/classsentinel/ui/screens/SettingsHubScreen.kt")

        assertTrue(settings.contains("语音识别模型"))
        assertTrue(settings.contains("X-ASR 中英增强模型"))
        assertTrue(settings.contains("已内置 · 离线可用"))
        assertFalse(settings.contains("ModelDownload"))
        assertFalse(settings.contains("preferredLocalModel"))
        assertFalse(settings.contains("使用此模型"))
        assertFalse(settings.contains("继续下载"))
        assertFalse(settings.contains("取消下载"))
        assertFalse(settings.contains("Remote"))
    }

    @Test
    fun `onboarding and settings do not advertise a nonexistent overlay permission`() {
        val sourceFiles = listOf(
            readRepositoryFile("app/src/main/java/com/classsentinel/ui/screens/OnboardingScreen.kt"),
            readRepositoryFile("app/src/main/java/com/classsentinel/ui/screens/SettingsHubScreen.kt"),
        )
        val source = sourceFiles.joinToString("\n")

        assertFalse(source.contains("悬浮窗权限"))
        assertFalse(source.contains("浮窗回答"))
        assertFalse(source.contains("SYSTEM_ALERT_WINDOW"))
        assertFalse(source.contains("ACTION_MANAGE_OVERLAY_PERMISSION"))
    }

    @Test
    fun `production route has no legacy settings screen implementation`() {
        val app = readRepositoryFile("app/src/main/java/com/classsentinel/ui/ClassSentinelApp.kt")

        assertTrue(app.contains("SettingsHubScreen()"))
        assertFalse(app.contains("import com.classsentinel.ui.screens.SettingsScreen"))
        assertFalse(repositoryFile("app/src/main/java/com/classsentinel/ui/screens/SettingsScreen.kt").isFile)
    }

    private fun readRepositoryFile(relativePath: String): String {
        val file = repositoryFile(relativePath)
        if (file.isFile) return file.readText()
        error("Repository file not found: $relativePath")
    }

    private fun repositoryFile(relativePath: String): File {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "README.md").isFile && File(it, "app").isDirectory }
            ?: error("Repository root not found")
        return File(root, relativePath)
    }
}
