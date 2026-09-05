package com.classsentinel.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveStreamBusTranscriptTest {

    @After
    fun tearDown() {
        LiveStreamBus.clear()
    }

    @Test
    fun `partial with same utterance id replaces preview`() {
        LiveStreamBus.pushPartial(7, "张", 100L)
        LiveStreamBus.pushPartial(7, "张三", 200L)

        assertEquals(
            listOf(LiveTranscriptLine.Partial(7, "张三", 200L)),
            LiveStreamBus.transcript.value,
        )
    }

    @Test
    fun `final removes matching partial and duplicate final is idempotent`() {
        val final = LiveTranscriptLine.Final(7, "张三你来回答这个问题", 0L, 1_200L)
        LiveStreamBus.pushPartial(7, "张三你来回答", 500L)
        LiveStreamBus.pushFinal(7, final.text, final.startOffsetMs, final.endOffsetMs)
        LiveStreamBus.pushFinal(7, final.text, final.startOffsetMs, final.endOffsetMs)

        assertEquals(listOf(final), LiveStreamBus.transcript.value)
        assertEquals(listOf(final.text), LiveStreamBus.segmentList.value)
    }

    @Test
    fun `new utterance preview does not replace older final`() {
        val first = LiveTranscriptLine.Final(7, "第一句", 0L, 1_000L)
        LiveStreamBus.pushFinal(7, first.text, first.startOffsetMs, first.endOffsetMs)
        LiveStreamBus.pushPartial(8, "第二句", 1_100L)

        assertEquals(
            listOf(first, LiveTranscriptLine.Partial(8, "第二句", 1_100L)),
            LiveStreamBus.transcript.value,
        )
    }

    @Test
    fun `clear partial removes only the requested utterance`() {
        val first = LiveTranscriptLine.Partial(1, "第一句草稿", 100L)
        val second = LiveTranscriptLine.Partial(2, "第二句草稿", 200L)
        LiveStreamBus.pushPartial(first.utteranceId, first.text, first.audioOffsetMs)
        LiveStreamBus.pushPartial(second.utteranceId, second.text, second.audioOffsetMs)

        LiveStreamBus.clearPartial(1)

        assertEquals(listOf(second), LiveStreamBus.transcript.value)
    }

    @Test
    fun `clear removes current display but preserves pipeline state`() {
        val state = com.classsentinel.core.pipeline.PipelineState.Listening(2)
        LiveStreamBus.pushState(state)
        LiveStreamBus.pushPartial(1, "当前", 10L)
        LiveStreamBus.pushFinal(2, "已完成", 0L, 20L)

        LiveStreamBus.clear()

        assertEquals(emptyList<LiveTranscriptLine>(), LiveStreamBus.transcript.value)
        assertEquals(emptyList<String>(), LiveStreamBus.segmentList.value)
        assertEquals(state, LiveStreamBus.pipelineState.value)
    }
}
