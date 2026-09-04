package com.classsentinel.core.context

import com.classsentinel.core.detect.FinalTranscript

data class TimedTranscript(
    val text: String,
    val ts: Long,
)

class TranscriptContextBuffer(
    private val windowMs: Long = 60_000L,
    private val maxChars: Int = 2_000,
) {
    private val entries = mutableListOf<TimedTranscript>()

    fun add(item: TimedTranscript) {
        entries += item
    }

    /** Live path entry point: only authoritative final ASR text enters context. */
    fun addFinal(final: FinalTranscript, timestampMs: Long = final.endOffsetMs) {
        if (final.text.isNotBlank()) add(TimedTranscript(final.text, timestampMs))
    }

    fun contextAt(ts: Long): String {
        if (maxChars <= 0) return ""
        val lowerBound = ts - windowMs
        val recent = entries
            .asSequence()
            .filter { it.ts >= lowerBound && it.ts <= ts }
            .sortedBy { it.ts }
            .toList()
        val selected = mutableListOf<String>()
        var used = 0
        for (item in recent.asReversed()) {
            val separator = if (selected.isEmpty()) 0 else 1
            val available = maxChars - used - separator
            if (available <= 0) break
            val text = item.text.takeLast(available)
            selected.add(0, text)
            used += separator + text.length
        }
        return selected.joinToString("\n")
    }

    fun clear() {
        entries.clear()
    }
}
