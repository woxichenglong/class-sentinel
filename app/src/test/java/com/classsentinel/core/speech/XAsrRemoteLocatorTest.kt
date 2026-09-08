package com.classsentinel.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XAsrRemoteLocatorTest {

    @Test
    fun `480 and 960 expose all artifact locators at the pinned revision`() {
        listOf(ModelProfiles.X_ASR_480, ModelProfiles.X_ASR_960).forEach { profile ->
            val remote = profile.distribution as ModelDistribution.Remote
            assertEquals(profile.artifact.files.map { it.name }.toSet(), remote.files.keys)
            assertEquals(profile.artifact.files.size, remote.files.size)
            remote.files.forEach { (filename, locator) ->
                assertTrue(locator.isNotBlank())
                assertTrue(locator.startsWith("https://huggingface.co/GilgameshWind/X-ASR-zh-en/resolve/${profile.version}/"))
                assertTrue(locator.endsWith("/${profile.artifact.directory}/$filename?download=true"))
                assertFalse(locator.contains("/main/"))
                assertFalse(locator.contains("/latest/"))
            }
        }
    }

    @Test
    fun `480 and 960 use the same fixed revision without copying integrity metadata`() {
        assertEquals("689ff18c584d29910da37b6fe904db0c1489c9d1", ModelProfiles.X_ASR_480.version)
        assertEquals(ModelProfiles.X_ASR_480.version, ModelProfiles.X_ASR_960.version)
        assertEquals(
            ModelProfiles.X_ASR_480.artifact.files.drop(1).map { it.expectedSize },
            ModelProfiles.X_ASR_960.artifact.files.drop(1).map { it.expectedSize },
        )
        assertEquals(
            ModelProfiles.X_ASR_480.artifact.files.drop(1).map { it.sha256 },
            ModelProfiles.X_ASR_960.artifact.files.drop(1).map { it.sha256 },
        )
    }

    @Test
    fun `remote distribution rejects blank locations and artifact filename mismatches`() {
        val base = ModelProfiles.X_ASR_480
        assertThrows(IllegalArgumentException::class.java) {
            ModelDistribution.Remote(mapOf(base.artifact.files.first().name to ""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(
                distribution = ModelDistribution.Remote(
                    mapOf("not-an-artifact.onnx" to "https://example.test/file"),
                ),
            )
        }
    }
}
