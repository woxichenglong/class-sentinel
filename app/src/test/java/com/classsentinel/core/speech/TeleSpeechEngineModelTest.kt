package com.classsentinel.core.speech

import com.classsentinel.core.audio.WavSegment
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TeleSpeechEngineModelTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `TeleSpeech engine sends XingChen Ultra model`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"测试转写"}"""))
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val rewritten = chain.request()
                    .newBuilder()
                    .url(server.url("/v1/audio/transcriptions"))
                    .build()
                chain.proceed(rewritten)
            }
            .build()
        val engine = TeleSpeechEngine(apiKey = "sk-test", client = client)
        val segment = WavSegment(
            id = "s1",
            startOffsetMs = 0,
            endOffsetMs = 1000,
            bytes = ByteArray(44),
        )

        val result = engine.transcribeSegment(segment)

        assertEquals("测试转写", result.getOrThrow())
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.contains("/v1/audio/transcriptions"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("XingChenAGI/XingChenASR-V3.2-Ultra"))
        assertFalse(body.contains("TeleAI/TeleSpeechASR"))
    }
}
