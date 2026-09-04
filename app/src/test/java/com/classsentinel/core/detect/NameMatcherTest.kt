package com.classsentinel.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONArray

class NameMatcherTest {

    private val names = listOf(NameEntry("张伟", listOf("zhang wei", "张微", "张威")))

    @Test
    fun `exact name with context hits`() {
        val hit = NameMatcher(names).detect("张伟，你来回答一下", Sensitivity.STANDARD)
        assertEquals("张伟", hit?.name)
    }

    @Test
    fun `homophone variant hits`() {
        val hit = NameMatcher(names).detect("张微同学，起来回答", Sensitivity.STANDARD)
        assertEquals("张伟", hit?.name)
    }

    @Test
    fun `absence mention rejected`() {
        // 「没来」是缺席，不是点名
        assertNull(NameMatcher(names).detect("张伟今天没来", Sensitivity.STANDARD))
        assertNull(NameMatcher(names).detect("张伟今天没来", Sensitivity.LOOSE))
    }

    @Test
    fun `name without context rejected when required`() {
        assertNull(NameMatcher(names).detect("张伟同学上次作业不错", Sensitivity.STANDARD))
    }

    @Test
    fun `loose mode fires without context`() {
        assertNotNull(NameMatcher(names).detect("张伟同学上次作业不错", Sensitivity.LOOSE))
    }

    @Test
    fun `fuzzy match close pronunciation`() {
        // ASR 把「张伟」听成「章伟」：zhangwei vs zhangwei 相似度极高
        val hit = NameMatcher(names).detect("章伟，你来一下", Sensitivity.STANDARD)
        assertEquals("张伟", hit?.name)
    }

    @Test
    fun `unrelated name not hit`() {
        assertNull(NameMatcher(names).detect("李华，你来回答", Sensitivity.LOOSE))
    }

    @Test
    fun `empty names never hit`() {
        assertNull(NameMatcher(emptyList()).detect("张伟，你来回答", Sensitivity.LOOSE))
    }

    @Test
    fun `labeled corpus reports false positive and false negative counts`() {
        val raw = requireNotNull(javaClass.classLoader?.getResource("name_matcher_corpus.json"))
            .readText()
        val rows = JSONArray(raw)
        val matcher = NameMatcher(
            listOf(
                NameEntry("张伟", listOf("张微", "张威", "zhang wei")),
                NameEntry("王", emptyList()),
                NameEntry("明", listOf("小明")),
            ),
        )
        var falsePositives = 0
        var falseNegatives = 0
        repeat(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val text = row.getString("text")
            val expected = if (row.isNull("expected")) null else row.getString("expected")
            val actual = matcher.detect(text, Sensitivity.STANDARD)?.name
            if (expected == null && actual != null) falsePositives++
            if (expected != null && actual != expected) falseNegatives++
        }

        val counts = "false positives=$falsePositives false negatives=$falseNegatives"
        assertEquals(counts, 0, falsePositives)
        assertEquals(counts, 0, falseNegatives)
    }
}
