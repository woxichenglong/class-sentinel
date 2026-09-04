package com.classsentinel.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingAsrEventTest {

    @Test
    fun `partial carries utterance text and audio offset`() {
        val event = StreamingAsrEvent.Partial(
            utteranceId = 7,
            text = "张三",
            audioOffsetMs = 200L,
        )

        assertEquals(7, event.utteranceId)
        assertEquals("张三", event.text)
        assertEquals(200L, event.audioOffsetMs)
    }

    @Test
    fun `final carries same utterance and start end offsets`() {
        val event = StreamingAsrEvent.Final(
            utteranceId = 7,
            text = "张三你来回答这个问题",
            startOffsetMs = 0L,
            endOffsetMs = 1_200L,
        )

        assertEquals(7, event.utteranceId)
        assertEquals("张三你来回答这个问题", event.text)
        assertEquals(0L, event.startOffsetMs)
        assertEquals(1_200L, event.endOffsetMs)
    }

    @Test
    fun `failed contains only safe error category`() {
        val event: StreamingAsrEvent = StreamingAsrEvent.Failed("ASR_RUNTIME")

        assertTrue(event is StreamingAsrEvent.Failed)
        assertEquals("ASR_RUNTIME", (event as StreamingAsrEvent.Failed).errorKind)
        assertTrue(event.toString().contains("ASR_RUNTIME"))
        assertTrue(!event.toString().contains("课堂"))
        assertTrue(!event.toString().contains("api-key"))
    }

    @Test
    fun `engine lifecycle events carry safe labels`() {
        assertEquals("sherpa-onnx", (StreamingAsrEvent.EngineChanged("sherpa-onnx") as StreamingAsrEvent.EngineChanged).engine)
        assertEquals("restart", (StreamingAsrEvent.Recovering("restart") as StreamingAsrEvent.Recovering).reason)
    }
}
