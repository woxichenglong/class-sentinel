package com.classsentinel.service

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import com.classsentinel.core.alert.AlertChannel
import com.classsentinel.core.alert.AlertCoordinator
import com.classsentinel.core.alert.QuestionAlertMode
import com.classsentinel.core.alert.QuestionAlertPolicy
import com.classsentinel.core.context.TranscriptContextBuffer
import com.classsentinel.core.detect.ClassEvent
import com.classsentinel.core.detect.EventEngine
import com.classsentinel.core.detect.EventScope
import com.classsentinel.core.detect.EventType
import com.classsentinel.core.detect.NameEntry
import com.classsentinel.core.detect.NameMatcher
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
import com.classsentinel.data.AppDatabase
import com.classsentinel.data.entities.CourseEntity
import com.classsentinel.data.entities.EventEntity
import com.classsentinel.data.entities.TranscriptChunkEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuestionAlertProductionChainTest {

    private lateinit var db: AppDatabase

    @After
    fun tearDown() {
        LiveStreamBus.activeCourseId.value?.let { LiveStreamBus.finishCourse(it) }
        LiveStreamBus.clear()
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `Final to EventEngine to Room to alert policy to answer policy keeps both policies independent`() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        val courseId = db.courseDao().insert(
            CourseEntity(title = "测试课程", startTs = 1L),
        )
        LiveStreamBus.startCourse(courseId)

        var questionAlertMode = QuestionAlertMode.TARGETED_ONLY
        var answerTriggerMode = AnswerTriggerMode.TARGETED_ONLY
        val alerts = RecordingChannel()
        val alertCoordinator = AlertCoordinator(
            channels = listOf(alerts),
            enabledFlow = MutableStateFlow(setOf(alerts.key)),
            scope = this,
        )
        var generationCalls = 0
        val answerResults = mutableListOf<AnswerResult>()
        val answerCoordinator = AnswerGenerationCoordinator(
            scope = this,
            generate = {
                generationCalls++
                kotlinx.coroutines.flow.flowOf("答案")
            },
            onResult = { _, result -> answerResults += result },
        )
        val answerDispatcher = AnswerTriggerDispatcher(
            scope = this,
            policy = AnswerTriggerPolicy { answerTriggerMode },
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
            questionAlertPolicy = QuestionAlertPolicy { questionAlertMode },
            onQuestion = { event, eventId -> answerDispatcher.dispatch(event, eventId) },
            insertTranscript = { chunk -> db.transcriptDao().insert(chunk) },
            insertEvent = { event -> db.eventDao().insert(event) },
        )

        // 默认低打扰：课堂开放题入历史但不提醒/不自动回答。
        adapter.processSegment(
            courseId = courseId,
            final = StreamingAsrEvent.Final(1, "为什么 CAPM 成立", 0L, 1_000L),
            earlyAlerted = false,
        )
        // 点到本人：同一套 TARGETED_ONLY 同时提醒并自动回答。
        adapter.processSegment(
            courseId = courseId,
            final = StreamingAsrEvent.Final(2, "张伟，你来回答为什么 CAPM 成立", 1_000L, 2_000L),
            earlyAlerted = false,
        )
        // ROLLCALL 不受 QuestionAlertMode 影响，也不进入回答链。
        adapter.processSegment(
            courseId = courseId,
            final = StreamingAsrEvent.Final(3, "张伟，起立", 2_000L, 3_000L),
            earlyAlerted = false,
        )
        advanceUntilIdle()

        val stored = db.eventDao().getForCourse(courseId)
        assertEquals(3, stored.size)
        assertEquals(2, stored.count { it.type == EventType.QUESTION.name })
        assertEquals(1, stored.count { it.type == EventType.ROLLCALL.name })
        assertEquals(3, LiveStreamBus.events.value.size)
        assertEquals(listOf(EventScope.DIRECT, EventScope.ROLLCALL), alerts.events.map { it.scope })
        assertEquals(1, generationCalls)
        assertTrue(answerResults.any { it is AnswerResult.Succeeded && it.answer == "答案" })

        // 反向组合：问题提醒关闭、回答模式全开；两套策略不能互相串线。
        questionAlertMode = QuestionAlertMode.OFF
        answerTriggerMode = AnswerTriggerMode.ALL_QUESTIONS
        adapter.processSegment(
            courseId = courseId,
            final = StreamingAsrEvent.Final(4, "怎么计算 WACC", 3_000L, 4_000L),
            earlyAlerted = false,
        )
        advanceUntilIdle()

        assertEquals(4, db.eventDao().getForCourse(courseId).size)
        assertEquals(2, alerts.events.size)
        assertEquals(2, generationCalls)
        alertCoordinator.close()
    }

    private fun adapter(
        scope: CoroutineScope,
        alert: AlertCoordinator,
        questionAlertPolicy: QuestionAlertPolicy,
        onQuestion: (ClassEvent, Long?) -> Unit,
        insertTranscript: suspend (TranscriptChunkEntity) -> Long,
        insertEvent: suspend (EventEntity) -> Long,
    ): SessionPipelineAdapter = SessionPipelineAdapter(
        context = ContextWrapper(null),
        scope = scope,
        pipeline = emptyPipeline(),
        eventEngine = EventEngine(
            nameMatcher = NameMatcher(listOf(NameEntry("张伟", emptyList()))),
            sensitivityFlow = MutableStateFlow(Sensitivity.STANDARD),
        ),
        alert = alert,
        questionAlertPolicy = questionAlertPolicy,
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
        val events = mutableListOf<ClassEvent>()

        override fun fire(event: ClassEvent, context: Context) {
            events += event
        }
    }
}
