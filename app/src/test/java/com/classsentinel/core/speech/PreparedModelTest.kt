package com.classsentinel.core.speech

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PreparedModelTest {

    @Test
    fun `prepared model binds profile identity and artifact set hash`() {
        val profile = ModelProfiles.ZIPFORMER_ZH_14M
        val engine = BoundFakeEngine(
            modelProfileId = profile.id,
            sampleRate = profile.recognizer.sampleRate,
        )

        val prepared = PreparedModel.from(profile, engine)

        assertEquals(profile, prepared.profile)
        assertEquals(engine, prepared.engine)
        assertFalse(prepared.artifactSetHash.isBlank())
    }

    @Test
    fun `prepared model rejects engine from another profile`() {
        val profile = ModelProfiles.ZIPFORMER_ZH_14M
        val engine = BoundFakeEngine(
            modelProfileId = "x-asr-960",
            sampleRate = profile.recognizer.sampleRate,
        )

        assertThrows(IllegalArgumentException::class.java) {
            PreparedModel.from(profile, engine)
        }
    }

    @Test
    fun `prepared model rejects engine with a different profile sample rate`() {
        val profile = ModelProfiles.ZIPFORMER_ZH_14M
        val engine = BoundFakeEngine(
            modelProfileId = profile.id,
            sampleRate = 8_000,
        )

        assertThrows(IllegalArgumentException::class.java) {
            PreparedModel.from(profile, engine)
        }
    }

    private class BoundFakeEngine(
        override val modelProfileId: String,
        override val sampleRate: Int,
    ) : ProfileBoundStreamingSpeechEngine {
        override val name: String = "prepared-fake"

        override fun transcribe(pcm: Flow<ShortArray>): Flow<StreamingAsrEvent> = emptyFlow()
    }
}
