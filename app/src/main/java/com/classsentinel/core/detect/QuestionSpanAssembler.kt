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

        val previousLast = previousText.lastOrNull()
        val currentFirst = currentText.firstOrNull()
        val hasExistingSpace = previousFinal.text.lastOrNull()?.isWhitespace() == true ||
            current.text.firstOrNull()?.isWhitespace() == true
        val hasAsciiBoundary = isAsciiWordChar(previousLast) && isAsciiWordChar(currentFirst)
        val separator = if (hasExistingSpace || hasAsciiBoundary) {
            " "
        } else {
            ""
        }
        val merged = previousText + separator + currentText
        return merged.takeIf { it.length <= MAX_QUESTION_CHARS } ?: current.text
    }

    private fun isAsciiWordChar(value: Char?): Boolean =
        value != null && (value in 'a'..'z' || value in 'A'..'Z' || value in '0'..'9')
}
