package com.classsentinel.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray

class NameMatcherTest {

    private val names = listOf(
        NameEntry(
            display = "张伟",
            aliases = listOf("小伟"),
            asrVariants = listOf("zhang wei", "张微", "张威"),
        ),
    )

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
    fun `absence of another student does not suppress a later rollcall`() {
        val matcher = NameMatcher(
            listOf(
                NameEntry("小李", emptyList()),
                NameEntry("张伟", emptyList()),
            ),
        )

        val hit = matcher.detect("小李今天没来，张伟你来回答", Sensitivity.STANDARD)

        assertEquals("张伟", hit?.name)
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
    fun `hit metadata distinguishes textual exact from homophone fuzzy`() {
        val exact = NameMatcher(names).detect("张伟，你来一下", Sensitivity.STANDARD)
        val fuzzy = NameMatcher(names).detect("章伟，你来一下", Sensitivity.STANDARD)

        assertTrue(exact?.isExact == true)
        assertNotNull(fuzzy)
        assertFalse(fuzzy?.isExact == true)
    }

    @Test
    fun `failed short variant gate does not hide a later full exact name`() {
        val matcher = NameMatcher(listOf(NameEntry("张晨龙", listOf("晨"))))

        val hit = matcher.detect("张晨龙来回答", Sensitivity.STANDARD)

        assertEquals("张晨龙", hit?.name)
        assertEquals("张晨龙", hit?.matched)
        assertTrue(hit?.isExact == true)
    }

    @Test
    fun `configured display name can be detected as a question target`() {
        val matcher = QuestionTargetMatcher(names)

        val hit = matcher.detect("张伟，为什么 CAPM 成立")

        assertEquals("张伟", hit?.name)
        assertEquals("张伟", hit?.matched)
    }

    @Test
    fun `explicit spoken alias can be detected as a question target`() {
        val hit = QuestionTargetMatcher(names).detect("小伟，为什么 CAPM 成立")

        assertEquals("张伟", hit?.name)
        assertEquals("小伟", hit?.matched)
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
