package com.classsentinel.core.speech

import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        assertFalse(checker.isReady(ModelProfiles.ZIPFORMER_ZH_14M))
        assertFalse(checker.isReady(ModelProfiles.ZIPFORMER_ZH_14M))
        assertEquals(1, probes)

        checker.invalidate(ModelProfiles.ZIPFORMER_ZH_14M)

        assertTrue(checker.isReady(ModelProfiles.ZIPFORMER_ZH_14M))
        assertEquals(2, probes)
        root.deleteRecursively()
    }
}
