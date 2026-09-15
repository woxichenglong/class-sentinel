package com.classsentinel.ui

import com.classsentinel.core.llm.AiConnectivityChecker
import com.classsentinel.core.llm.AiConnectivityCheckState
import com.classsentinel.core.llm.AiConnectivityResult
import com.classsentinel.core.llm.AiConnectionStatus
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
    data object Connected : AiSetupState
    data object CapabilityChecking : AiSetupState
    data class Retrying(
        val attempt: Int,
        val maxAttempts: Int,
        val reason: AiSetupFailure,
        val retryAfterMs: Long? = null,
    ) : AiSetupState
    data class Unverified(
        val reason: AiSetupFailure? = null,
        val retryAfterMs: Long? = null,
    ) : AiSetupState
    data class Incompatible(val reason: AiSetupFailure? = null) : AiSetupState
    data object Ready : AiSetupState
    data class Failed(
        val reason: AiSetupFailure,
        val attempts: Int = 1,
        val retryable: Boolean = reason.retryable,
    ) : AiSetupState
}

internal const val AI_NAME_PRIVACY_NOTICE =
    "使用 AI 生成识别容错时，你填写的姓名会发送给当前配置的 AI 服务。"

internal const val AI_NAME_VOICE_PRIVACY_NOTICE =
    "姓名语音校准仅使用设备上的 X-ASR 处理，不会上传录音。"

internal fun isAiSettingsComplete(settings: AiSettings): Boolean =
    settings.apiKey.trim().isNotEmpty() && AiProviderPreset.isValid(settings.baseUrl, settings.model)

internal fun initialAiSetupState(
    settings: AiSettings,
    status: AiConnectionStatus = AiConnectionStatus.UNVERIFIED,
): AiSetupState = when (status) {
    AiConnectionStatus.CONNECTED -> AiSetupState.Connected
    AiConnectionStatus.READY -> AiSetupState.Ready
    AiConnectionStatus.UNVERIFIED -> if (isAiSettingsComplete(settings)) {
        AiSetupState.Editing
    } else {
        AiSetupState.Unconfigured
    }
    AiConnectionStatus.INCOMPATIBLE -> AiSetupState.Incompatible()
    AiConnectionStatus.FAILED -> AiSetupState.Failed(AiSetupFailure.UNKNOWN)
}

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

/** 先检查 AI 草稿，只有连通性成功才保存；状态本身不持久化。 */
internal suspend fun saveAndCheckAi(
    draft: AiSettings,
    save: suspend (AiSettings) -> Unit,
    checker: AiConnectivityChecker,
    onConnectivityState: (AiConnectivityCheckState) -> Unit = {},
    saveVerified: (suspend (AiSettings) -> Unit)? = null,
    saveStatus: (suspend (AiConnectionStatus) -> Unit)? = null,
): AiSetupState {
    val normalized = runCatching { AiProviderPreset.normalizeSettings(draft) }.getOrNull()
        ?: return AiSetupState.Failed(AiSetupFailure.CONFIG)
    if (saveVerified != null) {
        try {
            save(normalized)
            saveStatus?.invoke(AiConnectionStatus.UNVERIFIED)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return AiSetupState.Failed(AiSetupFailure.SAVE_FAILED)
        }
    } else if (normalized.apiKey.isBlank()) {
        return AiSetupState.Failed(AiSetupFailure.CONFIG)
    }

    val connectivity = try {
        checker.check(normalized, onConnectivityState)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return AiSetupState.Failed(AiSetupFailure.UNKNOWN)
    }
    if (connectivity is AiConnectivityResult.Failure) {
        if (saveVerified == null) {
            return AiSetupState.Failed(
                reason = connectivity.reason,
                attempts = connectivity.attempts,
                retryable = connectivity.retryable,
            )
        }
        return try {
            saveStatus?.invoke(connectivity.status)
            connectivity.toAiSetupState()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            AiSetupState.Failed(AiSetupFailure.SAVE_FAILED)
        }
    }

    return try {
        (saveVerified ?: save)(normalized)
        if (saveVerified != null) saveStatus?.invoke(AiConnectionStatus.READY)
        AiSetupState.Ready
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        AiSetupState.Failed(AiSetupFailure.SAVE_FAILED)
    }
}

internal fun canSkipAi(state: AiSetupState): Boolean = when (state) {
    AiSetupState.Checking,
    AiSetupState.Connected,
    AiSetupState.CapabilityChecking,
    is AiSetupState.Retrying,
    -> false
    else -> true
}

