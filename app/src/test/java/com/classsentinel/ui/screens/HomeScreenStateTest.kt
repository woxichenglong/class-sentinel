package com.classsentinel.ui.screens

import com.classsentinel.core.pipeline.PipelineState
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeScreenStateTest {

    @Test
    fun `home state describes listening status without course statistics`() {
        assertEquals("未在监听", homeStateText(PipelineState.Idle))
        assertEquals("正在监听 · 已转写 3 句", homeStateText(PipelineState.Listening(3)))
        assertEquals("正在启动监听…", homeStateText(PipelineState.Starting))
        assertEquals("监听出错：转写中断", homeStateText(PipelineState.Error("转写中断")))
        assertEquals(
            "停止失败，请再次停止",
            homeStateText(PipelineState.Error("停止失败", retryableStop = true)),
        )
    }

    @Test
    fun `home start gate never allows service start before model readiness`() {
        assertEquals(LocalListeningStartGate.MODEL_NOT_READY, localListeningStartGate(null))
        assertEquals(LocalListeningStartGate.MODEL_NOT_READY, localListeningStartGate(false))
        assertEquals(LocalListeningStartGate.READY, localListeningStartGate(true))
    }
}
