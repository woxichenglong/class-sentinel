package com.classsentinel.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTranscriptReducerTest {

    @Test
    fun `new partial replaces current partial for the same utterance`() {
        val reducer = StreamingTranscriptReducer()

        reducer.reduce(StreamingAsrEvent.Partial(7, "张", 100L))
        val snapshot = reducer.reduce(StreamingAsrEvent.Partial(7, "张三", 200L))

        assertEquals(StreamingAsrEvent.Partial(7, "张三", 200L), snapshot.currentPartial)
        assertEquals(emptyList<StreamingAsrEvent.Final>(), snapshot.finalizedLines)
    }

    @Test
    fun `final removes matching partial and appends one final`() {
        val reducer = StreamingTranscriptReducer()
        reducer.reduce(StreamingAsrEvent.Partial(7, "张三你来回答", 500L))

        val snapshot = reducer.reduce(
            StreamingAsrEvent.Final(7, "张三你来回答这个问题", 0L, 1_200L),
        )

        assertNull(snapshot.currentPartial)
        assertEquals(
            listOf(StreamingAsrEvent.Final(7, "张三你来回答这个问题", 0L, 1_200L)),
            snapshot.finalizedLines,
        )
    }

    @Test
    fun `utterance ended clears only the matching partial without creating a final`() {
        val reducer = StreamingTranscriptReducer()
        reducer.reduce(StreamingAsrEvent.Partial(7, "未完成", 500L))

        val snapshot = reducer.reduce(StreamingAsrEvent.UtteranceEnded(7))

        assertNull(snapshot.currentPartial)
        assertTrue(snapshot.finalizedLines.isEmpty())
    }

    @Test
    fun `duplicate final is idempotent`() {
        val reducer = StreamingTranscriptReducer()
        val event = StreamingAsrEvent.Final(7, "同一句", 0L, 1_000L)

        reducer.reduce(event)
        val snapshot = reducer.reduce(event)

        assertEquals(listOf(event), snapshot.finalizedLines)
    }

    @Test
    fun `partial from a new utterance does not overwrite an older final`() {
        val reducer = StreamingTranscriptReducer()
        val first = StreamingAsrEvent.Final(7, "第一句", 0L, 1_000L)
        reducer.reduce(first)

        val snapshot = reducer.reduce(StreamingAsrEvent.Partial(8, "第二句", 1_100L))

        assertEquals(listOf(first), snapshot.finalizedLines)
        assertEquals(8, snapshot.currentPartial?.utteranceId)
    }

    @Test
    fun `failed event leaves transcript unchanged`() {
        val reducer = StreamingTranscriptReducer()
        reducer.reduce(StreamingAsrEvent.Partial(7, "未完成", 200L))

        val snapshot = reducer.reduce(StreamingAsrEvent.Failed(StreamingAsrErrorKind.ASR_RUNTIME))

        assertEquals(StreamingAsrEvent.Partial(7, "未完成", 200L), snapshot.currentPartial)
        assertTrue(snapshot.finalizedLines.isEmpty())
    }
}
