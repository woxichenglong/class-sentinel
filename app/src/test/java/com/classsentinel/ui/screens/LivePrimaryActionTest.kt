package com.classsentinel.ui.screens

import com.classsentinel.core.pipeline.PipelineState
import org.junit.Assert.assertEquals
import org.junit.Test

class LivePrimaryActionTest {

    @Test
    fun `idle maps to enabled start`() {
        assertEquals(
            LivePrimaryActionUi("开始", LivePrimaryAction.START, enabled = true),
            livePrimaryActionUi(PipelineState.Idle),
        )
    }

    @Test
    fun `error maps to enabled start without using the error message`() {
        assertEquals(
            livePrimaryActionUi(PipelineState.Error("错误 A")),
            livePrimaryActionUi(PipelineState.Error("错误 B")),
        )
        assertEquals(
            LivePrimaryActionUi("开始", LivePrimaryAction.START, enabled = true),
            livePrimaryActionUi(PipelineState.Error("模型未就绪")),
        )
    }

    @Test
    fun `stop recovery error maps to an enabled retry stop`() {
        assertEquals(
            LivePrimaryActionUi("再次停止", LivePrimaryAction.STOP, enabled = true),
            livePrimaryActionUi(PipelineState.Error("停止失败", retryableStop = true)),
        )
    }

    @Test
    fun `starting listening and recovering map to enabled stop`() {
        val expected = LivePrimaryActionUi("停止", LivePrimaryAction.STOP, enabled = true)

        assertEquals(expected, livePrimaryActionUi(PipelineState.Starting))
        assertEquals(expected, livePrimaryActionUi(PipelineState.Listening(sentences = 2)))
        assertEquals(expected, livePrimaryActionUi(PipelineState.Recovering("x480", "重试中")))
    }

    @Test
    fun `stopping maps to disabled stopping label`() {
        assertEquals(
            LivePrimaryActionUi("正在停止…", LivePrimaryAction.DISABLED, enabled = false),
            livePrimaryActionUi(PipelineState.Stopping),
        )
    }
}
