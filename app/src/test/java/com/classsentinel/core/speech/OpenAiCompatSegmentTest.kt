package com.classsentinel.core.speech

import com.classsentinel.core.audio.VadSplitter
import com.classsentinel.core.audio.WavSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v0.2 Task 4（后半）：单段转写 + typed failure 契约。
 *
 * 新主路径 [SegmentSpeechEngine.transcribeSegment] 只面向一个 [WavSegment]：
 * - 一次 multipart 请求只含该 segment 的 WAV 字节与 model 字段；
 * - 失败必须返回可检查的 [AsrError] typed failure（不允许 println 后丢弃）；
 * - 成功段只返回一次（无重复文本）。
 */
class OpenAiCompatSegmentTest {

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

    private fun engine(
        apiKey: String = "sk-test",
        model: String = "TeleAI/TeleSpeechASR",
        client: okhttp3.OkHttpClient = okhttp3.OkHttpClient(),
    ): SegmentSpeechEngine {
        val compat = OpenAiCompatAsrEngine(
            name = "test",
            baseUrl = server.url("/v1").toString(),
            apiKey = apiKey,
            model = model,
            client = client,
        )
        // 返回类型本身就是契约：OpenAiCompatAsrEngine 必须可直接作为单段引擎使用。
        return compat
    }

    private suspend fun wavSegment(id: String = "s1", samples: Int = 8000): WavSegment =
        VadSplitter().segments(flowOf(ShortArray(samples) { 8000 })).toList().let {
            // 复用 M1a 分段器的稳定 String id；仅当传入 id 需要覆盖时才调整
            if (id != "s1") it[0].copy(id = id) else it[0]
        }

    // ---- 新主路径：单段转写 ---- //

