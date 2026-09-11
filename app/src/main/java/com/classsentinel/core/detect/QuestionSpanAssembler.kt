package com.classsentinel.core.detect

internal object QuestionSpanAssembler {
    private const val MAX_PREVIOUS_GAP_MS = 2_000L
    private const val MAX_QUESTION_CHARS = 300

    fun assemble(current: FinalTranscript, previous: FinalTranscript?): String {
        val previousFinal = previous ?: return current.text
        if (current.startOffsetMs < previousFinal.endOffsetMs ||
            current.startOffsetMs - previousFinal.endOffsetMs > MAX_PREVIOUS_GAP_MS
        ) {
            return current.text
        }

        val previousText = previousFinal.text.trimEnd()
        val currentText = current.text.trimStart()
        if (previousText.isEmpty() || currentText.isEmpty()) return current.text

        val separator = if (
            previousFinal.text.lastOrNull()?.isWhitespace() == true ||
            current.text.firstOrNull()?.isWhitespace() == true
        ) {
            " "
        } else {
            // Adjacent ASCII letters/digits are joined directly so a split word such as
            // "learn" + "ing" remains "learning". Other boundaries also remain raw.
            ""
        }
        val merged = previousText + separator + currentText
        return merged.takeIf { it.length <= MAX_QUESTION_CHARS } ?: current.text
    }
}
