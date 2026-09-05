package com.classsentinel.service

import android.content.Context
import android.content.ContextWrapper
import com.classsentinel.core.alert.AlertChannel
import com.classsentinel.core.alert.AlertCoordinator
import com.classsentinel.core.audio.AudioStreamer
import com.classsentinel.core.context.TranscriptContextBuffer
import com.classsentinel.core.detect.ClassEvent
import com.classsentinel.core.detect.EventEngine
import com.classsentinel.core.detect.NameEntry
import com.classsentinel.core.detect.NameMatcher
import com.classsentinel.core.detect.Sensitivity
import com.classsentinel.core.pipeline.StreamingListenPipeline
import com.classsentinel.core.speech.StreamingAsrEvent
import com.classsentinel.core.speech.StreamingSpeechEngine
import com.classsentinel.data.entities.EventEntity
import com.classsentinel.data.entities.TranscriptChunkEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SessionPipelineAdapterTest {

    @After
    fun clearGlobalLiveState() {
        LiveStreamBus.activeCourseId.value?.let { LiveStreamBus.finishCourse(it) }
        LiveStreamBus.clear()
    }

    @Test
    fun `transcript insert failure does not block rollcall detection or alert`() = runTest {
        val alerts = RecordingChannel()
        val coordinator = coordinator(this, alerts)
        val adapter = adapter(
            scope = this,
            alert = coordinator,
            insertTranscript = { throw IllegalStateException("transcript db failure") },
            insertEvent = { 9L },
        )

        adapter.processSegment(
            courseId = 1L,
            final = StreamingAsrEvent.Final(1, "张伟，你来回答", 0L, 1_000L),
            earlyAlerted = false,
        )

        assertEquals(1, alerts.fired)
        coordinator.close()
    }

    @Test
    fun `event insert failure still alerts question but does not invoke llm without event id`() = runTest {
        val alerts = RecordingChannel()
        val coordinator = coordinator(this, alerts)
        var questionCallbacks = 0
        val adapter = adapter(
            scope = this,
            alert = coordinator,
            onQuestion = { _, _ -> questionCallbacks++ },
            insertTranscript = { 1L },
            insertEvent = { throw IllegalStateException("event db failure") },
        )

        adapter.processSegment(
            courseId = 1L,
            final = StreamingAsrEvent.Final(1, "你能解释一下为什么 CAPM 成立吗", 0L, 1_000L),
            earlyAlerted = false,
        )

        assertEquals(1, alerts.fired)
        assertEquals(0, questionCallbacks)
        coordinator.close()
    }

    @Test
    fun `utterance ended clears blank endpoint partial without transcript event or alert side effects`() = runTest {
        val alerts = RecordingChannel()
        val coordinator = coordinator(this, alerts)
        var transcriptWrites = 0
        var eventWrites = 0
        val adapter = adapter(
            scope = this,
            alert = coordinator,
            pipeline = eventPipeline(
                listOf(
                    StreamingAsrEvent.Partial(1, "草稿", 100L),
                    StreamingAsrEvent.UtteranceEnded(1),
                ),
            ),
            insertTranscript = { transcriptWrites++; 1L },
            insertEvent = { eventWrites++; 1L },
        )

        adapter.start()
        advanceUntilIdle()

        assertEquals(emptyList<LiveTranscriptLine>(), LiveStreamBus.transcript.value)
        assertEquals(0, transcriptWrites)
        assertEquals(0, eventWrites)
        assertEquals(0, alerts.fired)
        adapter.stop()
        coordinator.close()
    }

    @Test
    fun `ended clears only one partial and does not affect another utterance`() = runTest {
        val alerts = RecordingChannel()
        val coordinator = coordinator(this, alerts)
        var transcriptWrites = 0
        var eventWrites = 0
        val adapter = adapter(
            scope = this,
            alert = coordinator,
            pipeline = eventPipeline(
                listOf(
                    StreamingAsrEvent.Partial(1, "第一句草稿", 100L),
                    StreamingAsrEvent.Partial(2, "第二句草稿", 200L),
                    StreamingAsrEvent.UtteranceEnded(1),
                ),
            ),
            insertTranscript = { transcriptWrites++; 1L },
            insertEvent = { eventWrites++; 1L },
        )

        adapter.start()
        advanceUntilIdle()

        assertEquals(
            listOf(LiveTranscriptLine.Partial(2, "第二句草稿", 200L)),
            LiveStreamBus.transcript.value,
        )
        assertEquals(0, transcriptWrites)
        assertEquals(0, eventWrites)
        assertEquals(0, alerts.fired)
        adapter.stop()
        coordinator.close()
    }

    @Test
    fun `partial followed by final reaches persistence and alert path`() = runTest {
        val alerts = RecordingChannel()
        val coordinator = coordinator(this, alerts)
        var transcriptWrites = 0
        var eventWrites = 0
        val adapter = adapter(
            scope = this,
            alert = coordinator,
            pipeline = eventPipeline(
                listOf(
                    StreamingAsrEvent.Partial(1, "草稿", 100L),
                    StreamingAsrEvent.Final(1, "张伟，你来回答", 0L, 1_000L),
                ),
            ),
            insertTranscript = { transcriptWrites++; 1L },
            insertEvent = { eventWrites++; 1L },
        )

        adapter.start()
        advanceUntilIdle()
        adapter.stop()

        assertEquals(
            listOf(LiveTranscriptLine.Final(1, "张伟，你来回答", 0L, 1_000L)),
            LiveStreamBus.transcript.value,
        )
        assertEquals(1, transcriptWrites)
        assertEquals(1, eventWrites)
        assertEquals(1, alerts.fired)
        coordinator.close()
    }

    private fun coordinator(scope: CoroutineScope, channel: RecordingChannel): AlertCoordinator = AlertCoordinator(
        channels = listOf(channel),
        enabledFlow = MutableStateFlow(setOf(channel.key)),
        scope = scope,
    )

    private fun adapter(
        scope: CoroutineScope,
        alert: AlertCoordinator,
        pipeline: StreamingListenPipeline = emptyPipeline(),
        onQuestion: (ClassEvent, Long?) -> Unit = { _, _ -> },
        insertTranscript: suspend (TranscriptChunkEntity) -> Long,
        insertEvent: suspend (EventEntity) -> Long,
    ): SessionPipelineAdapter = SessionPipelineAdapter(
        context = ContextWrapper(null),
        scope = scope,
        pipeline = pipeline,
        eventEngine = EventEngine(
            nameMatcher = NameMatcher(listOf(NameEntry("张伟", emptyList()))),
            sensitivityFlow = MutableStateFlow(Sensitivity.STANDARD),
        ),
        alert = alert,
        currentCourseId = { 1L },
        nextChunkSeq = { 0 },
        contextBuffer = TranscriptContextBuffer(windowMs = 60_000L, maxChars = 2_000),
        onQuestion = onQuestion,
        insertTranscript = insertTranscript,
        insertEvent = insertEvent,
    )

    private fun eventPipeline(events: List<StreamingAsrEvent>): StreamingListenPipeline =
        StreamingListenPipeline(
            streamer = AudioStreamer(context = null),
            speech = object : StreamingSpeechEngine {
                override val name: String = "test"
                override fun transcribe(pcm: Flow<ShortArray>): Flow<StreamingAsrEvent> =
                    kotlinx.coroutines.flow.flow {
                        events.forEach { emit(it) }
                    }
            },
        )

    private fun emptyPipeline(): StreamingListenPipeline = StreamingListenPipeline(
        streamer = AudioStreamer(context = null),
        speech = object : StreamingSpeechEngine {
            override val name: String = "test"
            override fun transcribe(pcm: Flow<ShortArray>): Flow<StreamingAsrEvent> = emptyFlow()
        },
    )

    private class RecordingChannel : AlertChannel {
        override val key: String = "record"
        var fired = 0

        override fun fire(event: ClassEvent, context: Context) {
            fired++
        }
    }
}
