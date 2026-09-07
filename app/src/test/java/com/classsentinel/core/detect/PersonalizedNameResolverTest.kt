package com.classsentinel.core.detect

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonalizedNameResolverTest {

    private val resolver = PersonalizedNameResolver(
        listOf(NameEntry(display = "梁津淦", aliases = emptyList(), asrVariants = emptyList())),
    )

    @Test
    fun `strong phonetic call is confirmed`() {
        val result = resolver.resolve("梁津干，你来回答")

        assertEquals(NameTargetConfidence.CONFIRMED, result.confidence)
        assertEquals("梁津淦", result.targetName)
    }

    @Test
    fun `medium phonetic call is suspect`() {
        val result = resolver.resolve("良金干，你看一下")

        assertEquals(NameTargetConfidence.SUSPECT, result.confidence)
        assertEquals("梁津淦", result.targetName)
    }

    @Test
    fun `high phonetic similarity in answer narrative is ignored`() {
        val result = resolver.resolve("梁金干刚才的答案不错")

        assertEquals(NameTargetConfidence.NONE, result.confidence)
    }

    @Test
    fun `similar person name in narrative is ignored`() {
        val result = resolver.resolve("梁金刚这个人物很好")

        assertEquals(NameTargetConfidence.NONE, result.confidence)
    }

    @Test
    fun `exact canonical name remains confirmed`() {
        val result = resolver.resolve("梁津淦，你来回答")

        assertEquals(NameTargetConfidence.CONFIRMED, result.confidence)
        assertEquals("梁津淦", result.targetName)
    }
}
