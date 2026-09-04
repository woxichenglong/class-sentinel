package com.classsentinel.service

import java.io.File
import com.classsentinel.core.speech.ModelProfiles
import com.classsentinel.core.speech.SherpaOnnxStreamingEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenServiceLiveWiringTest {

    @Test
    fun `live speech factory returns local continuous sherpa engine`() {
        val engine = createLiveStreamingSpeechEngine(File("build/test-model"))

        assertTrue(engine is SherpaOnnxStreamingEngine)
        assertEquals("sherpa-onnx", engine.name)
    }

    @Test
    fun `live speech factory keeps the baseline profile by default`() {
        val engine = createLiveStreamingSpeechEngine(File("build/test-model"))

        assertEquals(ModelProfiles.ZIPFORMER_ZH_14M.id, engine.modelProfileId)
    }

    @Test
    fun `live speech factory binds an explicitly selected daily profile`() {
        listOf(ModelProfiles.X_ASR_480, ModelProfiles.X_ASR_960).forEach { profile ->
            val engine = createLiveStreamingSpeechEngine(File("build/${profile.id}"), profile)

            assertEquals(profile.id, engine.modelProfileId)
        }
    }
}
