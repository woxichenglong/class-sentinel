package com.classsentinel.core.llm

import com.classsentinel.data.AiSettings
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener

/** 可替换的 AI 连通性检查 seam；使用固定请求，不携带姓名或其他个人信息。 */
typealias AiConnectivityStreamChat = (List<Map<String, String>>, LlmConfig) -> Flow<String>

interface AiConnectivityChecker {
    suspend fun check(settings: AiSettings): AiConnectivityResult
}

enum class AiSetupFailure {
    CONFIG,
    AUTH,
    RATE_LIMIT,
    NETWORK,
    SERVER,
    TIMEOUT,
    INVALID_RESPONSE,
    SAVE_FAILED,
    UNKNOWN,
}

sealed interface AiConnectivityResult {
    data object Success : AiConnectivityResult
    data class Failure(val reason: AiSetupFailure) : AiConnectivityResult
}

/** 复用现有 LlmClient 的轻量固定 JSON 连通性检查。 */
class LlmAiConnectivityChecker(
    private val streamChat: AiConnectivityStreamChat? = null,
    private val client: LlmClient = LlmClient(),
) : AiConnectivityChecker {

    override suspend fun check(settings: AiSettings): AiConnectivityResult {
        val normalized = runCatching { AiProviderPreset.normalizeSettings(settings) }.getOrNull()
            ?: return AiConnectivityResult.Failure(AiSetupFailure.CONFIG)
        if (normalized.apiKey.isBlank()) {
            return AiConnectivityResult.Failure(AiSetupFailure.CONFIG)
        }

        val config = LlmConfig(
            baseUrl = normalized.baseUrl,
            apiKey = normalized.apiKey,
            model = normalized.model,
            thinkingDisabled = true,
            maxTokens = 32,
            responseFormatJsonObject = true,
        )
        return try {
            val raw = withTimeoutOrNull(CONNECTIVITY_TIMEOUT_MS) {
                request(connectivityMessages(), config)
            }
            when {
                raw == null -> AiConnectivityResult.Failure(AiSetupFailure.TIMEOUT)
                raw.isBlank() -> AiConnectivityResult.Failure(AiSetupFailure.INVALID_RESPONSE)
                parseOk(raw) -> AiConnectivityResult.Success
                else -> AiConnectivityResult.Failure(AiSetupFailure.INVALID_RESPONSE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            AiConnectivityResult.Failure(e.error.toSetupFailure())
        } catch (_: IOException) {
            AiConnectivityResult.Failure(AiSetupFailure.NETWORK)
        } catch (_: Exception) {
            AiConnectivityResult.Failure(AiSetupFailure.UNKNOWN)
        }
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

    private companion object {
        const val CONNECTIVITY_TIMEOUT_MS = 10_000L
    }
}

private fun LlmError.toSetupFailure(): AiSetupFailure = when (kind) {
    LlmError.Kind.AUTH -> AiSetupFailure.AUTH
    LlmError.Kind.CONFIG -> AiSetupFailure.CONFIG
    LlmError.Kind.RATE_LIMIT -> AiSetupFailure.RATE_LIMIT
    LlmError.Kind.NETWORK -> AiSetupFailure.NETWORK
    LlmError.Kind.SERVER -> AiSetupFailure.SERVER
    LlmError.Kind.EMPTY -> AiSetupFailure.INVALID_RESPONSE
    LlmError.Kind.UNKNOWN -> AiSetupFailure.UNKNOWN
}
