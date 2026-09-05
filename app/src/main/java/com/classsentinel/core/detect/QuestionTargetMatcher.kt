package com.classsentinel.core.detect

/**
 * Detects whether a final question is vocatively directed at one configured student.
 * Only the display name and explicitly configured spoken aliases are eligible here;
 * ASR-only variants are deliberately excluded from this evidence layer.
 */
class QuestionTargetMatcher private constructor(
    private val namesProvider: () -> List<NameEntry>,
) {
    constructor(names: List<NameEntry>) : this({ names })

    constructor(nameMatcher: NameMatcher) : this({ nameMatcher.configuredNames() })

    data class Hit(
        val name: String,
        val matched: String,
    )

    fun detect(segment: String): Hit? {
        val names = namesProvider()
        if (segment.length < 2 || names.isEmpty()) return null

        for (entry in names) {
            val candidates = (listOf(entry.display) + entry.aliases)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
            for (candidate in candidates) {
                var start = 0
                while (start <= segment.length - candidate.length) {
                    if (
                        segment.regionMatches(
                            start,
                            candidate,
                            0,
                            candidate.length,
                            ignoreCase = true,
                        ) && !NameMatchRules.isExcludedOccurrence(segment, start + candidate.length) &&
                        isVocativeOccurrence(segment, start, start + candidate.length)
                    ) {
                        return Hit(entry.display, candidate)
                    }
                    start++
                }
            }
        }
        return null
    }

    private fun isVocativeOccurrence(segment: String, start: Int, end: Int): Boolean {
        if (!hasNameStartBoundary(segment, start)) return false

        // A name at the start followed by punctuation/space is a normal Chinese vocative.
        // Without punctuation, only an explicit addressing/question continuation is accepted;
        // this rejects "王明哲" and "张伟刚才的答案" while keeping "张伟你觉得…".
        val suffix = segment.substring(end).trimStart()
        if (suffix.isEmpty()) return false
        if (!suffix.first().isLetterOrDigit()) return true
        if (suffix.startsWith("同学")) {
            val afterHonorific = suffix.removePrefix("同学").trimStart()
            if (afterHonorific.isEmpty() || !afterHonorific.first().isLetterOrDigit()) return true
            return DIRECT_CONTINUATIONS.any { afterHonorific.startsWith(it, ignoreCase = true) }
        }
        return DIRECT_CONTINUATIONS.any { suffix.startsWith(it, ignoreCase = true) }
    }

    private fun hasNameStartBoundary(segment: String, start: Int): Boolean {
        if (start == 0) return true
        val previous = segment[start - 1]
        if (!previous.isLetterOrDigit()) return true

        // Chinese request prefixes may be attached to narrative words, e.g. 老师请张伟/那我们让张伟.
        val prefix = segment.substring(0, start).trimEnd()
        return TARGET_PREFIXES.any { prefix.endsWith(it) }
    }

    private companion object {
        val TARGET_PREFIXES = listOf("邀请", "有请", "请", "让", "叫")
        val DIRECT_CONTINUATIONS = listOf(
            "你",
            "您",
            "来",
            "回答",
            "解释",
            "说说",
            "讲讲",
            "谈谈",
            "为什么",
            "怎么",
            "如何",
            "什么",
            "能",
            "可以",
            "觉得",
            "认为",
        )
    }
}