    @Test
    fun `single segment posts its own wav and model once`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"今天讲傅里叶变换"}"""))
        val engine = engine()
        val seg = wavSegment()

        val result = engine.transcribeSegment(seg)

        assertEquals("今天讲傅里叶变换", result.getOrThrow())
        assertEquals(1, server.requestCount)
        val req = server.takeRequest()
        assertTrue(req.path!!.contains("/audio/transcriptions"))
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("TeleAI/TeleSpeechASR"))
        assertTrue(body.contains("audio.wav"))
        // 请求体必须是该段自己的 WAV（44 字节头 + PCM16），不能是整个 PCM 流或重放前一段。
        // multipart 二进制 part 原样写入（非 base64）；用 WAV 头 + 段字节数特征校验：
        // 该段 WAV 的 data 长度字段（偏移 40，小端）必须与请求体内的数据长度一致。
        fun wavDataLen(bytes: ByteArray): Int {
            var v = 0
            for (i in 40 until 44) v = v or ((bytes[i].toInt() and 0xFF) shl (8 * (i - 40)))
            return v
        }
        val dataLen = wavDataLen(seg.bytes)
        // 请求体包含 multipart 的 content-length 头（二进制 part 大小 = 44 + dataLen）
        assertTrue("request must carry segment wav payload", body.contains("audio/wav"))
        assertTrue("request must carry segment wav data length", body.contains("${44 + dataLen}"))
    }

    @Test
    fun `segment id flows from wav segment metadata`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"甲"}"""))
        server.enqueue(MockResponse().setBody("""{"text":"乙"}"""))
        val engine = engine()
        val segA = wavSegment()
        val segB = wavSegment(id = "s2", samples = 16000)

        val a = engine.transcribeSegment(segA)
        val b = engine.transcribeSegment(segB)

        assertEquals(listOf("甲", "乙"), listOf(a.getOrThrow(), b.getOrThrow()))
        assertEquals(2, server.requestCount)
        // 请求体对应各自的段，不是重放同一段（takeRequest 按入队顺序）。
        // 段 A(8000 样本, 44+16000=16044B) 与段 B(16000 样本, 44+32000=32044B) 长度不同，
        // 用各自 WAV data 长度字段校验 body 携带了正确大小的 payload。
        val bodyA = server.takeRequest().body.readUtf8()
        val bodyB = server.takeRequest().body.readUtf8()
        fun wavDataLen(bytes: ByteArray): Int {
            var v = 0
            for (i in 40 until 44) v = v or ((bytes[i].toInt() and 0xFF) shl (8 * (i - 40)))
            return v
        }
        val lenA = wavDataLen(segA.bytes)
        val lenB = wavDataLen(segB.bytes)
        assertTrue("lenA($lenA) and lenB($lenB) must differ", lenA != lenB)
        assertTrue("request A must carry segment A wav size", bodyA.contains("${44 + lenA}"))
        assertFalse("request A must not carry segment B wav size", bodyA.contains("${44 + lenB}"))
        assertTrue("request B must carry segment B wav size", bodyB.contains("${44 + lenB}"))
        assertFalse("request B must not carry segment A wav size", bodyB.contains("${44 + lenA}"))
    }

    // ---- typed failure 契约 ---- //

    @Test
    fun `500 retried once then SERVER retriable failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val engine = engine()

        val failure = engine.transcribeSegment(wavSegment()).exceptionOrNull()

        assertEquals(2, server.requestCount)
        assertTrue("must be typed AsrException carrying AsrError, was ${failure?.javaClass?.name}", failure is AsrException)
        failure as AsrException
        assertEquals(AsrError.Kind.SERVER, failure.error.kind)
        assertTrue(failure.error.retriable)
    }

    @Test
    fun `503 retried once then SERVER retriable failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("down"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("down"))
        val engine = engine()

        val failure = engine.transcribeSegment(wavSegment()).exceptionOrNull()

        assertEquals(2, server.requestCount)
        assertTrue(failure is AsrException)
        failure as AsrException
        assertEquals(AsrError.Kind.SERVER, failure.error.kind)
        assertTrue(failure.error.retriable)
    }

    @Test
    fun `500 then success recovers without duplicate emission`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.enqueue(MockResponse().setBody("""{"text":"终于成功"}"""))
        val engine = engine()

        val result = engine.transcribeSegment(wavSegment())

        assertEquals("终于成功", result.getOrThrow())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `401 not retried and AUTH non-retriable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))
        val engine = engine()

        val failure = engine.transcribeSegment(wavSegment()).exceptionOrNull()

        assertEquals(1, server.requestCount)
        assertTrue(failure is AsrException)
        failure as AsrException
        assertEquals(AsrError.Kind.AUTH, failure.error.kind)
        assertFalse(failure.error.retriable)
    }

    @Test
    fun `403 not retried and AUTH non-retriable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("nope"))
        val engine = engine()

        val failure = engine.transcribeSegment(wavSegment()).exceptionOrNull()

        assertEquals(1, server.requestCount)
        assertTrue(failure is AsrException)
        failure as AsrException
        assertEquals(AsrError.Kind.AUTH, failure.error.kind)
        assertFalse(failure.error.retriable)
    }

    @Test
    fun `429 retried once then RATE_LIMIT retriable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))
        val engine = engine()

        val failure = engine.transcribeSegment(wavSegment()).exceptionOrNull()

        assertEquals(2, server.requestCount)
        assertTrue(failure is AsrException)
        failure as AsrException
        assertEquals(AsrError.Kind.RATE_LIMIT, failure.error.kind)
        assertTrue(failure.error.retriable)
    }

    @Test
    fun `200 blank text is EMPTY non-retriable and no text emitted`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"   "}"""))
        val engine = engine()

        val failure = engine.transcribeSegment(wavSegment()).exceptionOrNull()

        assertEquals(1, server.requestCount)
        assertTrue(failure is AsrException)
        failure as AsrException
        assertEquals(AsrError.Kind.EMPTY, failure.error.kind)
        assertFalse(failure.error.retriable)
    }

    // ---- 取消语义回归（M1c 返工）---- //

    @Test
    fun `cancellation is rethrown not swallowed as failure result`() = runTest {
        // 裸 runCatching 会把 CancellationException 吞成 Result.failure，
        // 父协程无法及时结束。注入在 OkHttp execute() 前抛 CancellationException 的
        // 拦截器（模拟取消瞬间 OkHttp 抛出的真实取消异常），断言它原样上抛。
        val engine = engine(
            client = OkHttpClient.Builder()
                .addInterceptor { _: Interceptor.Chain ->
                    throw CancellationException("cancelled")
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
}
