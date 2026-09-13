package com.classsentinel.service

import com.classsentinel.core.speech.ModelProfiles
import com.classsentinel.core.speech.SherpaOnnxStreamingEngine
import com.classsentinel.core.speech.SherpaOnlineRecognizerPort
import com.classsentinel.core.speech.SherpaOnlineStreamPort
import com.classsentinel.core.speech.StreamingAsrEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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

    @Test
    fun `runtime startup initializes native once only inside collection and releases on failure`() = runBlocking {
        var prepares = 0
        var creates = 0
        var releases = 0
        val engine = createRuntimeAsrEngine(
            prepareDirectory = { prepares++; File("build/test-model") },
            recognizerFactory = {
                creates++
                object : SherpaOnlineRecognizerPort {
                    override fun createStream(): SherpaOnlineStreamPort = error("synthetic stream failure")
                    override fun release() { releases++ }
                }
            },
        )
        assertEquals(1, prepares)
        assertEquals(0, creates)
        val results = engine.transcribe(flowOf(shortArrayOf(0))).toList()
        assertEquals(1, creates)
        assertEquals(1, releases)
        assertTrue(results.single() is StreamingAsrEvent.Failed)
    }
}
