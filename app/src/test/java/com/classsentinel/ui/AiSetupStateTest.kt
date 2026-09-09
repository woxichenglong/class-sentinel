package com.classsentinel.ui

import com.classsentinel.core.llm.AiConnectivityResult
import com.classsentinel.core.llm.AiConnectivityChecker
import com.classsentinel.core.llm.AiSetupFailure
import com.classsentinel.data.AiSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSetupStateTest {

    private val completeSettings = AiSettings(
        baseUrl = "https://example.test/v1",
        apiKey = "configured-test-key",
        model = "test-model",
    )

    @Test
    fun `missing AI configuration starts at AiConfig`() {
        assertEquals(
            OnboardingStep.AiConfig,
            initialOnboardingStep(aiConfigured = false, hasName = false, onboardingCompleted = false),
        )
        assertEquals(
            AiSetupState.Unconfigured,
            initialAiSetupState(AiSettings("https://example.test/v1", "", "test-model")),
        )
    }

    @Test
    fun `saved complete configuration and connectivity success becomes Ready`() = runTest {
        var saved: AiSettings? = null
        val state = saveAndCheckAi(
            draft = completeSettings,
            save = { saved = it },
            checker = FakeChecker(AiConnectivityResult.Success),
        )

        assertEquals(AiSetupState.Ready, state)
        assertEquals(completeSettings, saved)
        assertEquals(
            OnboardingStep.NameConfig,
            nextStepAfterAiSetup(state, skipped = false),
        )
    }

    @Test
    fun `connectivity failure becomes Failed and skip still opens NameConfig`() = runTest {
        val state = saveAndCheckAi(
            draft = completeSettings,
            save = {},
            checker = FakeChecker(AiConnectivityResult.Failure(AiSetupFailure.NETWORK)),
        )

        assertEquals(AiSetupState.Failed(AiSetupFailure.NETWORK), state)
        assertTrue(canSkipAi(state))
        assertEquals(OnboardingStep.NameConfig, nextStepAfterAiSetup(state, skipped = true))
    }

    @Test
    fun `complete existing AI config opens NameConfig without repeating setup`() {
        assertTrue(isAiSettingsComplete(completeSettings))
        assertEquals(
            OnboardingStep.NameConfig,
            initialOnboardingStep(aiConfigured = true, hasName = false, onboardingCompleted = false),
        )
    }

    @Test
    fun `restart facts restore permissions or home without relying on transient step`() {
        assertEquals(
            OnboardingStep.Permissions,
            initialOnboardingStep(aiConfigured = true, hasName = true, onboardingCompleted = false),
        )
        assertEquals(
            OnboardingStep.Permissions,
            initialOnboardingStep(aiConfigured = false, hasName = true, onboardingCompleted = false),
        )
        assertNull(initialOnboardingStep(aiConfigured = true, hasName = true, onboardingCompleted = true))
    }

    @Test
    fun `privacy notice explicitly says the name leaves the device`() {
        assertTrue(AI_NAME_PRIVACY_NOTICE.contains("填写的姓名会发送给当前配置的 AI 服务"))
    }
}

private class FakeChecker(
    private val result: AiConnectivityResult,
) : AiConnectivityChecker {
    override suspend fun check(settings: AiSettings): AiConnectivityResult = result
}
