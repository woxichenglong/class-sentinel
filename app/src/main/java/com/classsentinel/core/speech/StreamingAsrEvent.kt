package com.classsentinel.core.speech

/**
 * Normalized events emitted by the continuous local ASR engine.
 * Partial text is replaceable display state; only Final is authoritative.
 */
sealed interface StreamingAsrEvent {
    data class Partial(
        val utteranceId: Int,
        val text: String,
        val audioOffsetMs: Long,
    ) : StreamingAsrEvent

    data class Final(
        val utteranceId: Int,
        val text: String,
        val startOffsetMs: Long,
        val endOffsetMs: Long,
    ) : StreamingAsrEvent

    /** Explicit lifecycle boundary for an utterance that ended without final text. */
    data class UtteranceEnded(val utteranceId: Int) : StreamingAsrEvent

    data class EngineChanged(val engine: String) : StreamingAsrEvent

    data class Recovering(val reason: String) : StreamingAsrEvent

    /** Safe category only; never store raw audio, transcript, provider body, or credentials here. */
    data class Failed(val errorKind: StreamingAsrErrorKind) : StreamingAsrEvent
}

/** Closed set of live-ASR failure categories safe to cross the pipeline boundary. */
enum class StreamingAsrErrorKind {
    ASR_RUNTIME,
}
