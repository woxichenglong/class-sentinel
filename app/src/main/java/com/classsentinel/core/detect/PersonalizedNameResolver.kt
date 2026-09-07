package com.classsentinel.core.detect

import kotlinx.coroutines.flow.StateFlow

/** Closed result set for personalized name targeting. */
enum class NameTargetConfidence {
    CONFIRMED,
    SUSPECT,
    NONE,
}

/** Explainable context evidence used by [PersonalizedNameResolver]. */
enum class NameTargetContext {
    STRONG,
    MEDIUM,
    NONE,
}

/** Internal first-version gates; tune later with benchmark evidence, not user settings. */
internal object PersonalizedNameThresholds {
    const val HIGH_THRESHOLD: Double = 0.85
    const val SUSPECT_THRESHOLD: Double = 0.65
}

data class PersonalizedNameResolution(
    val confidence: NameTargetConfidence,
    val targetName: String? = null,
    val matchedText: String? = null,
    val score: Double = 0.0,
    val phoneticSimilarity: Double = 0.0,
    val context: NameTargetContext = NameTargetContext.NONE,
    val isVocativePosition: Boolean = false,
)

/** A process-local UI event; [transcript] always preserves the original ASR final text. */
data class PersonalizedNameTargetEvent(
    val transcript: String,
    val targetName: String,
    val matchedText: String,
    val confidence: NameTargetConfidence,
    val score: Double,
    val timestampMs: Long,
)

/**
 * Resolves a configured canonical name against one authoritative ASR final.
 *
 * This is deliberately independent from [NameMatcher]: it only decides whether a final may be
 * treated as a personalized target. It does not mutate the transcript and does not emit alerts.
 */
