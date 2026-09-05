package com.classsentinel.core.speech

import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalListenStartPreflightTest {

    @Test
    fun `shared preflight delegates local model readiness and preparation`() = runTest {
        val root = File("build/local-listen-preflight-test").apply { deleteRecursively() }
        var probes = 0
        val checker = ModelReadinessChecker(
            filesDir = root,
            dispatcher = StandardTestDispatcher(testScheduler),
            probe = { _, _ ->
                probes++
                true
            },
        )
        val preflight = LocalListenStartPreflight(
            readinessChecker = checker,
            assetOpener = { error("asset opener must not run for an already-ready model") },
        )

        assertTrue(preflight.isReady(ModelProfiles.ZIPFORMER_ZH_14M))
        assertTrue(preflight.ensureReady(ModelProfiles.ZIPFORMER_ZH_14M))
        assertEquals(1, probes)
        root.deleteRecursively()
    }
}
