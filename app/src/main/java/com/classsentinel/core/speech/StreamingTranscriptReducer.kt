package com.classsentinel.core.speech

/** Immutable in-process transcript view: final history plus at most one replaceable partial. */
data class StreamingTranscriptState(
    val finalizedLines: List<StreamingAsrEvent.Final> = emptyList(),
    val currentPartial: StreamingAsrEvent.Partial? = null,
)

/**
 * Applies continuous-ASR events without turning partial hypotheses into history.
 * The reducer is deliberately pure Kotlin so the same semantics can be used by
 * the service and Compose state adapters without Android or persistence coupling.
 */
class StreamingTranscriptReducer(
    private val maxFinalizedLines: Int = 100,
) {
    init {
        require(maxFinalizedLines > 0) { "maxFinalizedLines must be positive" }
    }

    var state: StreamingTranscriptState = StreamingTranscriptState()
        private set

    @Synchronized
    fun reduce(event: StreamingAsrEvent): StreamingTranscriptState {
        state = when (event) {
            is StreamingAsrEvent.Partial -> state.copy(currentPartial = event)
            is StreamingAsrEvent.Final -> {
                if (state.finalizedLines.any { it.utteranceId == event.utteranceId }) {
                    state
                } else {
                    state.copy(
                        finalizedLines = (state.finalizedLines + event).takeLast(maxFinalizedLines),
                        currentPartial = state.currentPartial
                            ?.takeUnless { it.utteranceId == event.utteranceId },
                    )
                }
            }
            is StreamingAsrEvent.UtteranceEnded -> state.copy(
                currentPartial = state.currentPartial
                    ?.takeUnless { it.utteranceId == event.utteranceId },
            )
            is StreamingAsrEvent.EngineChanged,
            is StreamingAsrEvent.Recovering,
            is StreamingAsrEvent.Failed,
            -> state
        }
        return state
    }

    @Synchronized
    fun clear() {
        state = StreamingTranscriptState()
    }
}
