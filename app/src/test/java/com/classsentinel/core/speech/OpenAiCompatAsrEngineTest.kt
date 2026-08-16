package com.classsentinel.core.speech

import com.classsentinel.core.audio.VadSplitter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiCompatAsrEngineTest {

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
    fun `posts wav multipart with model and parses text`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"今天讲傅里叶变换"}"""))
        val engine = OpenAiCompatAsrEngine(
            name = "test",
            baseUrl = server.url("/v1").toString(),
            apiKey = "sk-test",
            model = "TeleAI/TeleSpeechASR",
        )
        val loud = ShortArray(8000) { 8000 }
        val texts = engine.transcribe(flowOf(loud)).toList()

        assertEquals(listOf("今天讲傅里叶变换"), texts)

        val req = server.takeRequest()
        assertTrue(req.path!!.contains("/audio/transcriptions"))
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("TeleAI/TeleSpeechASR"))
        assertTrue(body.contains("audio.wav"))
    }

    @Test
    fun `two segments make two requests in order`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"第一段"}"""))
        server.enqueue(MockResponse().setBody("""{"text":"第二段"}"""))
        val engine = OpenAiCompatAsrEngine(
            name = "test",
            baseUrl = server.url("/v1").toString(),
            apiKey = "sk",
            model = "m",
        )
        val loud = ShortArray(16000) { 8000 }
        val quiet = ShortArray(16000) { 0 }
        val texts = engine.transcribe(flowOf(loud, quiet, quiet, loud, quiet, quiet)).toList()
        assertEquals(listOf("第一段", "第二段"), texts)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `failed segment retried once then skipped silently`() = runTest {
        // 两段音频：第一段请求两次都失败，第二段成功 → 只输出第二段
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.enqueue(MockResponse().setBody("""{"text":"第二段"}"""))
        val engine = OpenAiCompatAsrEngine(
            name = "test",
            baseUrl = server.url("/v1").toString(),
            apiKey = "sk",
            model = "m",
        )
        val loud = ShortArray(16000) { 8000 }
        val quiet = ShortArray(16000) { 0 }
        val texts = engine.transcribe(flowOf(loud, quiet, quiet, loud, quiet, quiet)).toList()
        assertEquals(listOf("第二段"), texts)
    }
}