class PersonalizedNameResolver private constructor(
    private val namesProvider: () -> List<NameEntry>,
) {
    constructor(names: List<NameEntry>) : this({ names })

    constructor(namesFlow: StateFlow<List<NameEntry>>) : this({ namesFlow.value })

    fun resolve(transcript: String): PersonalizedNameResolution {
        if (transcript.isBlank()) return PersonalizedNameResolution(NameTargetConfidence.NONE)

        var best: Candidate? = null
        for (entry in namesProvider()) {
            val candidates = (listOf(entry.display) + entry.aliases + entry.asrVariants)
                .map(String::trim)
                .filter { it.length >= 2 }
                .distinct()
            for (candidate in candidates) {
                evaluateExactOccurrences(transcript, entry.display, candidate) { result ->
                    if (result.isEligible && (best == null || result.score > best!!.score)) best = result
                }
                if (candidate.all(Char::isLetterOrDigit)) {
                    val windowLength = candidate.length
                    if (transcript.length >= windowLength) {
                        for (start in 0..transcript.length - windowLength) {
                            val matched = transcript.substring(start, start + windowLength)
                            if (!matched.all(Char::isLetterOrDigit)) continue
                            val result = evaluate(
                                transcript = transcript,
                                targetName = entry.display,
                                candidate = candidate,
                                matched = matched,
                                start = start,
                            )
                            if (result.isEligible && (best == null || result.score > best!!.score)) {
                                best = result
                            }
                        }
                    }
                }
            }
        }

        val hit = best ?: return PersonalizedNameResolution(NameTargetConfidence.NONE)
        val confidence = when {
            hit.score >= PersonalizedNameThresholds.HIGH_THRESHOLD -> NameTargetConfidence.CONFIRMED
            hit.score >= PersonalizedNameThresholds.SUSPECT_THRESHOLD -> NameTargetConfidence.SUSPECT
            else -> NameTargetConfidence.NONE
        }
        return PersonalizedNameResolution(
            confidence = confidence,
            targetName = hit.targetName.takeIf { confidence != NameTargetConfidence.NONE },
            matchedText = hit.matched.takeIf { confidence != NameTargetConfidence.NONE },
            score = hit.score,
            phoneticSimilarity = hit.phoneticSimilarity,
            context = hit.context,
            isVocativePosition = hit.isVocativePosition,
        )
    }

    private fun evaluateExactOccurrences(
        transcript: String,
        targetName: String,
        candidate: String,
        onResult: (Candidate) -> Unit,
    ) {
        var start = transcript.indexOf(candidate)
        while (start >= 0) {
            onResult(
                evaluate(
                    transcript = transcript,
                    targetName = targetName,
                    candidate = candidate,
                    matched = candidate,
                    start = start,
                ),
            )
            start = transcript.indexOf(candidate, start + 1)
        }
    }

    private fun evaluate(
        transcript: String,
        targetName: String,
        candidate: String,
        matched: String,
        start: Int,
    ): Candidate {
        val end = start + matched.length
        val vocativePosition = isVocativePosition(transcript, start)
        val context = contextAfter(transcript, end)
        val phoneticSimilarity = PinyinFuzzy.similarity(matched, candidate)
        val lexicalSimilarity = lexicalSimilarity(matched, candidate)
        val nameSimilarity = (phoneticSimilarity * NAME_PHONETIC_WEIGHT) +
            (lexicalSimilarity * NAME_LEXICAL_WEIGHT)
        val contextScore = when (context) {
            NameTargetContext.STRONG -> STRONG_CONTEXT_SCORE
            NameTargetContext.MEDIUM -> MEDIUM_CONTEXT_SCORE
            NameTargetContext.NONE -> 0.0
        }
        val positionScore = if (vocativePosition) POSITION_SCORE else 0.0
        val score = nameSimilarity * NAME_SCORE_WEIGHT +
            contextScore * CONTEXT_SCORE_WEIGHT +
            positionScore * POSITION_SCORE_WEIGHT

        return Candidate(
            targetName = targetName,
            matched = matched,
            score = score,
            phoneticSimilarity = phoneticSimilarity,
            context = context,
            isVocativePosition = vocativePosition,
            isEligible = vocativePosition && context != NameTargetContext.NONE,
        )
    }

    private fun contextAfter(transcript: String, end: Int): NameTargetContext {
        val suffix = transcript.substring(end)
            .trimStart { it.isWhitespace() || it in LEADING_PUNCTUATION }
            .let { if (it.startsWith("同学")) it.removePrefix("同学").trimStart() else it }
        val firstClause = suffix.takeWhile { it !in CLAUSE_BOUNDARIES }
        return when {
            STRONG_CONTEXT_PREFIXES.any { firstClause.startsWith(it) } -> NameTargetContext.STRONG
            MEDIUM_CONTEXT_PREFIXES.any { firstClause.startsWith(it) } -> NameTargetContext.MEDIUM
            else -> NameTargetContext.NONE
        }
    }

    private fun isVocativePosition(transcript: String, start: Int): Boolean {
        if (start == 0) return true
        val previous = transcript[start - 1]
        if (previous.isWhitespace() || previous in VOCATIVE_BOUNDARIES) return true
        val prefix = transcript.substring(0, start).trimEnd()
        return REQUEST_PREFIXES.any { prefix.endsWith(it) }
    }

    private fun lexicalSimilarity(a: String, b: String): Double {
        val maxLength = maxOf(a.length, b.length).coerceAtLeast(1)
        return 1.0 - PinyinFuzzy.levenshtein(a.lowercase(), b.lowercase()).toDouble() / maxLength
    }

    private data class Candidate(
        val targetName: String,
        val matched: String,
        val score: Double,
        val phoneticSimilarity: Double,
        val context: NameTargetContext,
        val isVocativePosition: Boolean,
        val isEligible: Boolean,
    )

    private companion object {
        const val NAME_PHONETIC_WEIGHT = 0.70
        const val NAME_LEXICAL_WEIGHT = 0.30
        const val NAME_SCORE_WEIGHT = 0.65
        const val CONTEXT_SCORE_WEIGHT = 0.25
        const val POSITION_SCORE_WEIGHT = 0.10
        const val STRONG_CONTEXT_SCORE = 1.0
        const val MEDIUM_CONTEXT_SCORE = 0.65
        const val POSITION_SCORE = 1.0

        val STRONG_CONTEXT_PREFIXES = listOf(
            "你来回答",
            "请你回答",
            "请你来",
            "你回答",
            "来回答",
            "请回答",
            "回答",
            "起立",
            "起来",
            "上来",
            "发言",
            "说说",
            "讲一下",
            "讲讲",
        )
        val MEDIUM_CONTEXT_PREFIXES = listOf(
            "你看",
            "看一下",
            "你听",
            "听一下",
            "你说",
        )
        const val LEADING_PUNCTUATION = "，,。！？!?；;：:"
        const val CLAUSE_BOUNDARIES = "，,。！？!?；;"
        const val VOCATIVE_BOUNDARIES = "，,。！？!?；;：:（(【["
        val REQUEST_PREFIXES = listOf("请", "让", "叫", "有请", "邀请")
    }
}
