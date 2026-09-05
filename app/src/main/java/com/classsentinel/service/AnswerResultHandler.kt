package com.classsentinel.service

import com.classsentinel.core.llm.AnswerRequest
import com.classsentinel.core.llm.AnswerResult
import kotlinx.coroutines.CancellationException

/** Applies terminal answer persistence before publishing each safe live result. */
internal class AnswerResultHandler(
    private val persistAnswer: suspend (eventId: Long, answer: String) -> Unit,
    private val publish: suspend (AnswerRequest, AnswerResult) -> Unit,
) {
    suspend fun handle(request: AnswerRequest, result: AnswerResult) {
        val effective = when (result) {
            is AnswerResult.Succeeded -> {
                val answer = result.answer.trim()
                if (answer.isBlank()) {
                    AnswerResult.Insufficient(request.question)
                } else {
                    try {
                        persistAnswer(request.eventId, answer)
                        AnswerResult.Succeeded(answer)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        AnswerResult.Failed("ANSWER_SAVE")
                    }
                }
            }

            else -> result
        }
        publish(request, effective)
    }
}
