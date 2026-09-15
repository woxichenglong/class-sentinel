package com.classsentinel.core.llm

import com.classsentinel.data.AiSettings
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/** 可替换的 AI 连通性检查 seam；使用固定请求，不携带姓名或其他个人信息。 */
typealias AiConnectivityStreamChat = (List<Map<String, String>>, LlmConfig) -> Flow<String>
typealias AiConnectivityStateListener = (AiConnectivityCheckState) -> Unit

/** Durable status projection; CONNECTED and READY are also used as transient UI phases. */
enum class AiConnectionStatus {
    CONNECTED,
    READY,
    UNVERIFIED,
    INCOMPATIBLE,
    FAILED;

    companion object {
        fun fromStored(value: String?): AiConnectionStatus =
            value?.trim()?.uppercase(Locale.ROOT)?.let { normalized ->
                runCatching { valueOf(normalized) }.getOrNull()
            } ?: UNVERIFIED
    }
}

/** Optional provider features checked separately from basic reachability/model generation. */
enum class AiCapability {
    THINKING_DISABLED,
    JSON_OBJECT_OUTPUT,
}

object AiConnectivityRequirements {
    /** Current formal ClassSentinel name-variant path uses both optional capabilities. */
    val CLASS_SENTINEL: Set<AiCapability> = setOf(
        AiCapability.THINKING_DISABLED,
        AiCapability.JSON_OBJECT_OUTPUT,
    )
}

/** Connectivity lifecycle exposed to onboarding/settings without provider details. */
sealed interface AiConnectivityCheckState {
    data object Idle : AiConnectivityCheckState
    data class Checking(val attempt: Int, val maxAttempts: Int) : AiConnectivityCheckState
    data object Connected : AiConnectivityCheckState
    data object CapabilityChecking : AiConnectivityCheckState
    data class Retrying(
        val attempt: Int,
        val maxAttempts: Int,
        val reason: AiSetupFailure,
        val retryAfterMs: Long? = null,
    ) : AiConnectivityCheckState
    data object Ready : AiConnectivityCheckState
    data class Unverified(
        val reason: AiSetupFailure,
        val retryAfterMs: Long? = null,
    ) : AiConnectivityCheckState
    data class Incompatible(val reason: AiSetupFailure? = null) : AiConnectivityCheckState
    data class Failed(
        val reason: AiSetupFailure,
        val attempts: Int,
        val retryable: Boolean = reason.retryable,
        val retryAfterMs: Long? = null,
    ) : AiConnectivityCheckState
}

interface AiConnectivityChecker {
    suspend fun check(settings: AiSettings): AiConnectivityResult

    /**
     * Optional lifecycle-aware overload. Existing test and feature seams only implementing
     * [check] remain source-compatible; production checkers can expose bounded retries.
     */
    suspend fun check(
        settings: AiSettings,
        onStateChange: AiConnectivityStateListener,
    ): AiConnectivityResult {
        onStateChange(AiConnectivityCheckState.Checking(attempt = 1, maxAttempts = 1))
        val result = check(settings)
        onStateChange(result.toCheckState())
        return result
    }
}

enum class AiSetupFailure(
    val retryable: Boolean,
    val status: AiConnectionStatus,
) {
    CONFIG(false, AiConnectionStatus.FAILED),
    AUTH(false, AiConnectionStatus.FAILED),
    FORBIDDEN(false, AiConnectionStatus.FAILED),
    NOT_FOUND(false, AiConnectionStatus.INCOMPATIBLE),
    MODEL_UNSUPPORTED(false, AiConnectionStatus.INCOMPATIBLE),
    CAPABILITY_UNSUPPORTED(false, AiConnectionStatus.INCOMPATIBLE),
    RATE_LIMIT(true, AiConnectionStatus.UNVERIFIED),
    QUOTA_EXHAUSTED(false, AiConnectionStatus.FAILED),
    DNS(true, AiConnectionStatus.UNVERIFIED),
    NETWORK(true, AiConnectionStatus.UNVERIFIED),
    SERVER(true, AiConnectionStatus.UNVERIFIED),
    TIMEOUT(true, AiConnectionStatus.UNVERIFIED),
    INVALID_RESPONSE(false, AiConnectionStatus.FAILED),
    SAVE_FAILED(false, AiConnectionStatus.FAILED),
    UNKNOWN(false, AiConnectionStatus.FAILED),
}

