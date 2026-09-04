package com.classsentinel.core.evaluation

import com.classsentinel.core.speech.PcmReplayResult
import com.classsentinel.core.speech.ReplayPhase
import com.classsentinel.core.speech.StreamingAsrEvent

/** Optional device-side measurements attached to one scored replay/E2E run. */
data class DevicePerformanceMetrics(
    val eventTriggerLatencyMs: Long? = null,
    val aiStartLatencyMs: Long? = null,
    val avgCpuPercent: Double? = null,
    val peakRamBytes: Long? = null,
    val durationMinutes: Double? = null,
    val batteryStartPercent: Int? = null,
    val batteryEndPercent: Int? = null,
    val batteryTempStartC: Double? = null,
    val batteryTempEndC: Double? = null,
    val thermalThrottleObserved: Boolean? = null,
)

/** Keyword recall/false-positive counts for one vocabulary (names or domain terms). */
data class KeywordMetrics(
    val referenceHits: Int,
    val matchedHits: Int,
    val hypothesisHits: Int,
    val falsePositiveHits: Int,
) {
    val recall: Double?
        get() = referenceHits.takeIf { it > 0 }?.let { matchedHits.toDouble() / it }

    val falsePositiveRate: Double
        get() = if (hypothesisHits == 0) 0.0 else falsePositiveHits.toDouble() / hypothesisHits
}

/** Unified quality/performance record; source and hypothesis text are intentionally not retained. */
data class UnifiedAsrScore(
    val modelProfileId: String,
    val gitCommitSha: String,
    val runId: String,
    val phase: ReplayPhase,
    val cer: Double,
    val wer: Double,
    val codeSwitchErrorRate: Double,
    val professionalTerms: KeywordMetrics,
    val names: KeywordMetrics,
    val firstPartialLatencyMs: Long?,
    val firstFinalLatencyMs: Long?,
    val rtf: Double,
    val inputDurationMs: Long,
    val elapsedMs: Long,
    val performance: DevicePerformanceMetrics,
)

/** Scores replay output without involving the live app pipeline or legacy importer. */
object UnifiedAsrScorer {
    fun score(
        result: PcmReplayResult,
        referenceText: String,
        professionalTerms: Set<String> = emptySet(),
        names: Set<String> = emptySet(),
        performance: DevicePerformanceMetrics = DevicePerformanceMetrics(),
    ): UnifiedAsrScore {
        val hypothesisText = result.observations
            .asSequence()
            .mapNotNull { (it.event as? StreamingAsrEvent.Final)?.text }
            .joinToString(" ")

        return UnifiedAsrScore(
            modelProfileId = result.modelProfileId,
            gitCommitSha = result.gitCommitSha,
            runId = result.runId,
            phase = result.phase,
            cer = errorRate(characterTokens(referenceText), characterTokens(hypothesisText)),
            wer = errorRate(wordTokens(referenceText), wordTokens(hypothesisText)),
            codeSwitchErrorRate = errorRate(scriptRuns(referenceText), scriptRuns(hypothesisText)),
            professionalTerms = keywordMetrics(referenceText, hypothesisText, professionalTerms),
            names = keywordMetrics(referenceText, hypothesisText, names),
            firstPartialLatencyMs = result.firstPartialLatencyMs,
            firstFinalLatencyMs = result.firstFinalLatencyMs,
            rtf = if (result.inputDurationMs > 0L) {
                result.elapsedMs.toDouble() / result.inputDurationMs
            } else {
                0.0
            },
            inputDurationMs = result.inputDurationMs,
            elapsedMs = result.elapsedMs,
            performance = performance,
        )
    }

    private fun keywordMetrics(
        reference: String,
        hypothesis: String,
        keywords: Set<String>,
    ): KeywordMetrics {
        val counts = keywords.map { keyword ->
            countOccurrences(reference, keyword) to countOccurrences(hypothesis, keyword)
        }
        val referenceHits = counts.sumOf { it.first }
        val hypothesisHits = counts.sumOf { it.second }
        val matchedHits = counts.sumOf { minOf(it.first, it.second) }
        return KeywordMetrics(
            referenceHits = referenceHits,
            matchedHits = matchedHits,
            hypothesisHits = hypothesisHits,
            falsePositiveHits = (hypothesisHits - matchedHits).coerceAtLeast(0),
        )
    }

    private fun countOccurrences(text: String, keyword: String): Int {
        val needle = keyword.trim().lowercase()
        if (needle.isEmpty()) return 0
        val haystack = text.lowercase()
        if (!needle.all { it.isCjk() }) {
            val boundaryPattern = Regex(
                "(?<![\\p{L}\\p{N}])${Regex.escape(needle)}(?![\\p{L}\\p{N}])",
            )
            return boundaryPattern.findAll(haystack).count()
        }
        var start = 0
        var count = 0
        while (true) {
            val found = haystack.indexOf(needle, startIndex = start)
            if (found < 0) return count
            count++
            start = found + needle.length
        }
    }

    private fun characterTokens(text: String): List<String> =
        text.lowercase().filter { it.isLetterOrDigit() }.map(Char::toString)

    /** Mixed-language WER tokens: CJK characters are tokens; adjacent non-CJK letters form words. */
    private fun wordTokens(text: String): List<String> {
        val tokens = mutableListOf<String>()
        val word = StringBuilder()

        fun flushWord() {
            if (word.isNotEmpty()) {
                tokens += word.toString()
                word.clear()
            }
        }

        for (char in text.lowercase()) {
            when {
                char.isCjk() -> {
                    flushWord()
                    tokens += char.toString()
                }
                char.isLetterOrDigit() -> word.append(char)
                else -> flushWord()
            }
        }
        flushWord()
        return tokens
    }

    /** A coarse script-run sequence used specifically for code-switch error reporting. */
    private fun scriptRuns(text: String): List<String> {
        val runs = mutableListOf<String>()
        var previous: String? = null
        for (char in text) {
            val current = when {
                char.isCjk() -> "ZH"
                char.isAsciiLetterOrDigit() -> "EN"
                else -> null
            } ?: continue
            if (current != previous) runs += current
            previous = current
        }
        return runs
    }

    private fun errorRate(reference: List<String>, hypothesis: List<String>): Double {
        if (reference.isEmpty()) return if (hypothesis.isEmpty()) 0.0 else 1.0
        return levenshtein(reference, hypothesis).toDouble() / reference.size
    }

    private fun levenshtein(reference: List<String>, hypothesis: List<String>): Int {
        var previous = IntArray(hypothesis.size + 1) { it }
        for (i in reference.indices) {
            val current = IntArray(hypothesis.size + 1)
            current[0] = i + 1
            for (j in hypothesis.indices) {
                val substitution = previous[j] + if (reference[i] == hypothesis[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    substitution,
                )
            }
            previous = current
        }
        return previous[hypothesis.size]
    }

    private fun Char.isCjk(): Boolean = this in '\u4E00'..'\u9FFF'

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}
