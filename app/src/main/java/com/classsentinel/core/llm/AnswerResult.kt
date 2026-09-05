package com.classsentinel.core.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/** Observable, privacy-safe answer generation state. */
sealed interface AnswerResult {
    data object Generating : AnswerResult

    /** Replaceable live answer text; it is never persisted to the event history. */
    data class Streaming(val text: String) : AnswerResult

    data class Succeeded(val answer: String) : AnswerResult

    data class Insufficient(val question: String) : AnswerResult

    data class Failed(val safeCode: String) : AnswerResult {
        init {
            require(safeCode in SAFE_CODES) { "safeCode must be a known safe category" }
        }

        companion object {
            private val SAFE_CODES = buildSet {
                LlmError.Kind.values().forEach { add(it.name) }
                add("LLM_TIMEOUT")
                add("LLM_REQUEST")
                add("ANSWER_SAVE")
            }
        }
    }
}

/** Exact wire sentinel used when the model cannot answer from the supplied evidence. */
internal const val INSUFFICIENT_ANSWER_SENTINEL = "[[INSUFFICIENT]]"

/** Fixed user-facing copy for safe LLM categories; provider details never cross this boundary. */
internal fun answerFailureMessage(safeCode: String): String = when (safeCode) {
    "AUTH", "CONFIG" -> "检查 AI 配置"
    "NETWORK" -> "网络异常，请稍后重试"
    "RATE_LIMIT" -> "请求过于频繁，请稍后重试"
    "SERVER" -> "AI 服务暂时不可用"
    "LLM_TIMEOUT" -> "请求超时"
    "EMPTY", "UNKNOWN", "LLM_REQUEST" -> "生成失败"
    else -> "生成失败"
}

/** Collects one answer stream into a bounded, observable terminal result. */
fun answerResults(
    question: String,
    deltas: Flow<String>,
    timeoutMs: Long = 5_000L,
    streamOutput: Boolean = false,
): Flow<AnswerResult> = flow {
    require(timeoutMs > 0L) { "timeoutMs must be positive" }
    emit(AnswerResult.Generating)
    try {
        val answer = withTimeoutOrNull(timeoutMs) {
            buildString {
                deltas.collect {
                    append(it)
                    if (streamOutput) {
                        val textSoFar = toString()
                        val normalized = textSoFar.trim()
                        if (normalized.isNotBlank() &&
                            normalized != INSUFFICIENT_ANSWER_SENTINEL &&
                            !INSUFFICIENT_ANSWER_SENTINEL.startsWith(normalized)
                        ) {
                            emit(AnswerResult.Streaming(textSoFar))
                        }
                    }
                }
            }.trim()
        }
        if (answer == null) {
            emit(AnswerResult.Failed("LLM_TIMEOUT"))
        } else if (answer.isBlank() || answer == INSUFFICIENT_ANSWER_SENTINEL) {
            emit(AnswerResult.Insufficient(question))
        } else {
            emit(AnswerResult.Succeeded(answer))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: LlmException) {
        emit(AnswerResult.Failed(e.error.safeCode))
    } catch (_: Exception) {
        emit(AnswerResult.Failed("LLM_REQUEST"))
    }
}
