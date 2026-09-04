package com.classsentinel.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2 Task 3：类型化 ASR 错误与语音事件契约测试。
 * 只验证契约行为，不触碰真实 ASR / 网络 / 凭证。
 */
class SpeechEventTest {

    // ---- AsrError.fromHttp ----

    @Test
    fun `fromHttp 401 is AUTH and not retriable`() {
        val e = AsrError.fromHttp(401)
        assertEquals(AsrError.Kind.AUTH, e.kind)
        assertFalse(e.retriable)
    }

    @Test
    fun `fromHttp 403 is AUTH and not retriable`() {
        val e = AsrError.fromHttp(403)
        assertEquals(AsrError.Kind.AUTH, e.kind)
        assertFalse(e.retriable)
    }

    @Test
    fun `fromHttp 429 is RATE_LIMIT and retriable`() {
        val e = AsrError.fromHttp(429)
        assertEquals(AsrError.Kind.RATE_LIMIT, e.kind)
        assertTrue(e.retriable)
    }

    @Test
    fun `fromHttp 5xx is SERVER and retriable`() {
        for (code in listOf(500, 503, 599)) {
            val e = AsrError.fromHttp(code)
            assertEquals("code=$code", AsrError.Kind.SERVER, e.kind)
            assertTrue("code=$code", e.retriable)
        }
    }

    @Test
    fun `fromHttp unknown status is UNKNOWN and not retriable`() {
        for (code in listOf(200, 404, 418)) {
            val e = AsrError.fromHttp(code)
            assertEquals("code=$code", AsrError.Kind.UNKNOWN, e.kind)
            assertFalse("code=$code", e.retriable)
        }
    }

    @Test
    fun `fromHttp non-positive code is UNKNOWN and not retriable`() {
        // 非法的 HTTP 状态码属于"未知状态"，契约要求明确不可重试
        val e = AsrError.fromHttp(-1)
        assertEquals(AsrError.Kind.UNKNOWN, e.kind)
        assertFalse(e.retriable)
    }

    // ---- 安全构造 ----

    @Test
    fun `emptyText is EMPTY and not retriable`() {
        val e = AsrError.emptyText()
        assertEquals(AsrError.Kind.EMPTY, e.kind)
        assertFalse(e.retriable)
    }

    @Test
    fun `network is NETWORK retriable and carries message`() {
        val e = AsrError.network("connection refused")
        assertEquals(AsrError.Kind.NETWORK, e.kind)
        assertTrue(e.retriable)
        assertTrue(e.message.contains("connection refused"))
    }

    // ---- SpeechEvent sealed contract ----

    @Test
    fun `text event carries segment id and text`() {
        val e = SpeechEvent.Text(segmentId = "s1", text = "张三")
        assertEquals("s1", e.segmentId)
        assertEquals("张三", e.text)
    }

    @Test
    fun `engine changed carries engine name`() {
        val e = SpeechEvent.EngineChanged(engine = "XunfeiRtasr")
        assertEquals("XunfeiRtasr", e.engine)
    }

    @Test
    fun `recovering carries segment id and message`() {
        val e = SpeechEvent.Recovering(segmentId = "s3", message = "reconnecting")
        assertEquals("s3", e.segmentId)
        assertEquals("reconnecting", e.message)
    }

    @Test
    fun `failed carries optional segment id and typed error`() {
        val noSegment = SpeechEvent.Failed(segmentId = null, error = AsrError.fromHttp(429))
        assertNull(noSegment.segmentId)
        assertEquals(AsrError.Kind.RATE_LIMIT, noSegment.error.kind)
        assertTrue(noSegment.error.retriable)

        val withSegment = SpeechEvent.Failed(segmentId = "s9", error = AsrError.emptyText())
        assertEquals("s9", withSegment.segmentId)
        assertEquals(AsrError.Kind.EMPTY, withSegment.error.kind)
    }
}
