package com.classsentinel.core.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Request identity and prompt data for a persisted or transient question answer. */
class AnswerRequest(
    val eventId: Long?,
    val question: String,
    val context: String,
    val style: AnswerStyle = AnswerStyle.TERSENESS,
    val llmConfig: LlmConfig? = null,
    val answerLength: String = "mid",
    val streamOutput: Boolean = true,
    val requestKey: String = eventId?.let { "event:$it" } ?: "",
) {
    init {
        require(requestKey.isNotBlank()) { "requestKey is required for a transient answer" }
    }
}

/**
 * Serializes answer generation per request key. It never inserts events; callers
 * update a persisted row only when receiving a terminal result for a persisted request.
 */
internal class AnswerGenerationCoordinator(
    private val scope: CoroutineScope,
    private val generate: (AnswerRequest) -> Flow<String>,
    private val onResult: suspend (AnswerRequest, AnswerResult) -> Unit,
    private val timeoutMs: Long = 5_000L,
) {
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()

    fun submit(request: AnswerRequest): Job? {
        val job = synchronized(lock) {
            jobs[request.requestKey]?.takeIf { it.isActive }?.let { return it }
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    answerResults(
                        question = request.question,
                        deltas = generate(request),
                        timeoutMs = timeoutMs,
                        streamOutput = request.streamOutput,
                    ).collect { result ->
                        onResult(request, result)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LlmException) {
                    onResult(request, AnswerResult.Failed(e.error.safeCode))
                } catch (_: Exception) {
                    onResult(request, AnswerResult.Failed("LLM_REQUEST"))
                } finally {
                    synchronized(lock) {
                        if (jobs[request.requestKey] === this@launch) jobs.remove(request.requestKey)
                    }
                }
            }.also { jobs[request.requestKey] = it }
        }
        job.start()
        return job
    }
}
