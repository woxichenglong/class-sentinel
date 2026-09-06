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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
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
            val bodyStr = payload.toString()
            val req = Request.Builder()
                .url("${cfg.baseUrl.trimEnd('/')}/chat/completions")
                .addHeader("Authorization", "Bearer ${cfg.apiKey}")
                .addHeader("Accept", "text/event-stream")
                .post(bodyStr.toRequestBody("application/json".toMediaType()))
                .build()

            // 注意: 不能在 withContext(IO) 里 emit(Flow 不变式违规)，用 flowOn 切调度
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 ->
                        throw LlmException(LlmError(LlmError.Kind.AUTH))
                    resp.code == 429 ->
                        throw LlmException(LlmError(LlmError.Kind.RATE_LIMIT))
                    resp.code in 500..599 ->
                        throw LlmException(LlmError(LlmError.Kind.SERVER))
                    !resp.isSuccessful ->
                        throw LlmException(LlmError(LlmError.Kind.CONFIG))
                }
                val source = resp.body?.source() ?: throw IOException("LLM empty body")
                while (true) {
                    val line = source.readUtf8Line() ?: break // EOF 兜底
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("data:")) continue // 忽略注释/空行
                    val data = trimmed.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break
                    val content = JSONObject(data)
                        .optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("delta")
                        ?.opt("content")
                    if (content != null && content != JSONObject.NULL) {
                        emit(content.toString())
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            throw e
        } catch (_: IOException) {
            throw LlmException(LlmError(LlmError.Kind.NETWORK))
        } catch (_: Exception) {
            throw LlmException(LlmError(LlmError.Kind.UNKNOWN))
        }
    }.flowOn(Dispatchers.IO)
}

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
