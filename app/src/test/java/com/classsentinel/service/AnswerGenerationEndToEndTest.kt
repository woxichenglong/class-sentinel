package com.classsentinel.service

import androidx.room.Room
import com.classsentinel.core.llm.AnswerGenerationCoordinator
import com.classsentinel.core.llm.AnswerRequest
import com.classsentinel.core.llm.AnswerResult
import com.classsentinel.core.llm.AnswerService
import com.classsentinel.core.llm.AnswerStyle
import com.classsentinel.core.llm.LlmClient
import com.classsentinel.core.llm.LlmConfig
import com.classsentinel.data.AppDatabase
import com.classsentinel.data.entities.EventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real answer path regression: provider deltas → result states → live bus and Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnswerGenerationEndToEndTest {
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
        LiveStreamBus.clear()
    }

    @Test
    fun `streaming setting reaches the real answer path and saves only the terminal answer`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(sse("A", "B")))
        val eventId = insertQuestion()
        val observed = mutableListOf<AnswerResult>()
        var answerWrites = 0
        val handler = AnswerResultHandler(
            persistAnswer = { id, answer ->
                answerWrites++
                db.eventDao().updateAnswer(id, answer)
            },
            publish = { request, result ->
                observed += result
                LiveStreamBus.pushAnswer(
                    eventId = request.eventId,
                    question = request.question,
                    context = request.context,
                    timestampMs = 1L,
                    result = result,
                )
            },
        )

        coordinator(this, handler).submit(request(eventId, streamOutput = true))?.join()

        assertEquals(
            listOf(
                AnswerResult.Generating,
                AnswerResult.Streaming("A"),
                AnswerResult.Streaming("AB"),
                AnswerResult.Succeeded("AB"),
            ),
            observed,
        )
        assertEquals(1, answerWrites)
        assertEquals("AB", db.eventDao().getQuestionById(eventId)?.answerText)
        assertEquals(AnswerResult.Succeeded("AB"), LiveStreamBus.latestAnswer.value?.result)
    }

    @Test
    fun `disabled streaming setting keeps the real path terminal-only`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(sse("A", "B")))
        val eventId = insertQuestion()
        val observed = mutableListOf<AnswerResult>()
        val handler = AnswerResultHandler(
            persistAnswer = { id, answer -> db.eventDao().updateAnswer(id, answer) },
            publish = { request, result ->
                observed += result
                LiveStreamBus.pushAnswer(
                    eventId = request.eventId,
                    question = request.question,
                    context = request.context,
                    timestampMs = 1L,
                    result = result,
                )
            },
        )

        coordinator(this, handler).submit(request(eventId, streamOutput = false))?.join()

        assertEquals(
            listOf(AnswerResult.Generating, AnswerResult.Succeeded("AB")),
            observed,
        )
        assertEquals("AB", db.eventDao().getQuestionById(eventId)?.answerText)
    }

    @Test
    fun `typed provider error reaches live result without a Room answer or leaked body`() = runBlocking {
        val body = "provider body https://provider.test classroom text"
        server.enqueue(MockResponse().setResponseCode(401).setBody(body))
        val eventId = insertQuestion()
        val observed = mutableListOf<AnswerResult>()
        var answerWrites = 0
        val handler = AnswerResultHandler(
            persistAnswer = { id, answer ->
                answerWrites++
                db.eventDao().updateAnswer(id, answer)
            },
            publish = { request, result ->
                observed += result
                LiveStreamBus.pushAnswer(
                    eventId = request.eventId,
                    question = request.question,
                    context = request.context,
                    timestampMs = 1L,
                    result = result,
                )
            },
        )

        coordinator(this, handler).submit(request(eventId, streamOutput = false))?.join()

        assertEquals(
            listOf(AnswerResult.Generating, AnswerResult.Failed("AUTH")),
            observed,
        )
        assertEquals(0, answerWrites)
        assertNull(db.eventDao().getQuestionById(eventId)?.answerText)
        assertTrue(observed.last().toString().contains("AUTH"))
        assertTrue(!observed.last().toString().contains("provider body"))
        assertTrue(!observed.last().toString().contains("provider.test"))
        assertTrue(!observed.last().toString().contains("classroom text"))
    }

    private fun coordinator(
        scope: CoroutineScope,
        handler: AnswerResultHandler,
    ): AnswerGenerationCoordinator =
        AnswerGenerationCoordinator(
            scope = scope,
            generate = { request ->
                val cfg = requireNotNull(request.llmConfig)
                AnswerService(LlmClient()).answer(
                    question = request.question,
                    context = request.context,
                    style = request.style,
                    cfg = cfg,
                    answerLength = request.answerLength,
                    streamOutput = request.streamOutput,
                )
            },
            onResult = { request, result -> handler.handle(request, result) },
        )

    private fun request(eventId: Long, streamOutput: Boolean): AnswerRequest = AnswerRequest(
        eventId = eventId,
        question = "问题",
        context = "上下文",
        style = AnswerStyle.TERSENESS,
        llmConfig = LlmConfig(server.url("/v1").toString(), "test-key", "test-model"),
        streamOutput = streamOutput,
    )

    private suspend fun insertQuestion(): Long = db.eventDao().insert(
        EventEntity(
            courseId = 1L,
            type = "QUESTION",
            triggerText = "问题",
            contextText = "上下文",
            notifiedAt = 1L,
            ts = 1L,
        ),
    )

    private fun sse(vararg pieces: String): String = buildString {
        pieces.forEach { piece ->
            append("data: {\"choices\":[{\"delta\":{\"content\":\"$piece\"}}]}\n\n")
        }
        append("data: [DONE]\n\n")
    }
}