sealed interface AiConnectivityResult {
    data object Success : AiConnectivityResult
    data class Failure(
        val reason: AiSetupFailure,
        val attempts: Int = 1,
        val retryable: Boolean = reason.retryable,
        val retryAfterMs: Long? = null,
        val status: AiConnectionStatus = reason.status,
    ) : AiConnectivityResult
}

/** Bounded retry and deadline policy for the installation/onboarding probe only. */
data class AiConnectivityRetryPolicy(
    val maxAttempts: Int = 2,
    val requestTimeoutMs: Long = 10_000L,
    val initialBackoffMs: Long = 250L,
    val maxBackoffMs: Long = 1_000L,
    val maxRetryAfterMs: Long = 10_000L,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(requestTimeoutMs > 0L) { "requestTimeoutMs must be positive" }
        require(initialBackoffMs >= 0L) { "initialBackoffMs must not be negative" }
        require(maxBackoffMs >= initialBackoffMs) { "maxBackoffMs must not be below initialBackoffMs" }
        require(maxRetryAfterMs > 0L) { "maxRetryAfterMs must be positive" }
    }

    /** Returns the delay after the completed attempt and before the next attempt. */
    fun backoffAfter(completedAttempt: Int): Long {
        require(completedAttempt > 0) { "completedAttempt must be positive" }
        var value = initialBackoffMs
        repeat((completedAttempt - 1).coerceAtMost(30)) {
            value = if (value >= maxBackoffMs / 2L) {
                maxBackoffMs
            } else {
                (value * 2L).coerceAtMost(maxBackoffMs)
            }
        }
        return value.coerceAtMost(maxBackoffMs)
    }
}

/**
 * Basic reachability/auth/model probe plus an optional second-stage capability check.
 * The basic request deliberately avoids provider-specific optional fields.
 */
