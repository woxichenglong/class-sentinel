package com.classsentinel.service

import com.classsentinel.core.speech.ModelProfiles
import com.classsentinel.core.speech.SherpaOnnxStreamingEngine
import java.io.File
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
    fun `live speech factory always binds the production X ASR 480 profile`() {
        val engine = createLiveStreamingSpeechEngine(File("build/test-model"))

        assertEquals(ModelProfiles.PRODUCTION.id, engine.modelProfileId)
        assertEquals(ModelProfiles.PRODUCTION.recognizer.sampleRate, engine.sampleRate)
    }
}
