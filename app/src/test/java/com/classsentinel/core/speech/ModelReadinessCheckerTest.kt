package com.classsentinel.core.speech

import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModelReadinessCheckerTest {

    @Test
    fun `readiness probe is cached and invalidated explicitly`() = runTest {
        val root = File("build/model-readiness-cache-test").apply { deleteRecursively() }
        var probes = 0
        val checker = ModelReadinessChecker(
            filesDir = root,
            dispatcher = StandardTestDispatcher(testScheduler),
            probe = { _, _ ->
                probes++
                probes > 1
            },
        )

        assertFalse(checker.isReady(ModelProfiles.PRODUCTION))
        assertFalse(checker.isReady(ModelProfiles.PRODUCTION))
        assertEquals(1, probes)

        checker.invalidate(ModelProfiles.PRODUCTION)

        assertTrue(checker.isReady(ModelProfiles.PRODUCTION))
        assertEquals(2, probes)
        root.deleteRecursively()
    }

    @Test
    fun `storage insufficiency keeps its stable error and does not open assets`() {
        val root = File("build/model-readiness-storage-test").apply { deleteRecursively() }
        var assetOpens = 0
        val checker = ModelReadinessChecker(
            filesDir = root,
            availableSpace = { 0L },
        )

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                checker.ensureReady(
                    profile = ModelProfiles.PRODUCTION,
                    assetOpener = {
                        assetOpens++
                        error("asset must not open")
                    },
                )
            }
        }

        assertEquals(ASR_MODEL_STORAGE_INSUFFICIENT, error.message)
        assertEquals(0, assetOpens)
        assertFalse(File(root, "asr/${ModelProfiles.PRODUCTION.artifact.directory}/.model-profile").exists())
        root.deleteRecursively()
    }
}
