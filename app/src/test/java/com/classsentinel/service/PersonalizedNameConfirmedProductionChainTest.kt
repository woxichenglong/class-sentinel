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
import com.classsentinel.core.detect.NameEntry
import com.classsentinel.core.detect.NameMatcher
import com.classsentinel.core.detect.NameTargetConfidence
import com.classsentinel.core.detect.PersonalizedNameResolver
import com.classsentinel.core.detect.Sensitivity
import com.classsentinel.core.llm.AnswerGenerationCoordinator
import com.classsentinel.core.llm.AnswerRequest
import com.classsentinel.core.llm.AnswerResult
import com.classsentinel.core.llm.AnswerTriggerDispatcher
import com.classsentinel.core.llm.AnswerTriggerMode
import com.classsentinel.core.llm.AnswerTriggerPolicy
import com.classsentinel.core.pipeline.StreamingListenPipeline
import com.classsentinel.core.speech.StreamingAsrEvent
import com.classsentinel.core.speech.StreamingSpeechEngine
import com.classsentinel.data.entities.EventEntity
import com.classsentinel.data.entities.TranscriptChunkEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersonalizedNameConfirmedProductionChainTest {

    private val names = listOf(NameEntry("梁津淦", emptyList()))

    @Before
    fun resetLiveStateBeforeTest() {
        LiveStreamBus.clear()
    }

    @After
    fun clearLiveState() {
        LiveStreamBus.clear()
    }

    @Test
    fun `confirmed phonetic direct target keeps raw transcript alerts and enters targeted answer boundary`() = runTest {
        val text = "梁津干，你来回答为什么 CAPM 成立"
        val resolver = PersonalizedNameResolver(names)
        assertEquals(NameTargetConfidence.CONFIRMED, resolver.resolve(text).confidence)

        val alerts = RecordingChannel()
        val alertCoordinator = coordinator(this, alerts)
        var observedEvent: ClassEvent? = null
        var transcriptText: String? = null
        var eventWrites = 0
        var generationCalls = 0
        val results = mutableListOf<AnswerResult>()
        val answerCoordinator = AnswerGenerationCoordinator(
            scope = this,
            generate = {
                generationCalls++
                flowOf("答案")
            },
            onResult = { _, result -> results += result },
        )
        val answerDispatcher = AnswerTriggerDispatcher(
            scope = this,
            policy = AnswerTriggerPolicy { AnswerTriggerMode.TARGETED_ONLY },
            onAllowed = { event, eventId ->
                answerCoordinator.submit(
                    AnswerRequest(
                        eventId = eventId,
                        requestKey = "event:${eventId ?: 0L}",
                        question = event.triggerText,
                        context = event.context,
                    ),
                )
            },
        )
        val adapter = adapter(
            scope = this,
            alert = alertCoordinator,
            onQuestion = { event, eventId ->
                observedEvent = event
                answerDispatcher.dispatch(event, eventId)
            },
            insertTranscript = { chunk ->
                transcriptText = chunk.text
                1L
            },
            insertEvent = {
                eventWrites++
                1L
            },
        )

        adapter.processSegment(
            courseId = 1L,
            final = StreamingAsrEvent.Final(1, text, 0L, 1_000L),
            earlyAlerted = false,
        )
        advanceUntilIdle()

        assertEquals(EventType.QUESTION, observedEvent?.type)
        assertEquals(EventScope.DIRECT, observedEvent?.scope)
        assertEquals("梁津淦", observedEvent?.targetName)
        assertEquals(text, observedEvent?.triggerText)
        assertEquals(text, transcriptText)
        assertEquals(1, eventWrites)
        assertEquals(1, alerts.fired)
        assertEquals(1, generationCalls)
        assertTrue(results.any { it is AnswerResult.Succeeded && it.answer == "答案" })
        alertCoordinator.close()
    }

    @Test
    fun `confirmed phonetic rollcall keeps raw transcript and produces normal rollcall alert`() = runTest {
        val text = "梁津干，起立"
        val resolver = PersonalizedNameResolver(names)
        assertEquals(NameTargetConfidence.CONFIRMED, resolver.resolve(text).confidence)

        val alerts = RecordingChannel()
        val alertCoordinator = coordinator(this, alerts)
        var observedEvent: ClassEvent? = null
        var transcriptText: String? = null
        var eventWrites = 0
        var answerDispatches = 0
        val adapter = adapter(
            scope = this,
            alert = alertCoordinator,
            onQuestion = { event, _ ->
                observedEvent = event
                answerDispatches++
            },
            insertTranscript = { chunk ->
                transcriptText = chunk.text
                1L
            },
            insertEvent = {
                eventWrites++
                1L
            },
        )

        adapter.processSegment(
            courseId = 1L,
            final = StreamingAsrEvent.Final(2, text, 0L, 1_000L),
            earlyAlerted = false,
        )
        observedEvent = LiveStreamBus.events.value.lastOrNull()

        assertEquals(EventType.ROLLCALL, observedEvent?.type)
        assertEquals(EventScope.ROLLCALL, observedEvent?.scope)
        assertEquals("梁津淦", observedEvent?.targetName)
        assertEquals(text, observedEvent?.triggerText)
        assertEquals(text, transcriptText)
        assertEquals(1, eventWrites)
        assertEquals(1, alerts.fired)
        assertEquals(0, answerDispatches)
        alertCoordinator.close()
    }

    @Test
    fun `none narrative does not create a name event or side effects`() = runTest {
        val text = "梁金干刚才的答案不错"
        val resolver = PersonalizedNameResolver(names)
        assertEquals(NameTargetConfidence.NONE, resolver.resolve(text).confidence)

        val alerts = RecordingChannel()
        val alertCoordinator = coordinator(this, alerts)
        var observedEvent: ClassEvent? = null
        var transcriptText: String? = null
        var eventWrites = 0
        var answerDispatches = 0
        val adapter = adapter(
            scope = this,
            alert = alertCoordinator,
            onQuestion = { event, _ ->
                observedEvent = event
                answerDispatches++
            },
            insertTranscript = { chunk ->
                transcriptText = chunk.text
                1L
            },
            insertEvent = {
                eventWrites++
                1L
            },
        )

        adapter.processSegment(
            courseId = 1L,
            final = StreamingAsrEvent.Final(3, text, 0L, 1_000L),
            earlyAlerted = false,
        )

        assertEquals(null, observedEvent)
        assertEquals(text, transcriptText)
        assertEquals(0, eventWrites)
        assertEquals(0, alerts.fired)
        assertEquals(0, answerDispatches)
        alertCoordinator.close()
    }

    private fun coordinator(scope: CoroutineScope, channel: RecordingChannel): AlertCoordinator =
        AlertCoordinator(
            channels = listOf(channel),
            enabledFlow = MutableStateFlow(setOf(channel.key)),
            scope = scope,
        )

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
            nameMatcher = NameMatcher(names),
            sensitivityFlow = MutableStateFlow(Sensitivity.STANDARD),
        ),
        personalizedNameResolver = PersonalizedNameResolver(names),
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
