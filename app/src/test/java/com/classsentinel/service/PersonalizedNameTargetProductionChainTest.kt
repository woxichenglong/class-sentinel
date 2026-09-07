package com.classsentinel.service

import android.content.Context
import android.content.ContextWrapper
import com.classsentinel.core.alert.AlertChannel
import com.classsentinel.core.alert.AlertCoordinator
import com.classsentinel.core.detect.ClassEvent
import com.classsentinel.core.detect.EventEngine
import com.classsentinel.core.detect.NameEntry
import com.classsentinel.core.detect.NameMatcher
import com.classsentinel.core.detect.PersonalizedNameResolver
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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersonalizedNameTargetProductionChainTest {

    @Before
    fun resetLiveStateBeforeTest() {
        LiveStreamBus.clear()
    }

    @After
    fun clearLiveState() {
        LiveStreamBus.clear()
    }

    @Test
    fun `suspect final publishes live state without alert or answer dispatch`() = runTest {
        val alerts = RecordingChannel()
        val coordinator = AlertCoordinator(
            channels = listOf(alerts),
            enabledFlow = MutableStateFlow(setOf(alerts.key)),
            scope = this,
        )
        var answerDispatches = 0
        var eventWrites = 0
        var transcriptText: String? = null
        val adapter = SessionPipelineAdapter(
            context = ContextWrapper(null),
            scope = this,
            pipeline = emptyPipeline(),
            eventEngine = EventEngine(
                nameMatcher = NameMatcher(
                    listOf(NameEntry("梁津淦", emptyList())),
                ),
                sensitivityFlow = MutableStateFlow(Sensitivity.STANDARD),
            ),
            personalizedNameResolver = PersonalizedNameResolver(
                listOf(NameEntry("梁津淦", emptyList())),
            ),
            alert = coordinator,
            currentCourseId = { 1L },
            nextChunkSeq = { 0 },
            contextBuffer = com.classsentinel.core.context.TranscriptContextBuffer(60_000L, 2_000),
            onQuestion = { _, _ -> answerDispatches++ },
            insertTranscript = { chunk: TranscriptChunkEntity ->
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
            final = StreamingAsrEvent.Final(1, "良金干，你看一下", 0L, 1_000L),
            earlyAlerted = false,
        )

        assertEquals("良金干，你看一下", transcriptText)
        assertEquals(0, alerts.fired)
        assertEquals(0, eventWrites)
        assertEquals(0, answerDispatches)
        assertEquals(emptyList<ClassEvent>(), LiveStreamBus.events.value)
        val suspect = LiveStreamBus.suspectedNameTarget.value
        assertNotNull(suspect)
        assertEquals("梁津淦", suspect?.targetName)
        assertEquals("良金干", suspect?.matchedText)
        assertEquals("良金干，你看一下", suspect?.transcript)
        assertEquals(com.classsentinel.core.detect.NameTargetConfidence.SUSPECT, suspect?.confidence)
        coordinator.close()
    }

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
