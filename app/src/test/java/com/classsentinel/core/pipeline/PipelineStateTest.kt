package com.classsentinel.core.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2a-1：PipelineState 数据契约测试。
 * 只验证状态可构造与字段取值，不触碰 ListenPipeline / 网络 / 凭证。
 */
class PipelineStateTest {

    private fun assertPipelineState(value: Any) {
        assertTrue(value is PipelineState)
    }

    // ---- 既有状态保留 ----

    @Test
    fun `Idle is constructible`() {
        assertPipelineState(PipelineState.Idle)
    }

    @Test
    fun `Error carries message`() {
        val e = PipelineState.Error("转写中断")
        assertEquals("转写中断", e.message)
    }

    // ---- 新增状态 ----

    @Test
    fun `Starting is constructible`() {
        assertPipelineState(PipelineState.Starting)
    }

    @Test
    fun `Stopping is constructible`() {
        assertPipelineState(PipelineState.Stopping)
    }

    @Test
    fun `Recovering carries engine and message`() {
        val r = PipelineState.Recovering(engine = "XunfeiRtasr", message = "重连中")
        assertEquals("XunfeiRtasr", r.engine)
        assertEquals("重连中", r.message)
    }

    // ---- Listening：兼容旧调用（仅传 sentences 时其余字段有安全默认值）----

    @Test
    fun `Listening single-arg defaults are safe`() {
        val l = PipelineState.Listening(2)
        assertEquals(2, l.sentences)
        assertEquals("", l.engine)
        assertEquals(0L, l.elapsedMs)
        assertEquals(0, l.pendingSegments)
    }

    @Test
    fun `Listening explicit fields are carried through`() {
        val l = PipelineState.Listening(
            sentences = 5,
            engine = "TeleSpeech",
            elapsedMs = 12_345L,
            pendingSegments = 2,
        )
        assertEquals(5, l.sentences)
        assertEquals("TeleSpeech", l.engine)
        assertEquals(12_345L, l.elapsedMs)
        assertEquals(2, l.pendingSegments)
    }

    @Test
    fun `Listening one-arg call keeps positional compatibility`() {
        val l = PipelineState.Listening(1)
        assertEquals(1, l.sentences)
    }
}
