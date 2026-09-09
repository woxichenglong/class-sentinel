package com.classsentinel.ui

import com.classsentinel.core.detect.NameEntry
import com.classsentinel.core.llm.NameVariantFailureCode
import com.classsentinel.core.llm.NameVariantGenerationResult
import com.classsentinel.core.llm.NameVariantGenerator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NameOnboardingPersistenceTest {

    @Test
    fun `name and AI success save separated NameEntry fields`() = runTest {
        val fake = FakeGenerator(
            NameVariantGenerationResult.Success(listOf("梁津干", "梁金干")),
        )
        var saved: List<NameEntry>? = null

        val result = saveOnboardingName(
            displayName = " 梁津淦 ",
            aliasesInput = "阿淦， 淦哥,,阿淦",
            existingNames = emptyList(),
            generator = fake,
            saveNames = { saved = it },
        )

        assertEquals("梁津淦", fake.requestedDisplay)
        assertEquals(
            NameEntry(
                display = "梁津淦",
                aliases = listOf("阿淦", "淦哥"),
                asrVariants = listOf("梁津干", "梁金干"),
            ),
            (result as NameOnboardingResult.Saved).entry,
        )
        assertEquals(result.entry, saved!!.single())
        assertTrue(result.aiGenerated)
        assertEquals(2, result.generatedVariantCount)
    }

    @Test
    fun `AI failure still saves display and user aliases with empty variants`() = runTest {
        val fake = FakeGenerator(
            NameVariantGenerationResult.Failure(NameVariantFailureCode.NETWORK),
        )
        var saved: List<NameEntry>? = null

        val result = saveOnboardingName(
            displayName = "梁津淦",
            aliasesInput = "阿淦",
            existingNames = emptyList(),
            generator = fake,
            saveNames = { saved = it },
        ) as NameOnboardingResult.Saved

        assertEquals(NameEntry("梁津淦", listOf("阿淦"), emptyList()), result.entry)
        assertEquals(result.entry, saved!!.single())
        assertFalse(result.aiGenerated)
        assertEquals(0, result.generatedVariantCount)
    }

    @Test
    fun `AI not ready saves display and aliases without invoking generator`() = runTest {
        val fake = FakeGenerator(
            NameVariantGenerationResult.Success(listOf("不应生成")),
        )
        var saved: List<NameEntry>? = null

        val result = saveOnboardingName(
            displayName = "梁津淦",
            aliasesInput = "阿淦",
            existingNames = emptyList(),
            generator = fake,
            aiReady = false,
            saveNames = { saved = it },
        ) as NameOnboardingResult.Saved

        assertEquals(NameEntry("梁津淦", listOf("阿淦"), emptyList()), result.entry)
        assertEquals(result.entry, saved!!.single())
        assertNull(fake.requestedDisplay)
        assertFalse(result.aiGenerated)
    }

    @Test
    fun `prepare name returns AI seed without persisting before calibration`() = runTest {
        val fake = FakeGenerator(
            NameVariantGenerationResult.Success(listOf("梁津干")),
        )

        val result = prepareOnboardingName(
            displayName = "梁津淦",
            aliasesInput = "阿淦",
            existingNames = emptyList(),
            generator = fake,
            aiReady = true,
        ) as NameOnboardingResult.Saved

        assertEquals(NameEntry("梁津淦", listOf("阿淦"), listOf("梁津干")), result.entry)
    }

    @Test
    fun `existing NameEntry prevents onboarding save and remains unchanged`() = runTest {
        val existing = NameEntry(
            display = "原姓名",
            aliases = listOf("原昵称"),
            asrVariants = listOf("原变体"),
        )
        val fake = FakeGenerator(
            NameVariantGenerationResult.Success(listOf("不应生成")),
        )
        var saveCalled = false

        val result = saveOnboardingName(
            displayName = "新姓名",
            aliasesInput = "新昵称",
            existingNames = listOf(existing),
            generator = fake,
            saveNames = { saveCalled = true },
        )

        assertEquals(NameOnboardingResult.ExistingConfiguration(existing), result)
        assertNull(fake.requestedDisplay)
        assertFalse(saveCalled)
        assertFalse(shouldShowOnboarding(completed = false, names = listOf(existing)))
        assertTrue(shouldShowOnboarding(completed = false, names = emptyList()))
    }
}

private class FakeGenerator(
    private val result: NameVariantGenerationResult,
) : NameVariantGenerator {
    var requestedDisplay: String? = null

    override suspend fun generate(displayName: String): NameVariantGenerationResult {
        requestedDisplay = displayName
        return result
    }
}
