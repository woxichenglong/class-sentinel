package com.classsentinel.core.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

/** Observable, privacy-safe answer generation state. */
sealed interface AnswerResult {
    data object Generating : AnswerResult

    data class Succeeded(val answer: String) : AnswerResult

    data class Insufficient(val question: String) : AnswerResult

    data class Failed(val safeCode: String) : AnswerResult {
        init {
            require(safeCode.matches(SAFE_CODE)) { "safeCode must be an uppercase category" }
        }

        companion object {
            private val SAFE_CODE = Regex("[A-Z0-9_]+")
        }
    }
}

/** Collects one answer stream into a bounded, observable terminal result. */
fun answerResults(
    question: String,
    deltas: Flow<String>,
    timeoutMs: Long = 5_000L,
): Flow<AnswerResult> = flow {
    require(timeoutMs > 0L) { "timeoutMs must be positive" }
    emit(AnswerResult.Generating)
    try {
        val answer = withTimeout(timeoutMs) {
            buildString { deltas.collect { append(it) } }.trim()
        }
        if (answer.isBlank()) {
            emit(AnswerResult.Insufficient(question))
        } else {
            emit(AnswerResult.Succeeded(answer))
        }
    } catch (_: TimeoutCancellationException) {
        emit(AnswerResult.Failed("LLM_TIMEOUT"))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emit(AnswerResult.Failed("LLM_REQUEST"))
    }
}
