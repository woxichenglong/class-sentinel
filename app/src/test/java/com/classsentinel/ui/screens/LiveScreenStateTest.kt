package com.classsentinel.ui.screens

import com.classsentinel.core.llm.AnswerResult
import com.classsentinel.core.pipeline.PipelineState
import com.classsentinel.service.LiveAnswerState
import com.classsentinel.service.LiveTranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveScreenStateTest {

    @Test
    fun `live transcript displays final and current partial distinctly`() {
        val lines = listOf(
            LiveTranscriptLine.Final(1, "第一句", 0L, 1_000L),
            LiveTranscriptLine.Partial(2, "第二句预览", 1_100L),
        )

        assertEquals(
            listOf("第二句预览（正在识别）", "第一句"),
            liveTranscriptDisplay(lines),
        )
    }

    @Test
    fun `live answer label reflects observable generation result`() {
        val base = LiveAnswerState(7L, "问题", "依据", 1L, AnswerResult.Generating)
        assertEquals("正在生成答案…", liveAnswerLabel(base))
        assertEquals(
            "答案",
            liveAnswerLabel(base.copy(result = AnswerResult.Succeeded("答案"))),
        )
        assertEquals(
            "依据不足",
            liveAnswerLabel(base.copy(result = AnswerResult.Insufficient("问题"))),
        )
        assertEquals(
            "答案生成失败，请重试",
            liveAnswerLabel(base.copy(result = AnswerResult.Failed("LLM_TIMEOUT"))),
        )
    }

    @Test
    fun `live state text has no self test prompt`() {
        assertEquals("监听中 · 已转写 1 句", liveStateText(PipelineState.Listening(1)))
        assertEquals("未在监听", liveStateText(PipelineState.Idle))
    }
}
