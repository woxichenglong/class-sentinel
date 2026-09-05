package com.classsentinel.worker

import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.WorkInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingRecoveryResumeCoordinatorTest {
    @After
    fun cleanProductionSeam() {
        runBlocking {
            runCatching {
                val app = RuntimeEnvironment.getApplication()
                WorkManager.getInstance(app).cancelUniqueWork(PendingTranscriptionWorker.UNIQUE_WORK_NAME)
            }
        }
    }

    @Test
    fun `failed global work with pending rows enqueues replacement`() = runBlocking {
        var enqueueCount = 0
        val coordinator = PendingRecoveryResumeCoordinator(
            pendingCount = { 2 },
            workStates = { listOf(WorkInfo.State.FAILED) },
            enqueueAfterGlobalFailure = { enqueueCount++ },
        )

        assertTrue(coordinator.resumeAfterAsrConfigChange())
        assertEquals(1, enqueueCount)
    }

    @Test
    fun `no pending rows has no work manager side effect`() = runBlocking {
        var workStateRead = false
        var enqueueCount = 0
        val coordinator = PendingRecoveryResumeCoordinator(
            pendingCount = { 0 },
            workStates = {
                workStateRead = true
                listOf(WorkInfo.State.FAILED)
            },
            enqueueAfterGlobalFailure = { enqueueCount++ },
        )

        assertFalse(coordinator.resumeAfterAsrConfigChange())
        assertFalse(workStateRead)
        assertEquals(0, enqueueCount)
    }

    @Test
    fun `healthy running work is not replaced`() = runBlocking {
        var enqueueCount = 0
        val coordinator = PendingRecoveryResumeCoordinator(
            pendingCount = { 1 },
            workStates = { listOf(WorkInfo.State.RUNNING) },
            enqueueAfterGlobalFailure = { enqueueCount++ },
        )

        assertFalse(coordinator.resumeAfterAsrConfigChange())
        assertEquals(0, enqueueCount)
    }

    @Test
    fun `settings action reaches production scheduler and real work manager`() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        runCatching { WorkManagerTestInitHelper.initializeTestWorkManager(app) }
        val workManager = WorkManager.getInstance(app)
        val resume = PendingRecoveryResumeCoordinator(
            pendingCount = { 1 },
            workStates = { emptyList() },
            enqueueAfterGlobalFailure = {
                PendingTranscriptionWorker.enqueueAfterGlobalFailure(workManager)
            },
        )
        var persistedKey = ""
        val settingsAction = AsrSettingsActionCoordinator(
            persistSiliconKey = { persistedKey = it },
            persistEngine = {},
            currentEngine = { "telespeech" },
            isRecoveryReady = { true },
            resumeAfterConfigChange = resume::resumeAfterAsrConfigChange,
        )

        val resumed = settingsAction.saveSiliconKey("fixed-key")

        assertTrue(resumed)
        assertEquals("fixed-key", persistedKey)
        assertTrue(
            workManager.getWorkInfosForUniqueWork(PendingTranscriptionWorker.UNIQUE_WORK_NAME)
                .get()
                .isNotEmpty(),
        )
    }
}
