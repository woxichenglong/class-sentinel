package com.classsentinel.service

import android.content.Context
import android.content.ContextWrapper
import com.classsentinel.core.alert.AlertChannel
import com.classsentinel.core.alert.AlertCoordinator
import com.classsentinel.core.context.TranscriptContextBuffer
import com.classsentinel.core.detect.ClassEvent
import com.classsentinel.core.detect.EventEngine
import com.classsentinel.core.detect.EventScope
import com.classsentinel.core.detect.EventType
import com.classsentinel.core.detect.FinalTranscript
import com.classsentinel.core.detect.NameEntry
import com.classsentinel.core.detect.NameMatcher
import com.classsentinel.core.detect.Sensitivity
import com.classsentinel.core.llm.AnswerGenerationCoordinator
import com.classsentinel.core.llm.AnswerRequest
import com.classsentinel.core.llm.AnswerResult
import com.classsentinel.core.llm.AnswerTriggerMode
import com.classsentinel.core.llm.AnswerTriggerDispatcher
import com.classsentinel.core.llm.AnswerTriggerPolicy
import com.classsentinel.core.pipeline.StreamingListenPipeline
import com.classsentinel.core.speech.StreamingAsrEvent
import com.classsentinel.core.speech.StreamingSpeechEngine
import com.classsentinel.data.entities.EventEntity
import com.classsentinel.data.entities.TranscriptChunkEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnswerTriggerProductionChainTest {

    @Test
    fun `Final to EventEngine to policy to coordinator preserves class-open history`() = runTest {
        val observedEvents = mutableListOf<ClassEvent>()
        val results = mutableListOf<AnswerResult>()
        var generationCalls = 0
        val alerts = RecordingChannel()
        val alertCoordinator = AlertCoordinator(
            channels = listOf(alerts),
            enabledFlow = kotlinx.coroutines.flow.MutableStateFlow(setOf(alerts.key)),
            scope = this,
        )
        val answerCoordinator = AnswerGenerationCoordinator(
            scope = this,
            generate = {
                generationCalls++
                kotlinx.coroutines.flow.flowOf("答案")
            },
            onResult = { _, result -> results += result },
        )
        val dispatcher = AnswerTriggerDispatcher(
            scope = this,
            policy = AnswerTriggerPolicy { AnswerTriggerMode.TARGETED_ONLY },
            onAllowed = { event, eventId ->
                answerCoordinator.submit(
                    AnswerRequest(
                        eventId = eventId,
                        question = event.triggerText,
                        context = event.context,
                        requestKey = "event:${eventId ?: 0L}",
                    ),
                )
            },
        )
        val adapter = adapter(
            scope = this,
            alert = alertCoordinator,
            onQuestion = { event, eventId ->
                observedEvents += event
                dispatcher.dispatch(event, eventId)
            },
            insertTranscript = { 1L },
            insertEvent = { 1L },
        )

        adapter.processSegment(
            courseId = 1L,
            final = StreamingAsrEvent.Final(1, "为什么 CAPM 成立", 0L, 1_000L),
            earlyAlerted = false,
        )
        adapter.processSegment(
            courseId = 1L,
            final = StreamingAsrEvent.Final(2, "张伟，你来回答为什么 CAPM 成立", 1_000L, 2_000L),
            earlyAlerted = false,
        )
        advanceUntilIdle()

        assertEquals(2, observedEvents.size)
        assertEquals(EventType.QUESTION, observedEvents[0].type)
        assertEquals(EventScope.CLASS_OPEN, observedEvents[0].scope)
        assertEquals(EventScope.DIRECT, observedEvents[1].scope)
        assertEquals(1, generationCalls)
        assertTrue(results.any { it is AnswerResult.Succeeded && it.answer == "答案" })
        assertEquals(2, alerts.fired)
        alertCoordinator.close()
    }

    private fun adapter(
        scope: CoroutineScope,
        alert: AlertCoordinator,
        onQuestion: (ClassEvent, Long?) -> Unit,
        insertTranscript: suspend (TranscriptChunkEntity) -> Long,
        insertEvent: suspend (EventEntity) -> Long,
    ): SessionPipelineAdapter = SessionPipelineAdapter(
        context = ContextWrapper(null),
        scope = scope,
        pipeline = emptyPipeline(),
        eventEngine = EventEngine(
            nameMatcher = NameMatcher(listOf(NameEntry("张伟", emptyList()))),
            sensitivityFlow = kotlinx.coroutines.flow.MutableStateFlow(Sensitivity.STANDARD),
        ),
        alert = alert,
        currentCourseId = { 1L },
        nextChunkSeq = { 0 },
        contextBuffer = TranscriptContextBuffer(windowMs = 60_000L, maxChars = 2_000),
        onQuestion = onQuestion,
        insertTranscript = insertTranscript,
        insertEvent = insertEvent,
    )

    private fun emptyPipeline(): StreamingListenPipeline = StreamingListenPipeline(
        streamer = com.classsentinel.core.audio.AudioStreamer(context = null),
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
