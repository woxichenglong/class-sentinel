package com.classsentinel.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容 chat completions SSE 流式客户端。
 * POST {base}/chat/completions，逐行读 "data: " 事件，[DONE] 结束，
 * 按序发射每个 choices[0].delta.content。
 */
class LlmClient(
    private val client: OkHttpClient = defaultClient(),
) {

    companion object {
        fun defaultClient(): OkHttpClient = newLlmTransportClient()
    }

    /** 逐个 delta.content 发射；非 2xx 抛 IOException(带状态码) */
    fun streamChat(messages: List<Map<String, String>>, cfg: LlmConfig): Flow<String> = flow {
        try {
            val payload = JSONObject()
                .put("model", cfg.model)
                .put("stream", true)
                .put("messages", JSONArray().apply {
                    messages.forEach { m ->
                        put(JSONObject().put("role", m["role"]).put("content", m["content"]))
                    }
                })
            // deepseek-v4-flash 铁律：不带 thinking disabled 会思维链吃满 max_tokens 返回空
            if (cfg.thinkingDisabled) {
                payload.put("thinking", JSONObject().put("type", "disabled"))
            }
            cfg.maxTokens?.let { payload.put("max_tokens", it) }
            if (cfg.responseFormatJsonObject) {
                payload.put("response_format", JSONObject().put("type", "json_object"))
            }
            val bodyStr = payload.toString()
            val req = Request.Builder()
                .url("${cfg.baseUrl.trimEnd('/')}/chat/completions")
                .addHeader("Authorization", "Bearer ${cfg.apiKey}")
                .addHeader("Accept", "text/event-stream")
                .post(bodyStr.toRequestBody("application/json".toMediaType()))
                .build()

            // 注意: 不能在 withContext(IO) 里 emit(Flow 不变式违规)，用 flowOn 切调度。
            // Probe 可传 per-call timeout，避免协程 deadline 取消后阻塞式 execute 仍拖到默认 read timeout。
            val callClient = cfg.callTimeoutMs?.let { timeoutMs ->
                client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
            } ?: client
            callClient.newCall(req).execute().use { resp ->
                classifyHttpError(resp)?.let { throw LlmException(it) }
                val source = resp.body?.source()
                    ?: throw LlmException(LlmError(LlmError.Kind.EMPTY))
                while (true) {
                    val line = source.readUtf8Line() ?: break // EOF 兜底
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("data:")) continue // 忽略注释/空行
                    val data = trimmed.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break
                    val content = try {
                        JSONObject(data)
                            .optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("delta")
                            ?.opt("content")
                    } catch (_: JSONException) {
                        throw LlmException(LlmError(LlmError.Kind.INVALID_RESPONSE))
                    }
                    if (content != null && content != JSONObject.NULL) {
                        emit(content.toString())
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            throw e
        } catch (e: IOException) {
            throw LlmException(LlmError(classifyTransportException(e)))
        } catch (_: Exception) {
            throw LlmException(LlmError(LlmError.Kind.UNKNOWN))
        }
    }.flowOn(Dispatchers.IO)
}

/**
 * Maps HTTP failures without allowing the provider body to cross the typed error boundary.
 * The bounded body snippet is used only for provider error-code classification.
 */
private fun classifyHttpError(response: Response): LlmError? {
    if (response.isSuccessful) return null

    val body = runCatching {
        response.body?.source()?.use { source ->
            if (!source.request(1)) {
                ""
            } else {
                source.readUtf8(minOf(source.buffer.size, MAX_ERROR_BODY_BYTES))
            }
        }.orEmpty()
    }.getOrDefault("").lowercase(Locale.ROOT)
    val kind = when (response.code) {
        401 -> LlmError.Kind.AUTH
        403 -> LlmError.Kind.FORBIDDEN
        404 -> if (isUnsupportedModel(body)) {
            LlmError.Kind.MODEL_UNSUPPORTED
        } else {
            LlmError.Kind.NOT_FOUND
        }
        408 -> LlmError.Kind.TIMEOUT
        429 -> if (isQuotaExhausted(body)) {
            LlmError.Kind.QUOTA_EXHAUSTED
        } else {
            LlmError.Kind.RATE_LIMIT
        }
        400, 422 -> when {
            isUnsupportedModel(body) -> LlmError.Kind.MODEL_UNSUPPORTED
            isUnsupportedCapability(body) -> LlmError.Kind.CAPABILITY_UNSUPPORTED
            else -> LlmError.Kind.CONFIG
        }
        in 500..599 -> LlmError.Kind.SERVER
        else -> LlmError.Kind.CONFIG
    }
    val retryAfterMs = if (kind == LlmError.Kind.RATE_LIMIT) {
        parseRetryAfter(response.header("Retry-After"))
    } else {
        null
    }
    return LlmError(kind = kind, retryAfterMs = retryAfterMs)
}

private fun isUnsupportedModel(body: String): Boolean {
    if (!body.contains("model")) return false
    return listOf(
        "not_found",
        "not found",
        "does_not_exist",
        "does not exist",
        "unsupported",
        "not supported",
        "unknown model",
        "invalid model",
        "unavailable",
    ).any(body::contains)
}

private fun isUnsupportedCapability(body: String): Boolean {
    val mentionsCapability = body.contains("response_format") ||
        body.contains("json_object") ||
        body.contains("thinking")
    return mentionsCapability && (
        body.contains("unsupported") ||
            body.contains("not supported") ||
            body.contains("invalid") ||
            body.contains("not permitted")
        )
}

private fun isQuotaExhausted(body: String): Boolean =
    listOf(
        "insufficient_quota",
        "quota_exceeded",
        "quota exhausted",
        "exceeded quota",
        "billing_hard_limit",
        "credits exhausted",
        "credits depleted",
        "insufficient balance",
        "余额不足",
        "额度不足",
    ).any(body::contains)

private fun parseRetryAfter(value: String?): Long? {
    val seconds = value?.trim()?.toLongOrNull() ?: return null
    if (seconds < 0L) return null
    return seconds.coerceAtMost(MAX_RETRY_AFTER_SECONDS) * 1_000L
}

private const val MAX_ERROR_BODY_BYTES = 16_384L
private const val MAX_RETRY_AFTER_SECONDS = 60L

/**
 * Shared OkHttp transport builder used by production LLM calls and the debug-only probe.
 * A null protocol list leaves OkHttp's default h2+h1 negotiation untouched for comparison.
 */
internal fun newLlmTransportClient(
    protocols: List<Protocol>? = listOf(Protocol.HTTP_1_1),
    eventListenerFactory: EventListener.Factory? = null,
): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS) // 流式回答要等模型吐字
    .apply { protocols?.let(::protocols) }
    .apply { eventListenerFactory?.let(::eventListenerFactory) }
    .build()