class LlmAiConnectivityChecker(
    private val streamChat: AiConnectivityStreamChat? = null,
    private val client: LlmClient = LlmClient(),
    private val retryPolicy: AiConnectivityRetryPolicy = AiConnectivityRetryPolicy(),
    private val requiredCapabilities: Set<AiCapability> = AiConnectivityRequirements.CLASS_SENTINEL,
) : AiConnectivityChecker {

    override suspend fun check(settings: AiSettings): AiConnectivityResult = check(settings) {}

    override suspend fun check(
        settings: AiSettings,
        onStateChange: AiConnectivityStateListener,
    ): AiConnectivityResult {
        val normalized = runCatching { AiProviderPreset.normalizeSettings(settings) }.getOrNull()
            ?: return terminalFailure(AiSetupFailure.CONFIG, 1, onStateChange)
        if (normalized.apiKey.isBlank()) {
            return terminalFailure(AiSetupFailure.CONFIG, 1, onStateChange)
        }

        val basicConfig = LlmConfig(
            baseUrl = normalized.baseUrl,
            apiKey = normalized.apiKey,
            model = normalized.model,
            // A basic probe must not require optional provider capabilities.
            thinkingDisabled = false,
            maxTokens = 8,
            responseFormatJsonObject = false,
            callTimeoutMs = retryPolicy.requestTimeoutMs,
        )
        val capabilityConfig = LlmConfig(
            baseUrl = normalized.baseUrl,
            apiKey = normalized.apiKey,
            model = normalized.model,
            thinkingDisabled = AiCapability.THINKING_DISABLED in requiredCapabilities,
            maxTokens = 8,
            responseFormatJsonObject = AiCapability.JSON_OBJECT_OUTPUT in requiredCapabilities,
            callTimeoutMs = retryPolicy.requestTimeoutMs,
        )

        var completedAttempts = 0
        while (completedAttempts < retryPolicy.maxAttempts) {
            val attempt = ++completedAttempts
            onStateChange(
                AiConnectivityCheckState.Checking(
                    attempt = attempt,
                    maxAttempts = retryPolicy.maxAttempts,
                ),
            )
            var stage = ProbeStage.BASIC
            val result = try {
                val basic = runProbe(
                    messages = basicConnectivityMessages(),
                    config = basicConfig,
                    stage = ProbeStage.BASIC,
                )
                if (basic is AiConnectivityResult.Failure || requiredCapabilities.isEmpty()) {
                    if (basic is AiConnectivityResult.Success) {
                        onStateChange(AiConnectivityCheckState.Connected)
                    }
                    basic
                } else {
                    // Basic generation succeeded; only now test capabilities required by the
                    // formal ClassSentinel path.
                    onStateChange(AiConnectivityCheckState.Connected)
                    onStateChange(AiConnectivityCheckState.CapabilityChecking)
                    stage = ProbeStage.CAPABILITY
                    runProbe(
                        messages = capabilityMessages(),
                        config = capabilityConfig,
                        stage = stage,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                val reason = e.error.toSetupFailure(stage)
                AiConnectivityResult.Failure(
                    reason = reason,
                    attempts = attempt,
                    retryable = e.error.retryable,
                    retryAfterMs = e.error.retryAfterMs,
                )
            } catch (e: IOException) {
                val reason = LlmError(classifyTransportException(e)).toSetupFailure(stage)
                AiConnectivityResult.Failure(reason, attempt)
            } catch (_: Exception) {
                val reason = if (stage == ProbeStage.CAPABILITY) {
                    AiSetupFailure.CAPABILITY_UNSUPPORTED
                } else {
                    AiSetupFailure.UNKNOWN
                }
                AiConnectivityResult.Failure(reason, attempt)
            }

            if (result is AiConnectivityResult.Success) {
                onStateChange(AiConnectivityCheckState.Ready)
                return result
            }

            val failure = result as AiConnectivityResult.Failure
            val terminal = failure.copy(attempts = attempt)
            if (failure.status == AiConnectionStatus.INCOMPATIBLE) {
                onStateChange(AiConnectivityCheckState.Incompatible(failure.reason))
                return terminal
            }
            if (!failure.retryable || attempt >= retryPolicy.maxAttempts) {
                emitTerminalState(terminal, onStateChange)
                return terminal
            }
            if (failure.retryAfterMs != null && failure.retryAfterMs > retryPolicy.maxRetryAfterMs) {
                // Interactive checks must not block longer than the local limit. Keep the
                // original provider hint so the UI can tell the user when to retry.
                emitTerminalState(terminal, onStateChange)
                return terminal
            }

            val nextAttempt = attempt + 1
            onStateChange(
                AiConnectivityCheckState.Retrying(
                    attempt = nextAttempt,
                    maxAttempts = retryPolicy.maxAttempts,
                    reason = failure.reason,
                    retryAfterMs = failure.retryAfterMs,
                ),
            )
            val waitMs = failure.retryAfterMs ?: retryPolicy.backoffAfter(attempt)
            if (waitMs > 0L) delay(waitMs)
        }

        // The loop always returns on a terminal failure or success.
        return terminalFailure(AiSetupFailure.UNKNOWN, completedAttempts, onStateChange)
    }

    private suspend fun request(
        messages: List<Map<String, String>>,
        config: LlmConfig,
    ): String = (streamChat?.invoke(messages, config) ?: client.streamChat(messages, config))
        .toList()
        .joinToString("")
        .trim()

    private suspend fun runProbe(
        messages: List<Map<String, String>>,
        config: LlmConfig,
        stage: ProbeStage,
    ): AiConnectivityResult {
        // withTimeoutOrNull only consumes its own deadline; caller cancellation propagates.
        val raw = withTimeoutOrNull(retryPolicy.requestTimeoutMs) {
            request(messages, config)
        } ?: return AiConnectivityResult.Failure(AiSetupFailure.TIMEOUT)
        if (raw.isBlank()) {
            return AiConnectivityResult.Failure(
                if (stage == ProbeStage.CAPABILITY) {
                    AiSetupFailure.CAPABILITY_UNSUPPORTED
                } else {
                    AiSetupFailure.INVALID_RESPONSE
                },
            )
        }
        if (stage == ProbeStage.CAPABILITY &&
            AiCapability.JSON_OBJECT_OUTPUT in requiredCapabilities &&
            !parseOk(raw)
        ) {
            return AiConnectivityResult.Failure(AiSetupFailure.CAPABILITY_UNSUPPORTED)
        }
        return AiConnectivityResult.Success
    }

    private fun parseOk(raw: String): Boolean {
        val value = try {
            val tokener = JSONTokener(raw.trim())
            val parsed = tokener.nextValue()
            if (parsed !is JSONObject || tokener.nextClean() != 0.toChar()) return false
            parsed
        } catch (_: Exception) {
            return false
        }
        val keys = mutableSetOf<String>()
        val iterator = value.keys()
        while (iterator.hasNext()) keys += iterator.next()
        if (keys != setOf("ok")) return false
        return value.opt("ok") == true
    }

    private fun basicConnectivityMessages(): List<Map<String, String>> = listOf(
        mapOf(
            "role" to "system",
            "content" to "你是 AI 连通性检查服务。请完成这次最小生成，并只返回一句简短确认。",
        ),
        mapOf(
            "role" to "user",
            "content" to "这是固定连接检查，不包含个人信息。请完成一次最小文本生成。",
        ),
    )

    private fun capabilityMessages(): List<Map<String, String>> = listOf(
        mapOf(
            "role" to "system",
            "content" to "只输出严格 JSON 对象，且只能有 ok 一个布尔字段。不要输出解释、Markdown 或代码围栏。",
        ),
        mapOf(
            "role" to "user",
            "content" to "这是正式能力检查，不包含个人信息。请返回 {\"ok\":true}。",
        ),
    )

    private fun emitTerminalState(
        failure: AiConnectivityResult.Failure,
        onStateChange: AiConnectivityStateListener,
    ) {
        when (failure.status) {
            AiConnectionStatus.UNVERIFIED -> onStateChange(
                AiConnectivityCheckState.Unverified(
                    reason = failure.reason,
                    retryAfterMs = failure.retryAfterMs,
                ),
            )
            AiConnectionStatus.INCOMPATIBLE -> onStateChange(
                AiConnectivityCheckState.Incompatible(failure.reason),
            )
            else -> onStateChange(
                AiConnectivityCheckState.Failed(
                    reason = failure.reason,
                    attempts = failure.attempts,
                    retryable = failure.retryable,
                    retryAfterMs = failure.retryAfterMs,
                ),
            )
        }
    }

    private fun terminalFailure(
        reason: AiSetupFailure,
        attempts: Int,
        onStateChange: AiConnectivityStateListener,
    ): AiConnectivityResult.Failure {
        val result = AiConnectivityResult.Failure(reason = reason, attempts = attempts)
        onStateChange(
            AiConnectivityCheckState.Failed(
                reason = reason,
                attempts = attempts,
                retryable = result.retryable,
            ),
        )
        return result
    }
}

private enum class ProbeStage {
    BASIC,
    CAPABILITY,
}

private fun LlmError.toSetupFailure(stage: ProbeStage): AiSetupFailure {
    val mapped = when (kind) {
    LlmError.Kind.AUTH -> AiSetupFailure.AUTH
    LlmError.Kind.FORBIDDEN -> AiSetupFailure.FORBIDDEN
    LlmError.Kind.NOT_FOUND -> AiSetupFailure.NOT_FOUND
    LlmError.Kind.MODEL_UNSUPPORTED -> AiSetupFailure.MODEL_UNSUPPORTED
    LlmError.Kind.CAPABILITY_UNSUPPORTED -> AiSetupFailure.CAPABILITY_UNSUPPORTED
    LlmError.Kind.CONFIG -> AiSetupFailure.CONFIG
    LlmError.Kind.RATE_LIMIT -> AiSetupFailure.RATE_LIMIT
    LlmError.Kind.QUOTA_EXHAUSTED -> AiSetupFailure.QUOTA_EXHAUSTED
    LlmError.Kind.DNS -> AiSetupFailure.DNS
    LlmError.Kind.NETWORK -> AiSetupFailure.NETWORK
    LlmError.Kind.TIMEOUT -> AiSetupFailure.TIMEOUT
    LlmError.Kind.SERVER -> AiSetupFailure.SERVER
    LlmError.Kind.EMPTY,
    LlmError.Kind.INVALID_RESPONSE,
    -> AiSetupFailure.INVALID_RESPONSE
    LlmError.Kind.UNKNOWN -> AiSetupFailure.UNKNOWN
    }
    return if (stage == ProbeStage.CAPABILITY &&
        mapped in setOf(AiSetupFailure.CONFIG, AiSetupFailure.INVALID_RESPONSE)
    ) {
        AiSetupFailure.CAPABILITY_UNSUPPORTED
    } else {
        mapped
    }
}

private fun AiConnectivityResult.toCheckState(): AiConnectivityCheckState = when (this) {
    AiConnectivityResult.Success -> AiConnectivityCheckState.Ready
    is AiConnectivityResult.Failure -> when (status) {
        AiConnectionStatus.UNVERIFIED -> AiConnectivityCheckState.Unverified(
            reason = reason,
            retryAfterMs = retryAfterMs,
        )
        AiConnectionStatus.INCOMPATIBLE -> AiConnectivityCheckState.Incompatible(reason)
        else -> AiConnectivityCheckState.Failed(
            reason = reason,
            attempts = attempts,
            retryable = retryable,
            retryAfterMs = retryAfterMs,
        )
    }
}
