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

/** 可替换的 AI 连通性检查 seam；使用固定请求，不携带姓名或其他个人信息。 */
typealias AiConnectivityStreamChat = (List<Map<String, String>>, LlmConfig) -> Flow<String>
typealias AiConnectivityStateListener = (AiConnectivityCheckState) -> Unit

/** Connectivity lifecycle exposed to onboarding/settings without provider details. */
sealed interface AiConnectivityCheckState {
    data object Idle : AiConnectivityCheckState
    data class Checking(val attempt: Int, val maxAttempts: Int) : AiConnectivityCheckState
    data class Retrying(
        val attempt: Int,
        val maxAttempts: Int,
        val reason: AiSetupFailure,
    ) : AiConnectivityCheckState
    data object Ready : AiConnectivityCheckState
    data class Failed(
        val reason: AiSetupFailure,
        val attempts: Int,
        val retryable: Boolean = reason.retryable,
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

enum class AiSetupFailure(val retryable: Boolean) {
    CONFIG(false),
    AUTH(false),
    FORBIDDEN(false),
    NOT_FOUND(false),
    MODEL_UNSUPPORTED(false),
    RATE_LIMIT(true),
    QUOTA_EXHAUSTED(false),
    DNS(true),
    NETWORK(true),
    SERVER(true),
    TIMEOUT(true),
    INVALID_RESPONSE(false),
    SAVE_FAILED(false),
    UNKNOWN(false),
}

sealed interface AiConnectivityResult {
    data object Success : AiConnectivityResult
    data class Failure(
        val reason: AiSetupFailure,
        val attempts: Int = 1,
        val retryable: Boolean = reason.retryable,
        val retryAfterMs: Long? = null,
    ) : AiConnectivityResult
}

/** Bounded retry and deadline policy for the installation/onboarding probe only. */
data class AiConnectivityRetryPolicy(
    val maxAttempts: Int = 2,
    val requestTimeoutMs: Long = 10_000L,
    val initialBackoffMs: Long = 250L,
    val maxBackoffMs: Long = 1_000L,
    val maxRetryAfterMs: Long = 60_000L,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        require(requestTimeoutMs > 0L) { "requestTimeoutMs must be positive" }
        require(initialBackoffMs >= 0L) { "initialBackoffMs must not be negative" }
        require(maxBackoffMs >= initialBackoffMs) { "maxBackoffMs must not be below initialBackoffMs" }
        require(maxRetryAfterMs >= 0L) { "maxRetryAfterMs must not be negative" }
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

/** 复用现有 LlmClient 的轻量固定 JSON 连通性检查。 */
class LlmAiConnectivityChecker(
    private val streamChat: AiConnectivityStreamChat? = null,
    private val client: LlmClient = LlmClient(),
    private val retryPolicy: AiConnectivityRetryPolicy = AiConnectivityRetryPolicy(),
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

        val config = LlmConfig(
            baseUrl = normalized.baseUrl,
            apiKey = normalized.apiKey,
            model = normalized.model,
            thinkingDisabled = true,
            // This is a real completion, but the response contract keeps the probe tiny.
            maxTokens = 8,
            responseFormatJsonObject = true,
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
            val result = try {
                // withTimeoutOrNull only consumes its own deadline; caller cancellation propagates.
                val raw = withTimeoutOrNull(retryPolicy.requestTimeoutMs) {
                    request(connectivityMessages(), config)
                }
                when {
                    raw == null -> AiConnectivityResult.Failure(AiSetupFailure.TIMEOUT, attempt)
                    raw.isBlank() -> AiConnectivityResult.Failure(AiSetupFailure.INVALID_RESPONSE, attempt)
                    parseOk(raw) -> AiConnectivityResult.Success
                    else -> AiConnectivityResult.Failure(AiSetupFailure.INVALID_RESPONSE, attempt)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                AiConnectivityResult.Failure(
                    reason = e.error.toSetupFailure(),
                    attempts = attempt,
                    retryable = e.error.retryable,
                    retryAfterMs = e.error.retryAfterMs,
                )
            } catch (e: IOException) {
                val reason = LlmError(classifyTransportException(e)).toSetupFailure()
                AiConnectivityResult.Failure(reason, attempt)
            } catch (_: Exception) {
                AiConnectivityResult.Failure(AiSetupFailure.UNKNOWN, attempt)
            }

            if (result is AiConnectivityResult.Success) {
                onStateChange(AiConnectivityCheckState.Ready)
                return result
            }

            val failure = result as AiConnectivityResult.Failure
            if (!failure.retryable || attempt >= retryPolicy.maxAttempts) {
                val terminal = failure.copy(attempts = attempt)
                onStateChange(
                    AiConnectivityCheckState.Failed(
                        reason = terminal.reason,
                        attempts = terminal.attempts,
                        retryable = terminal.retryable,
                    ),
                )
                return terminal
            }

            val nextAttempt = attempt + 1
            onStateChange(
                AiConnectivityCheckState.Retrying(
                    attempt = nextAttempt,
                    maxAttempts = retryPolicy.maxAttempts,
                    reason = failure.reason,
                ),
            )
            val waitMs = if (failure.retryAfterMs != null) {
                failure.retryAfterMs.coerceIn(0L, retryPolicy.maxRetryAfterMs)
            } else {
                retryPolicy.backoffAfter(attempt)
            }
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

    private fun connectivityMessages(): List<Map<String, String>> = listOf(
        mapOf(
            "role" to "system",
            "content" to "你是 AI 连通性检查服务。只输出严格 JSON 对象，且只能有 ok 一个布尔字段。不要输出解释、Markdown 或代码围栏。",
        ),
        mapOf(
            "role" to "user",
            "content" to "这是固定连接检查，不包含个人信息。请返回 {\"ok\":true}。",
        ),
    )

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

private fun LlmError.toSetupFailure(): AiSetupFailure = when (kind) {
    LlmError.Kind.AUTH -> AiSetupFailure.AUTH
    LlmError.Kind.FORBIDDEN -> AiSetupFailure.FORBIDDEN
    LlmError.Kind.NOT_FOUND -> AiSetupFailure.NOT_FOUND
    LlmError.Kind.MODEL_UNSUPPORTED -> AiSetupFailure.MODEL_UNSUPPORTED
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

private fun AiConnectivityResult.toCheckState(): AiConnectivityCheckState = when (this) {
    AiConnectivityResult.Success -> AiConnectivityCheckState.Ready
    is AiConnectivityResult.Failure -> AiConnectivityCheckState.Failed(
        reason = reason,
        attempts = attempts,
        retryable = retryable,
    )
}
