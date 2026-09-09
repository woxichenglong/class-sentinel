package com.classsentinel.core.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class NameVariantMergePolicyTest {

    @Test
    fun `measured variants take priority and display plus duplicates are removed`() {
        val merged = NameVariantMergePolicy.merge(
            displayName = "梁津淦",
            aiSeedVariants = listOf("梁津干", "良津干"),
            measuredTranscripts = listOf("梁津干", "梁金干", "梁津淦"),
        )

        assertEquals(listOf("梁津干", "梁金干", "良津干"), merged)
    }

    @Test
    fun `empty measured results preserve AI seeds`() {
        assertEquals(
            listOf("梁津干", "良津干"),
            NameVariantMergePolicy.merge(
                displayName = "梁津淦",
                aiSeedVariants = listOf("梁津干", "良津干"),
                measuredTranscripts = emptyList(),
            ),
        )
    }

    @Test
    fun `empty AI seeds still allow measured results`() {
        assertEquals(
            listOf("梁津干", "梁金干"),
            NameVariantMergePolicy.merge(
                displayName = "梁津淦",
                aiSeedVariants = emptyList(),
                measuredTranscripts = listOf("梁津干", "梁金干"),
            ),
        )
    }

    @Test
    fun `measured candidates fill the ten item budget before AI seeds`() {
        val measured = (0..11).map { index -> "甲乙${('甲'.code + index).toChar()}" }

        assertEquals(
            measured.take(10),
            NameVariantMergePolicy.merge(
                displayName = "梁津淦",
                aiSeedVariants = listOf("梁津干"),
                measuredTranscripts = measured,
            ),
        )
    }

    @Test
    fun `sentence-like measured finals are rejected without NLP extraction`() {
        assertEquals(
            listOf("梁津干"),
            NameVariantMergePolicy.merge(
                displayName = "梁津淦",
                aiSeedVariants = emptyList(),
                measuredTranscripts = listOf(
                    "我叫梁津淦",
                    "梁津淦你好",
                    "老师我叫梁津淦",
                    "梁津干",
                ),
            ),
        )
    }
}
