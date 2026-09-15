package com.classsentinel.ui

import com.classsentinel.core.llm.AiConnectivityResult
import com.classsentinel.core.llm.AiConnectivityChecker
import com.classsentinel.core.llm.AiConnectionStatus
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
        var draftSaved: AiSettings? = null
        var verifiedSaved: AiSettings? = null
        var savedStatus: AiConnectionStatus? = null
        val state = saveAndCheckAi(
            draft = completeSettings,
            save = { draftSaved = it },
            checker = FakeChecker(AiConnectivityResult.Success),
            saveVerified = { verifiedSaved = it },
            saveStatus = { savedStatus = it },
        )

        assertEquals(AiSetupState.Ready, state)
        assertEquals(completeSettings, draftSaved)
        assertEquals(completeSettings, verifiedSaved)
        assertEquals(AiConnectionStatus.READY, savedStatus)
        assertEquals(
            OnboardingStep.NameConfig,
            nextStepAfterAiSetup(state, skipped = false),
        )
    }

    @Test
    fun `transient connectivity failure saves unverified draft and keeps verified config`() = runTest {
        var draftSaved: AiSettings? = null
        var verified = completeSettings
        var savedStatus: AiConnectionStatus? = null
        val state = saveAndCheckAi(
            draft = completeSettings,
            save = { draftSaved = it },
            checker = FakeChecker(AiConnectivityResult.Failure(AiSetupFailure.NETWORK)),
            saveVerified = { verified = it },
            saveStatus = { savedStatus = it },
        )

        assertEquals(AiSetupState.Unverified(AiSetupFailure.NETWORK), state)
        assertEquals(completeSettings, draftSaved)
        assertEquals(completeSettings, verified)
        assertEquals(AiConnectionStatus.UNVERIFIED, savedStatus)
        assertTrue(canSkipAi(state))
        assertEquals(OnboardingStep.NameConfig, nextStepAfterAiSetup(state, skipped = true))
    }

    @Test
    fun `rate limit and server failure both save draft without replacing verified config`() = runTest {
        listOf(AiSetupFailure.RATE_LIMIT, AiSetupFailure.SERVER).forEach { reason ->
            var draftSaved: AiSettings? = null
            var verified = completeSettings
            var savedStatus: AiConnectionStatus? = null
            val state = saveAndCheckAi(
                draft = completeSettings,
                save = { draftSaved = it },
                checker = FakeChecker(AiConnectivityResult.Failure(reason)),
                saveVerified = { verified = it },
                saveStatus = { savedStatus = it },
            )

            assertEquals(AiSetupState.Unverified(reason), state)
            assertEquals(completeSettings, draftSaved)
            assertEquals(completeSettings, verified)
            assertEquals(AiConnectionStatus.UNVERIFIED, savedStatus)
        }
    }

    @Test
    fun `permanent auth failure saves draft but preserves the previously verified configuration`() = runTest {
        var persisted = completeSettings
        var draftSaved: AiSettings? = null
        var savedStatus: AiConnectionStatus? = null
        val replacement = AiSettings(
            baseUrl = "https://replacement.example.test/v1",
            apiKey = "replacement-test-key",
            model = "replacement-model",
        )

        val state = saveAndCheckAi(
            draft = replacement,
            save = { draftSaved = it },
            checker = FakeChecker(AiConnectivityResult.Failure(AiSetupFailure.AUTH)),
            saveVerified = { persisted = it },
            saveStatus = { savedStatus = it },
        )

        assertEquals(AiSetupState.Failed(AiSetupFailure.AUTH), state)
        assertEquals(replacement, draftSaved)
        assertEquals(completeSettings, persisted)
        assertEquals(AiConnectionStatus.FAILED, savedStatus)
    }

    @Test
    fun `capability failure becomes incompatible without discarding the draft`() = runTest {
        var draftSaved: AiSettings? = null
        var verified = completeSettings
        var savedStatus: AiConnectionStatus? = null
        val state = saveAndCheckAi(
            draft = completeSettings,
            save = { draftSaved = it },
            checker = FakeChecker(
                AiConnectivityResult.Failure(AiSetupFailure.CAPABILITY_UNSUPPORTED),
            ),
            saveVerified = { verified = it },
            saveStatus = { savedStatus = it },
        )

        assertEquals(AiSetupState.Incompatible(AiSetupFailure.CAPABILITY_UNSUPPORTED), state)
        assertEquals(completeSettings, draftSaved)
        assertEquals(completeSettings, verified)
        assertEquals(AiConnectionStatus.INCOMPATIBLE, savedStatus)
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

    @Test
    fun `retrying is an active state and failed setup cannot advance unless skipped`() {
        val retrying = AiSetupState.Retrying(
            attempt = 2,
            maxAttempts = 3,
            reason = AiSetupFailure.NETWORK,
        )

        assertTrue(!canSkipAi(retrying))
        assertTrue(!canSkipAi(AiSetupState.Connected))
        assertTrue(!canSkipAi(AiSetupState.CapabilityChecking))
        assertEquals(
            null,
            nextStepAfterAiSetup(AiSetupState.Failed(AiSetupFailure.AUTH), skipped = false),
        )
    }

    @Test
    fun `user-facing setup messages distinguish every transport and provider category`() {
        assertTrue(aiSetupFailureMessage(AiSetupFailure.AUTH).contains("401"))
        assertTrue(aiSetupFailureMessage(AiSetupFailure.FORBIDDEN).contains("403"))
        assertTrue(aiSetupFailureMessage(AiSetupFailure.NOT_FOUND).contains("404"))
        assertTrue(aiSetupFailureMessage(AiSetupFailure.RATE_LIMIT).contains("限流"))
        assertTrue(aiSetupFailureMessage(AiSetupFailure.QUOTA_EXHAUSTED).contains("额度"))
        assertTrue(aiSetupFailureMessage(AiSetupFailure.DNS).contains("DNS"))
        assertTrue(aiSetupFailureMessage(AiSetupFailure.TIMEOUT).contains("超时"))
        assertTrue(aiSetupFailureMessage(AiSetupFailure.SERVER).contains("5xx"))
        assertTrue(aiSetupFailureMessage(AiSetupFailure.MODEL_UNSUPPORTED).contains("模型"))
        assertTrue(aiSetupFailureMessage(AiSetupFailure.CAPABILITY_UNSUPPORTED).contains("能力"))
        assertEquals("服务建议约 30 秒后再试", aiRetrySuggestion(30_000L))
    }

    @Test
    fun `persisted connection status maps to explicit setup states`() {
        assertEquals(AiSetupState.Connected, AiConnectionStatus.CONNECTED.toAiSetupState())
        assertEquals(AiSetupState.Ready, AiConnectionStatus.READY.toAiSetupState())
        assertEquals(AiSetupState.Unverified(), AiConnectionStatus.UNVERIFIED.toAiSetupState())
        assertEquals(AiSetupState.Incompatible(), AiConnectionStatus.INCOMPATIBLE.toAiSetupState())
        assertEquals(AiSetupState.Failed(AiSetupFailure.UNKNOWN), AiConnectionStatus.FAILED.toAiSetupState())
    }
}

private class FakeChecker(
    private val result: AiConnectivityResult,
) : AiConnectivityChecker {
    override suspend fun check(settings: AiSettings): AiConnectivityResult = result
}
