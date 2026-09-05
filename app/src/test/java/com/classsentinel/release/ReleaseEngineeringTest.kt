package com.classsentinel.release

import java.io.File
import java.util.regex.Pattern
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseEngineeringTest {

    @Test
    fun `ordinary ci is read only and pins every action`() {
        val workflow = readRepositoryFile(".github/workflows/android-ci.yml")

        assertTrue(workflow.contains("permissions:\n  contents: read"))
        assertTrue(workflow.contains("actions/checkout@d23441a48e516b6c34aea4fa41551a30e30af803"))
        assertTrue(workflow.contains("actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961"))
        assertTrue(workflow.contains("gradle/actions/setup-gradle@9c971963bec38e04b3d30dcc455b5382be2fdbfb"))
        assertTrue(workflow.contains("testDebugUnitTest"))
        assertTrue(workflow.contains("lintDebug"))
        assertTrue(workflow.contains("assembleDebug"))
        assertFalse(FLOATING_ACTION_PATTERN.matcher(workflow).find())
    }

    @Test
    fun `release workflow is tag only and signs from secrets without printing them`() {
        val workflow = readRepositoryFile(".github/workflows/android-release.yml")

        assertTrue(workflow.contains("tags:"))
        assertTrue(workflow.contains("v*.*.*"))
        assertTrue(workflow.contains("permissions:\n  contents: write"))
        assertTrue(workflow.contains("assembleRelease"))
        assertTrue(workflow.contains("ANDROID_KEYSTORE_BASE64"))
        assertTrue(workflow.contains("ANDROID_KEYSTORE_PASSWORD"))
        assertTrue(workflow.contains("ANDROID_KEY_ALIAS"))
        assertTrue(workflow.contains("ANDROID_KEY_PASSWORD"))
        assertTrue(workflow.contains("sha256sum"))
        assertTrue(workflow.contains("actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02"))
        assertTrue(workflow.contains("gh release create"))
        assertFalse(workflow.contains("set -x"))
        assertFalse(FLOATING_ACTION_PATTERN.matcher(workflow).find())
    }

    @Test
    fun `gradle release signing is environment driven and minify remains an explicit decision`() {
        val gradle = readRepositoryFile("app/build.gradle.kts")

        assertTrue(gradle.contains("signingConfigs"))
        assertTrue(gradle.contains("ANDROID_KEYSTORE_PATH"))
        assertTrue(gradle.contains("ANDROID_KEYSTORE_PASSWORD"))
        assertTrue(gradle.contains("ANDROID_KEY_ALIAS"))
        assertTrue(gradle.contains("ANDROID_KEY_PASSWORD"))
        assertTrue(gradle.contains("isMinifyEnabled = false"))
        assertTrue(gradle.contains("verifyReleaseSigning"))
        assertTrue(readRepositoryFile(".gitignore").contains("*.jks"))
        assertTrue(readRepositoryFile(".gitignore").contains(".env"))
    }

    @Test
    fun `readme distinguishes release download from debug test artifact`() {
        val readme = readRepositoryFile("README.md")

        assertTrue(readme.contains("app-release.apk"))
        assertTrue(readme.contains("debug APK 只用于测试"))
        assertTrue(readme.contains("release APK"))
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

    private companion object {
        val FLOATING_ACTION_PATTERN = Pattern.compile("uses:\\s+[^\\s]+@v[0-9].*")
    }
}
