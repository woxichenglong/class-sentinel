package com.classsentinel.ui

import com.classsentinel.core.llm.AiConnectivityChecker
import com.classsentinel.core.llm.AiConnectivityResult
import com.classsentinel.core.llm.AiProviderPreset
import com.classsentinel.core.llm.AiSetupFailure
import com.classsentinel.data.AiSettings
import kotlinx.coroutines.CancellationException

/** 首启只在内存中维护的步骤；重启时从已保存事实重新推导。 */
sealed interface OnboardingStep {
    data object AiConfig : OnboardingStep
    data object NameConfig : OnboardingStep
    data object VoiceNameCalibration : OnboardingStep
    data object Permissions : OnboardingStep
}

/** AI 配置页的瞬态 UI 状态，不写入 DataStore。 */
sealed interface AiSetupState {
    data object Unconfigured : AiSetupState
    data object Editing : AiSetupState
    data object Checking : AiSetupState
    data object Ready : AiSetupState
    data class Failed(val reason: AiSetupFailure) : AiSetupState
}

internal const val AI_NAME_PRIVACY_NOTICE =
    "使用 AI 生成识别容错时，你填写的姓名会发送给当前配置的 AI 服务。"

internal const val AI_NAME_VOICE_PRIVACY_NOTICE =
    "姓名语音校准仅使用设备上的 X-ASR 处理，不会上传录音。"

internal fun isAiSettingsComplete(settings: AiSettings): Boolean =
    settings.apiKey.trim().isNotEmpty() && AiProviderPreset.isValid(settings.baseUrl, settings.model)

internal fun initialAiSetupState(settings: AiSettings): AiSetupState =
    if (isAiSettingsComplete(settings)) AiSetupState.Ready else AiSetupState.Unconfigured

/**
 * 按持久化事实恢复首启步骤：完成标记优先，其次是已保存姓名，再其次是完整 AI 配置。
 * 返回 null 表示已经完成 onboarding，应由根导航进入首页。
 */
internal fun initialOnboardingStep(
    aiConfigured: Boolean,
    hasName: Boolean,
    onboardingCompleted: Boolean,
): OnboardingStep? = when {
    onboardingCompleted -> null
    hasName -> OnboardingStep.Permissions
    aiConfigured -> OnboardingStep.NameConfig
    else -> OnboardingStep.AiConfig
}

/** 保存现有 AI 配置后做一次固定 JSON 连通性检查；状态本身不持久化。 */
internal suspend fun saveAndCheckAi(
    draft: AiSettings,
    save: suspend (AiSettings) -> Unit,
    checker: AiConnectivityChecker,
): AiSetupState {
    val normalized = runCatching { AiProviderPreset.normalizeSettings(draft) }.getOrNull()
        ?: return AiSetupState.Failed(AiSetupFailure.CONFIG)
    if (normalized.apiKey.isBlank()) {
        return AiSetupState.Failed(AiSetupFailure.CONFIG)
    }

    return try {
        save(normalized)
        when (val result = checker.check(normalized)) {
            AiConnectivityResult.Success -> AiSetupState.Ready
            is AiConnectivityResult.Failure -> AiSetupState.Failed(result.reason)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        AiSetupState.Failed(AiSetupFailure.SAVE_FAILED)
    }
}

internal fun canSkipAi(state: AiSetupState): Boolean = state !is AiSetupState.Checking

internal fun nextStepAfterAiSetup(state: AiSetupState, skipped: Boolean): OnboardingStep? =
    if (skipped || state !is AiSetupState.Checking) OnboardingStep.NameConfig else null

internal fun aiSetupFailureMessage(reason: AiSetupFailure): String = when (reason) {
    AiSetupFailure.CONFIG -> "AI 配置不完整，可暂时跳过"
    AiSetupFailure.AUTH -> "AI 认证失败，可暂时跳过或检查 API Key"
    AiSetupFailure.RATE_LIMIT -> "AI 服务暂时限流，可稍后重试或跳过"
    AiSetupFailure.NETWORK -> "网络暂时不可用，可暂时跳过"
    AiSetupFailure.SERVER -> "AI 服务暂时不可用，可稍后重试或跳过"
    AiSetupFailure.TIMEOUT -> "AI 检查超时，可暂时跳过"
    AiSetupFailure.INVALID_RESPONSE -> "AI 返回格式异常，可暂时跳过"
    AiSetupFailure.SAVE_FAILED -> "AI 配置保存失败，请重试或暂时跳过"
    AiSetupFailure.UNKNOWN -> "AI 配置检查失败，可暂时跳过"
}
