package com.classsentinel.ui.screens

import com.classsentinel.core.llm.AnswerResult
import com.classsentinel.core.detect.NameTargetConfidence
import com.classsentinel.core.detect.PersonalizedNameTargetEvent
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
            "请求超时",
            liveAnswerLabel(base.copy(result = AnswerResult.Failed("LLM_TIMEOUT"))),
        )
        assertEquals(
            "答案进行中",
            liveAnswerLabel(base.copy(result = AnswerResult.Streaming("答案进行中"))),
        )
        assertEquals(
            "检查 AI 配置",
            liveAnswerLabel(base.copy(result = AnswerResult.Failed("AUTH"))),
        )
        assertEquals(
            "网络异常，请稍后重试",
            liveAnswerLabel(base.copy(result = AnswerResult.Failed("NETWORK"))),
        )
        assertEquals(
            "请求过于频繁，请稍后重试",
            liveAnswerLabel(base.copy(result = AnswerResult.Failed("RATE_LIMIT"))),
        )
        assertEquals(
            "AI 服务暂时不可用",
            liveAnswerLabel(base.copy(result = AnswerResult.Failed("SERVER"))),
        )
        assertEquals(
            "生成失败",
            liveAnswerLabel(base.copy(result = AnswerResult.Failed("UNKNOWN"))),
        )
    }

    @Test
    fun `live state text has no self test prompt`() {
        assertEquals("监听中 · 已转写 1 句", liveStateText(PipelineState.Listening(1)))
        assertEquals("未在监听", liveStateText(PipelineState.Idle))
        assertEquals(
            "停止失败，请再次停止",
            liveStateText(PipelineState.Error("停止失败", retryableStop = true)),
        )
    }

    @Test
    fun `history persistence warning is non blocking and session scoped`() {
        assertEquals(null, historyPersistenceWarning(false))
        assertEquals("本节部分历史保存失败", historyPersistenceWarning(true))
    }

    @Test
    fun `suspected target warning shows canonical name without classroom transcript`() {
        val event = PersonalizedNameTargetEvent(
            transcript = "梁金钢你看一下这道题",
            targetName = "梁金刚",
            matchedText = "梁金钢",
            confidence = NameTargetConfidence.SUSPECT,
            score = 0.78,
            timestampMs = 1L,
        )

        assertEquals("可能叫到「梁金刚」，请留意", suspectedNameTargetWarning(event))
    }

    @Test
    fun `transcript key remains stable when a partial is replaced`() {
        val first = LiveTranscriptLine.Partial(7, "第一版", 100L)
        val replacement = LiveTranscriptLine.Partial(7, "第二版", 200L)

        assertEquals(liveTranscriptKey(first), liveTranscriptKey(replacement))
        assertEquals(7, liveTranscriptKey(first))
    }
}
