package com.classsentinel.core.summary

import com.classsentinel.core.llm.LlmClient
import com.classsentinel.core.llm.LlmConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SummaryGeneratorTest {

    private lateinit var server: MockWebServer
    private val cfg = LlmConfig("http://placeholder", "sk", "m")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sse(text: String): String {
        // 逐字流式模拟；content 用 JSONObject 构造保证转义（换行/引号安全）
        val parts = text.chunked(2).joinToString("") { ch ->
            val obj = org.json.JSONObject()
                .put("choices", org.json.JSONArray().put(
                    org.json.JSONObject().put("delta", org.json.JSONObject().put("content", ch)),
                ))
                .toString()
            "data: $obj\n\n"
        }
        return parts + "data: [DONE]\n\n"
    }

    private fun cfgWithServer() = cfg.copy(baseUrl = server.url("/v1").toString())

    @Test
    fun `short transcript single call with four section prompt`() = runTest {
        server.enqueue(MockResponse().setBody(sse("## 知识点\n傅里叶")))
        val gen = SummaryGenerator(LlmClient())
        val out = gen.generate("今天讲了傅里叶变换", cfgWithServer()).toList()
        // 流式分片按序拼接应还原原文
        assertEquals("## 知识点\n傅里叶", out.joinToString(""))

        val req = server.takeRequest()
        val body = req.body.readUtf8()
        assertTrue(body.contains("四段式总结"))
        assertTrue(body.contains("课堂转写"))
        assertTrue(body.contains("今天讲了傅里叶变换"))
    }

    @Test
    fun `long transcript two stage compression`() = runTest {
        // 「傅里叶变换」5 字 × 900 = 4500 字 > 4000 → 两级压缩(2 块 + 1 汇总 = 3 请求)
        val longer = "傅里叶变换".repeat(900)
        server.enqueue(MockResponse().setBody(sse("块一摘要")))
        server.enqueue(MockResponse().setBody(sse("块二摘要")))
        server.enqueue(MockResponse().setBody(sse("## 知识点\n汇总结果")))
        val gen = SummaryGenerator(LlmClient())
        val out = gen.generate(longer, cfgWithServer()).toList()
        assertTrue(out.isNotEmpty())
        assertEquals(3, server.requestCount) // 2 分块 + 1 汇总

        val first = server.takeRequest()
        val firstBody = first.body.readUtf8()
        assertTrue(firstBody.contains("压缩成要点摘要"))
    }

    @Test
    fun `blank transcript returns no content without calling llm`() = runTest {
        val result = SummaryGenerator(LlmClient()).generateResult("  \n\t", cfgWithServer())

        assertEquals(SummaryGenerationResult.NoContent, result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `failed partial chunk returns failed and never invents final summary`() = runTest {
        val longer = "傅里叶变换".repeat(900)
        server.enqueue(MockResponse().setResponseCode(500).setBody("provider failure"))

        val result = SummaryGenerator(LlmClient()).generateResult(longer, cfgWithServer())

        assertEquals(SummaryGenerationResult.Failed("GENERATION_FAILED"), result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `cancellation during a partial chunk prevents subsequent chunk calls`() = runTest {
        val longer = "傅里叶变换".repeat(900)
        val calls = mutableListOf<String>()
        val firstStarted = CompletableDeferred<Unit>()
        val generator = SummaryGenerator(
            streamChat = { messages, _ ->
                calls += messages.last()["content"].orEmpty()
                kotlinx.coroutines.flow.flow {
                    emit("块一")
                    firstStarted.complete(Unit)
                    awaitCancellation()
                }
            },
        )

        val task = async { generator.generateResult(longer, cfg) }
        firstStarted.await()
        task.cancel()
        try {
            task.await()
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
            // expected: cancellation must not be converted to a failed summary
        }

        assertEquals(1, calls.size)
    }
}
