package com.classsentinel.core.detect

import com.github.promeg.pinyinhelper.Pinyin

/**
 * 拼音模糊匹配：中文 ASR 同音字误识别兜底。
 * 汉字转无声调拼音（tinypinyin），编辑距离归一化为 0..1 相似度。
 */
object PinyinFuzzy {

    /** 转无声调拼音；非汉字原样保留（小写） */
    fun toPinyin(s: String): String =
        s.lowercase().map { ch -> Pinyin.toPinyin(ch).lowercase() }.joinToString("")

    /** Levenshtein 编辑距离 */
    fun levenshtein(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(
                d[i - 1][j] + 1,
                d[i][j - 1] + 1,
                d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
            )
        }
        return d[a.length][b.length]
    }

    /** 相似度 0..1：拼音（去空格）编辑距离归一化 */
    fun similarity(a: String, b: String): Double {
        val pa = toPinyin(a).filter { it != ' ' }
        val pb = toPinyin(b).filter { it != ' ' }
        val maxLen = maxOf(pa.length, pb.length).coerceAtLeast(1)
        return 1.0 - levenshtein(pa, pb).toDouble() / maxLen
    }
}
