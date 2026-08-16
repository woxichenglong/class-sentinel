package com.classsentinel.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinFuzzyTest {

    @Test
    fun `identical names score near 1`() {
        assertTrue(PinyinFuzzy.similarity("张伟", "张伟") > 0.99)
    }

    @Test
    fun `homophone close score`() {
        // 伟/薇 同音 zhang wei
        val s = PinyinFuzzy.similarity("张伟", "张薇")
        assertTrue("expected >=0.8, got $s", s >= 0.8)
    }

    @Test
    fun `unrelated names low score`() {
        assertTrue(PinyinFuzzy.similarity("张伟", "李明") < 0.5)
    }

    @Test
    fun `pinyin vs hanzi cross match`() {
        assertTrue(PinyinFuzzy.similarity("张伟", "zhang wei") > 0.8)
    }

    @Test
    fun `toPinyin strips tones`() {
        assertEquals("zhangwei", PinyinFuzzy.toPinyin("张伟"))
    }

    @Test
    fun `levenshtein basic cases`() {
        assertEquals(0, PinyinFuzzy.levenshtein("abc", "abc"))
        assertEquals(1, PinyinFuzzy.levenshtein("abc", "abd"))
        assertEquals(3, PinyinFuzzy.levenshtein("kitten", "sitting"))
    }
}
