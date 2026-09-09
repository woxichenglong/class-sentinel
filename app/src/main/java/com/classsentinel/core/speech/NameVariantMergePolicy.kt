package com.classsentinel.core.speech

import com.classsentinel.core.llm.NameVariantSanitizer

/** Combines real X480 finals before AI guesses while keeping one existing ASR variant budget. */
internal object NameVariantMergePolicy {
    fun merge(
        displayName: String,
        aiSeedVariants: List<String>,
        measuredTranscripts: List<String>,
    ): List<String> {
        val measured = NameVariantSanitizer.sanitize(displayName, measuredTranscripts)
        val seed = NameVariantSanitizer.sanitize(displayName, aiSeedVariants)
        return (measured + seed)
            .distinct()
            .take(NameVariantSanitizer.MAX_VARIANTS)
    }
}
