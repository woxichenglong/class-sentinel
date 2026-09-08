package com.classsentinel.ui.screens

import com.classsentinel.core.speech.ModelDownloadFailureReason
import com.classsentinel.core.speech.ModelDownloadState
import com.classsentinel.core.speech.ModelDistribution
import com.classsentinel.core.speech.ModelProfiles
import com.classsentinel.worker.ModelDownloadActions
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAsrModelUiStateTest {

    @Test
    fun `bundled model is built in and selectable without download`() {
        val state = localAsrModelCardState(
            profile = ModelProfiles.ZIPFORMER_ZH_14M,
            downloadState = null,
            ready = false,
            preferredModelId = ModelProfiles.ZIPFORMER_ZH_14M.id,
            workStatus = LocalAsrModelWorkStatus.NONE,
            hasPartial = false,
        )

        assertEquals(LocalAsrModelUiStatus.BUILT_IN, state.status)
        assertTrue(state.canSelect)
        assertFalse(state.canDownload)
        assertTrue(state.selected)
    }

    @Test
    fun `remote state mapping uses verifier truth over stale ready state`() {
        val profile = ModelProfiles.X_ASR_480
        val stale = localAsrModelCardState(
            profile = profile,
            downloadState = ModelDownloadState.Ready,
            ready = false,
            preferredModelId = profile.id,
            workStatus = LocalAsrModelWorkStatus.NONE,
            hasPartial = false,
        )
        val ready = localAsrModelCardState(
            profile = profile,
            downloadState = ModelDownloadState.Ready,
            ready = true,
            preferredModelId = profile.id,
            workStatus = LocalAsrModelWorkStatus.SUCCEEDED,
            hasPartial = false,
        )

        assertEquals(LocalAsrModelUiStatus.NOT_INSTALLED, stale.status)
        assertFalse(stale.canSelect)
        assertEquals(LocalAsrModelUiStatus.READY, ready.status)
        assertTrue(ready.canSelect)
    }

    @Test
    fun `downloading verifying and failed states map to safe UI actions`() {
        val profile = ModelProfiles.X_ASR_480
        val downloading = localAsrModelCardState(
            profile,
            ModelDownloadState.Downloading(42, 100),
            ready = false,
            preferredModelId = null,
            workStatus = LocalAsrModelWorkStatus.RUNNING,
            hasPartial = true,
        )
        val verifying = localAsrModelCardState(
            profile,
            ModelDownloadState.Verifying,
            ready = false,
            preferredModelId = null,
            workStatus = LocalAsrModelWorkStatus.RUNNING,
            hasPartial = true,
        )
        val failed = localAsrModelCardState(
            profile,
            ModelDownloadState.Failed(ModelDownloadFailureReason.NETWORK),
            ready = false,
            preferredModelId = null,
            workStatus = LocalAsrModelWorkStatus.FAILED,
            hasPartial = true,
        )

        assertEquals(LocalAsrModelUiStatus.DOWNLOADING, downloading.status)
        assertEquals(42, downloading.percent)
        assertTrue(downloading.canCancel)
        assertEquals(LocalAsrModelUiStatus.VERIFYING, verifying.status)
        assertFalse(verifying.canSelect)
        assertEquals(LocalAsrModelUiStatus.FAILED, failed.status)
        assertTrue(failed.canContinue)
        assertEquals("网络失败", failed.failureMessage)
    }

    @Test
    fun `progress is clamped and unknown total has no fake percentage`() {
        assertEquals(0, modelDownloadPercent(0, 100))
        assertEquals(100, modelDownloadPercent(150, 100))
        assertEquals(null, modelDownloadPercent(42, 0))
        assertEquals(null, modelDownloadPercent(42, null))
    }

    @Test
    fun `remote work state without a registry snapshot still shows downloading`() {
        val state = localAsrModelCardState(
            profile = ModelProfiles.X_ASR_960,
            downloadState = null,
            ready = false,
            preferredModelId = null,
            workStatus = LocalAsrModelWorkStatus.ENQUEUED,
            hasPartial = true,
        )

        assertEquals(LocalAsrModelUiStatus.DOWNLOADING, state.status)
        assertTrue(state.canCancel)
        assertFalse(state.canSelect)
    }

    @Test
    fun `action handler enqueues remote, cancels without deleting and rejects insufficient space`() {
        val recorder = RecordingModelDownloadActions()
        val messages = mutableListOf<String>()
        val handler = LocalAsrModelActionHandler(
            actions = recorder,
            hasEnoughStorage = { it.id != ModelProfiles.X_ASR_960.id },
            onMessage = messages::add,
        )

        assertTrue(handler.download(ModelProfiles.X_ASR_480))
        assertTrue(handler.continueDownload(ModelProfiles.X_ASR_480))
        handler.cancel(ModelProfiles.X_ASR_480)
        assertFalse(handler.download(ModelProfiles.X_ASR_960))

        assertEquals(listOf("x-asr-480", "x-asr-480"), recorder.enqueued)
        assertEquals(listOf("x-asr-480"), recorder.cancelled)
        assertEquals(listOf("存储空间不足"), messages)
    }

    private class RecordingModelDownloadActions : ModelDownloadActions {
        val enqueued = mutableListOf<String>()
        val cancelled = mutableListOf<String>()

        override fun enqueue(profileId: String) {
            enqueued += profileId
        }

        override fun cancel(profileId: String) {
            cancelled += profileId
        }
    }
}
