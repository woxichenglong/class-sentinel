package com.classsentinel.core.detect

/** Shared locality rule for absence phrases attached to one name occurrence. */
internal object NameMatchRules {
    private val absencePrefixes = listOf(
        "同学",
        "今天",
        "昨天",
        "刚才",
        "刚刚",
        "最近",
        "一直",
        "已经",
        "之前",
        "还",
    )
    private val absenceWords = listOf("没来", "请假", "没到", "不在")

    /** True only when the text immediately following this candidate names an absence. */
    fun isExcludedOccurrence(segment: String, candidateEnd: Int): Boolean {
        var suffix = segment.substring(candidateEnd).trimStart()
        var removedPrefix: Boolean
        do {
            removedPrefix = false
            val prefix = absencePrefixes.firstOrNull { suffix.startsWith(it) }
            if (prefix != null) {
                suffix = suffix.removePrefix(prefix).trimStart()
                removedPrefix = true
            }
        } while (removedPrefix)
        return absenceWords.any { suffix.startsWith(it) }
    }
}
