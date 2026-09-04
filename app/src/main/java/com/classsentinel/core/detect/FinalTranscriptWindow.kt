package com.classsentinel.core.detect

/** One authoritative utterance accepted by the trigger layer. */
data class FinalTranscript(
    val utteranceId: Int,
    val text: String,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
)

data class FinalTranscriptWindowSnapshot(
    val entries: List<FinalTranscript>,
) {
    val combinedText: String
        get() = entries.joinToString("\n") { it.text }
}

/**
 * Bounded final-only context for cross-utterance trigger detection.
 * Partial hypotheses never enter this class because [add] accepts only finals.
 */
class FinalTranscriptWindow(
    private val windowMs: Long = 8_000L,
    private val maxEntries: Int = 32,
) {
    init {
        require(windowMs > 0L) { "windowMs must be positive" }
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val entries = mutableListOf<FinalTranscript>()

    @Synchronized
    fun add(final: FinalTranscript): FinalTranscriptWindowSnapshot {
        if (final.text.isBlank()) return snapshot()
        if (entries.any { it.utteranceId == final.utteranceId }) return snapshot()

        entries += final
        val cutoff = if (final.endOffsetMs < Long.MIN_VALUE + windowMs) {
            Long.MIN_VALUE
        } else {
            final.endOffsetMs - windowMs
        }
        entries.removeAll { it.endOffsetMs < cutoff }
        while (entries.size > maxEntries) entries.removeAt(0)
        return snapshot()
    }

    @Synchronized
    fun snapshot(): FinalTranscriptWindowSnapshot =
        FinalTranscriptWindowSnapshot(entries.toList())

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
