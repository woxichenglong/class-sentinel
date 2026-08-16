package com.classsentinel.core.speech

import com.classsentinel.core.audio.VadSplitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容 ASR 引擎基类：POST {base}/audio/transcriptions（multipart）。
 * 内置 VAD 分段：PCM → 有声片段 WAV → 逐段 HTTP 转写 → 文本流。
 * 一个基类喂多个免费模型（TeleSpeechASR / SenseVoiceSmall），仅 model 参数不同。
 */
open class OpenAiCompatAsrEngine(
    final override val name: String,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val vad: VadSplitter = VadSplitter(),
    private val client: OkHttpClient = defaultClient(),
) : SpeechEngine {

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override fun transcribe(pcm: Flow<ShortArray>): Flow<String> = flow {
        vad.split(pcm).collect { wav ->
            val text = runCatching { transcribeSegment(wav) }
            text.onSuccess { if (it.isNotBlank()) emit(it) }
                .onFailure { e -> println("[ASR:$name] segment failed: ${e.message}") }
            // 单段失败静默跳过（重试 1 次已内置），连续失败由 FallbackSpeechEngine 降级
        }
    }

    private suspend fun transcribeSegment(wav: ByteArray): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", model)
            .build()
        val req = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        try {
            executeWithRetry(req)
        } catch (e: IOException) {
            throw e
        }
    }

    private fun executeWithRetry(req: Request): String {
        var lastErr: IOException? = null
        repeat(2) {
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                    }
                    val text = JSONObject(resp.body!!.string()).optString("text", "")
                    if (text.isNotBlank()) return text
                    throw IOException("empty text in response")
                }
            } catch (e: IOException) {
                lastErr = e
            }
        }
        throw lastErr ?: IOException("transcribe failed")
    }
}
