package com.classsentinel.core.speech

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeAsrModelResolverTest {

    @Test
    fun `preference resolves bundled and ready remote profiles`() = runBlocking {
        val resolver = RuntimeAsrModelResolver(
            readiness = { profile -> profile.id == ModelProfiles.X_ASR_480.id },
        )

        assertSelection(resolver.resolve(ModelProfiles.ZIPFORMER_ZH_14M.id), ModelProfiles.ZIPFORMER_ZH_14M, null)
        assertSelection(resolver.resolve(ModelProfiles.SMALL_BILINGUAL_ZH_EN.id), ModelProfiles.SMALL_BILINGUAL_ZH_EN, null)
        assertSelection(resolver.resolve(ModelProfiles.X_ASR_480.id), ModelProfiles.X_ASR_480, null)
    }

    @Test
    fun `not ready remote falls back without changing requested preference`() = runBlocking {
        val preferred = ModelProfiles.X_ASR_480.id
        val resolver = RuntimeAsrModelResolver(readiness = { false })

        val selection = resolver.resolve(preferred)

        assertEquals(preferred, selection.requestedProfile?.id)
        assertEquals(ModelProfiles.ZIPFORMER_ZH_14M.id, selection.selectedProfile.id)
        assertEquals(RuntimeAsrFallbackReason.REMOTE_NOT_READY, selection.fallbackReason)
    }

    @Test
    fun `unknown and missing preferences fall back with explicit reasons`() = runBlocking {
        val resolver = RuntimeAsrModelResolver(readiness = { true })

        val unknown = resolver.resolve("stale-model-id")
        val missing = resolver.resolve(null)

        assertEquals(ModelProfiles.ZIPFORMER_ZH_14M.id, unknown.selectedProfile.id)
        assertEquals(RuntimeAsrFallbackReason.PREFERENCE_UNKNOWN, unknown.fallbackReason)
        assertEquals(ModelProfiles.ZIPFORMER_ZH_14M.id, missing.selectedProfile.id)
        assertEquals(RuntimeAsrFallbackReason.PREFERENCE_MISSING, missing.fallbackReason)
    }

    @Test
    fun `existing remote directory with failed readiness reports integrity fallback`() = runBlocking {
        val resolver = RuntimeAsrModelResolver(
            readiness = { false },
            remoteDirectoryExists = { true },
        )

        val selection = resolver.resolve(ModelProfiles.X_ASR_960.id)

        assertEquals(ModelProfiles.ZIPFORMER_ZH_14M.id, selection.selectedProfile.id)
        assertEquals(RuntimeAsrFallbackReason.REMOTE_INTEGRITY_INVALID, selection.fallbackReason)
    }

    @Test
    fun `engine starter uses requested profile without fallback when init succeeds`() = runBlocking {
        val calls = mutableListOf<String>()
        val selection = RuntimeAsrModelSelection(ModelProfiles.X_ASR_480, ModelProfiles.X_ASR_480, null)
        val starter = starter(calls) { profile ->
            calls += "init:${profile.id}"
        }

        val creation = starter.create(selection)

        assertEquals(ModelProfiles.X_ASR_480.id, creation.engine.modelProfileId)
        assertEquals(ModelProfiles.X_ASR_480.id, creation.selection.selectedProfile.id)
        assertEquals(listOf("init:${ModelProfiles.X_ASR_480.id}", "create:${ModelProfiles.X_ASR_480.id}"), calls)
    }

    @Test
    fun `engine init failure falls back once to 14m`() = runBlocking {
        val calls = mutableListOf<String>()
        val selection = RuntimeAsrModelSelection(ModelProfiles.X_ASR_480, ModelProfiles.X_ASR_480, null)
        val starter = starter(calls) { profile ->
            calls += "init:${profile.id}"
            if (profile.id == ModelProfiles.X_ASR_480.id) error("synthetic remote init failure")
        }

        val creation = starter.create(selection)

        assertEquals(ModelProfiles.ZIPFORMER_ZH_14M.id, creation.engine.modelProfileId)
        assertEquals(RuntimeAsrFallbackReason.MODEL_INIT_FAILED, creation.selection.fallbackReason)
        assertEquals(
            listOf(
                "init:${ModelProfiles.X_ASR_480.id}",
                "init:${ModelProfiles.ZIPFORMER_ZH_14M.id}",
                "create:${ModelProfiles.ZIPFORMER_ZH_14M.id}",
            ),
            calls,
        )
    }

    @Test
    fun `14m init failure propagates without recursive fallback`() {
        val calls = mutableListOf<String>()
        val selection = RuntimeAsrModelSelection(
            ModelProfiles.ZIPFORMER_ZH_14M,
            ModelProfiles.ZIPFORMER_ZH_14M,
            null,
        )
        val starter = starter(calls) { profile ->
            calls += "init:${profile.id}"
            error("synthetic baseline init failure")
        }

        assertThrows(RuntimeAsrModelInitializationException::class.java) {
            runBlocking { starter.create(selection) }
        }
        assertEquals(listOf("init:${ModelProfiles.ZIPFORMER_ZH_14M.id}"), calls)
    }

    @Test
    fun `cancellation propagates and never falls back`() {
        val cancellation = CancellationException("synthetic cancellation")
        val calls = mutableListOf<String>()
        val selection = RuntimeAsrModelSelection(ModelProfiles.X_ASR_480, ModelProfiles.X_ASR_480, null)
        val starter = starter(calls) { profile ->
            calls += "init:${profile.id}"
            throw cancellation
        }

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking { starter.create(selection) }
        }

        assertSame(cancellation, thrown)
        assertEquals(listOf("init:${ModelProfiles.X_ASR_480.id}"), calls)
    }

    private fun assertSelection(
        selection: RuntimeAsrModelSelection,
        expected: ModelProfile,
        reason: RuntimeAsrFallbackReason?,
    ) {
        assertEquals(expected.id, selection.selectedProfile.id)
        assertEquals(reason, selection.fallbackReason)
    }

    private fun starter(
        calls: MutableList<String>,
        initialize: suspend (ModelProfile) -> Unit,
    ): RuntimeAsrEngineStarter = RuntimeAsrEngineStarter(
        prepareDirectory = { profile -> File("build/runtime-asr-${profile.id}") },
        initializeModel = { _, profile -> initialize(profile) },
        createEngine = { _, profile ->
            calls += "create:${profile.id}"
            FakeEngine(profile)
        },
    )

    private class FakeEngine(
        profile: ModelProfile,
    ) : ProfileBoundStreamingSpeechEngine {
        override val name: String = "fake-${profile.id}"
        override val modelProfileId: String = profile.id
        override val sampleRate: Int = profile.recognizer.sampleRate
        override fun transcribe(pcm: Flow<ShortArray>): Flow<StreamingAsrEvent> = emptyFlow()
    }
}
