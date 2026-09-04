package com.classsentinel.service

import java.io.File
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
}
