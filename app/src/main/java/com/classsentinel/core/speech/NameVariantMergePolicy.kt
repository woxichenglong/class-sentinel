package com.classsentinel.core.speech

import com.classsentinel.core.llm.NameVariantSanitizer

/** Structural-only safety gate for transcripts actually measured by X480. */
internal object MeasuredNameVariantSanitizer {
    private const val MAX_LENGTH_DELTA = 1
    private const val MAX_VARIANT_LENGTH = 12
    private val sentenceMarkers = listOf("我叫", "我是", "叫我", "你好", "请说", "谢谢")

    fun sanitize(displayName: String, candidates: List<String>): List<String> {
        val display = displayName.trim()
        if (display.isBlank()) return emptyList()

        val minLength = maxOf(1, display.length - MAX_LENGTH_DELTA)
        val maxLength = minOf(MAX_VARIANT_LENGTH, display.length + MAX_LENGTH_DELTA)
        if (maxLength < minLength) return emptyList()

        return candidates.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filter { it != display }
            .filter { it.length in minLength..maxLength }
            .filter { candidate -> candidate.all { it.isLetter() } }
            .filterNot { candidate -> isObviousSentence(candidate, display) }
            .distinct()
            .take(NameVariantSanitizer.MAX_VARIANTS)
            .toList()
    }

    private fun isObviousSentence(candidate: String, display: String): Boolean =
        sentenceMarkers.any(candidate::contains) ||
            candidate.contains(display) ||
            display.contains(candidate)
}

/** Combines real X480 finals before AI guesses while keeping one existing ASR variant budget. */
internal object NameVariantMergePolicy {
    fun merge(
        displayName: String,
        aiSeedVariants: List<String>,
        measuredTranscripts: List<String>,
    ): List<String> {
        val measured = MeasuredNameVariantSanitizer.sanitize(displayName, measuredTranscripts)
        val seed = NameVariantSanitizer.sanitize(displayName, aiSeedVariants)
        return (measured + seed)
            .distinct()
            .take(NameVariantSanitizer.MAX_VARIANTS)
    }

    /** Re-validates an already merged list without applying AI-only semantic rejection. */
    fun sanitizeMergedVariants(displayName: String, variants: List<String>): List<String> =
        MeasuredNameVariantSanitizer.sanitize(displayName, variants)
}