internal fun nextStepAfterAiSetup(state: AiSetupState, skipped: Boolean): OnboardingStep? = when {
    skipped -> OnboardingStep.NameConfig
    state is AiSetupState.Ready -> OnboardingStep.NameConfig
    else -> null
}

internal fun aiSetupFailureMessage(reason: AiSetupFailure): String = when (reason) {
    AiSetupFailure.CONFIG -> "AI 配置不完整，可暂时跳过"
    AiSetupFailure.AUTH -> "API Key 认证失败（HTTP 401），请检查 Key"
    AiSetupFailure.FORBIDDEN -> "AI 服务拒绝访问（HTTP 403），请检查账户权限"
    AiSetupFailure.NOT_FOUND -> "AI 接口不存在（HTTP 404），请检查 Base URL"
    AiSetupFailure.MODEL_UNSUPPORTED -> "模型不存在或不受支持，请检查模型名"
    AiSetupFailure.CAPABILITY_UNSUPPORTED -> "当前模型不支持 ClassSentinel 所需能力，请更换模型或服务"
    AiSetupFailure.RATE_LIMIT -> "请求被限流（HTTP 429），稍后重试"
    AiSetupFailure.QUOTA_EXHAUSTED -> "AI 额度已耗尽（HTTP 429），请充值或更换账户"
    AiSetupFailure.DNS -> "无法解析 AI 服务域名（DNS），请检查网络或地址"
    AiSetupFailure.NETWORK -> "网络连接异常，请检查网络后重试"
    AiSetupFailure.SERVER -> "AI 服务异常（5xx），稍后重试"
    AiSetupFailure.TIMEOUT -> "AI 请求超时，请稍后重试"
    AiSetupFailure.INVALID_RESPONSE -> "模型返回格式异常，模型暂不可用"
    AiSetupFailure.SAVE_FAILED -> "AI 配置保存失败，请重试或暂时跳过"
    AiSetupFailure.UNKNOWN -> "AI 检查发生未知错误，请重试或暂时跳过"
}

internal fun aiRetrySuggestion(retryAfterMs: Long?): String? = retryAfterMs?.let { delayMs ->
    val seconds = ((delayMs + 999L) / 1_000L).coerceAtLeast(1L)
    "服务建议约 ${seconds} 秒后再试"
}

internal fun AiConnectionStatus.toAiSetupState(): AiSetupState = when (this) {
    AiConnectionStatus.CONNECTED -> AiSetupState.Connected
    AiConnectionStatus.READY -> AiSetupState.Ready
    AiConnectionStatus.UNVERIFIED -> AiSetupState.Unverified()
    AiConnectionStatus.INCOMPATIBLE -> AiSetupState.Incompatible()
    AiConnectionStatus.FAILED -> AiSetupState.Failed(AiSetupFailure.UNKNOWN)
}

internal fun AiConnectivityResult.toAiSetupState(): AiSetupState = when (this) {
    AiConnectivityResult.Success -> AiSetupState.Ready
    is AiConnectivityResult.Failure -> when (status) {
        AiConnectionStatus.UNVERIFIED -> AiSetupState.Unverified(
            reason = reason,
            retryAfterMs = retryAfterMs,
        )
        AiConnectionStatus.INCOMPATIBLE -> AiSetupState.Incompatible(reason)
        else -> AiSetupState.Failed(
            reason = reason,
            attempts = attempts,
            retryable = retryable,
        )
    }
}

internal fun AiConnectivityCheckState.toAiSetupState(): AiSetupState = when (this) {
    AiConnectivityCheckState.Idle -> AiSetupState.Editing
    is AiConnectivityCheckState.Checking -> AiSetupState.Checking
    AiConnectivityCheckState.Connected -> AiSetupState.Connected
    AiConnectivityCheckState.CapabilityChecking -> AiSetupState.CapabilityChecking
    is AiConnectivityCheckState.Retrying -> AiSetupState.Retrying(
        attempt = attempt,
        maxAttempts = maxAttempts,
        reason = reason,
        retryAfterMs = retryAfterMs,
    )
    is AiConnectivityCheckState.Unverified -> AiSetupState.Unverified(
        reason = reason,
        retryAfterMs = retryAfterMs,
    )
    is AiConnectivityCheckState.Incompatible -> AiSetupState.Incompatible(reason)
    AiConnectivityCheckState.Ready -> AiSetupState.Ready
    is AiConnectivityCheckState.Failed -> AiSetupState.Failed(
        reason = reason,
        attempts = attempts,
        retryable = retryable,
    )
}
