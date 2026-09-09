package com.classsentinel.core.speech

import com.classsentinel.core.audio.WavSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

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
        val text = engine.transcribeSegment(wavSegment()).getOrThrow()

        assertEquals("今天讲傅里叶变换", text)

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
        val first = engine.transcribeSegment(wavSegment(id = "s1", dataBytes = 16_000)).getOrThrow()
        val second = engine.transcribeSegment(wavSegment(id = "s2", dataBytes = 32_000)).getOrThrow()

        assertEquals(listOf("第一段", "第二段"), listOf(first, second))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `cancellation from okhttp is rethrown not swallowed as failure`() = runTest {
        // M1c 回归：裸 runCatching 会把 CancellationException 吞成 Result.failure，
        // 导致父协程无法及时结束。拦截器直接抛 CancellationException（模拟取消瞬间
        // OkHttp 抛出的真实取消异常），transcribeSegment 必须原样抛出，不能返回 failure Result。
        val engine = OpenAiCompatAsrEngine(
            name = "test",
            baseUrl = server.url("/v1").toString(),
            apiKey = "sk-test",
            model = "m",
            client = OkHttpClient.Builder()
                .addInterceptor { _: Interceptor.Chain ->
                    throw CancellationException("cancelled by test")
                }
                .build(),
        )
        val seg = wavSegment()
        val thrown = withContext(Dispatchers.IO) {
            try {
                engine.transcribeSegment(seg)
                null
            } catch (t: Throwable) {
                t
            }
        }

        assertTrue(
            "CancellationException must propagate, but was ${thrown?.javaClass?.name}",
            thrown is CancellationException,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `network failure exposes only a safe error description`() = runTest {
        val engine = OpenAiCompatAsrEngine(
            name = "test",
            baseUrl = server.url("/v1").toString(),
            apiKey = "sk-test",
            model = "m",
            client = OkHttpClient.Builder()
                .addInterceptor { _: Interceptor.Chain ->
                    throw IOException("provider body contains classroom answer")
                }
                .build(),
        )
        val segment = wavSegment()

        val failure = engine.transcribeSegment(segment).exceptionOrNull()

        assertTrue(failure is AsrException)
        val error = (failure as AsrException).error
        assertEquals(AsrError.Kind.NETWORK, error.kind)
        assertEquals("network error", error.message)
        assertTrue(!error.message.contains("classroom"))
    }

    private fun wavSegment(id: String = "s1", dataBytes: Int = 16_000): WavSegment =
        WavSegment(id, startOffsetMs = 0L, endOffsetMs = 1_000L, bytes = ByteArray(44 + dataBytes))
}
