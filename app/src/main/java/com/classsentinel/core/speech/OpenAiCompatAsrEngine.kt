package com.classsentinel.core.speech

import com.classsentinel.core.audio.VadSplitter
import com.classsentinel.core.audio.WavSegment
import com.classsentinel.core.log.SafeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
 * OpenAI 兼容 ASR 引擎：POST {base}/audio/transcriptions（multipart）。
 *
 * 新主路径是 [SegmentSpeechEngine.transcribeSegment]：一次只转写一个 [WavSegment]
 * （该段自己的 44 字节 WAV 头 + PCM16），引擎内不做 VAD、不 catch 后跳过失败段；
 * 失败抛携带 [AsrError] 的 [AsrException]。一个基类喂多个免费模型
 * （XingChenASR-V3.2-Ultra / SenseVoiceSmall），仅 model 参数不同。
 *
 * 旧 `Flow<ShortArray> -> Flow<String>` 路径（[SpeechEngine.transcribe]）由
 * [LegacySpeechAdapter] 提供：复用 [VadSplitter.segments] + 本单段接口，不复制 VAD、
 * 不静默吞错（失败抛 [AsrException]，旧 FallbackSpeechEngine 以异常切换引擎）。
 */
open class OpenAiCompatAsrEngine(
    override val name: String,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val vad: VadSplitter = VadSplitter(),
    private val client: OkHttpClient = defaultClient(),
) : SegmentSpeechEngine, SpeechEngine {

    companion object {
        /** 有限重试策略：HTTP 5xx/429 最多重试一次；401/403 不重试。 */
        const val MAX_RETRIES = 1

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** 旧接口：交给 legacy adapter（VAD 分段 + 单段转写 + typed failure 上抛）。 */
    override fun transcribe(pcm: Flow<ShortArray>): Flow<String> =
        LegacySpeechAdapter(this, vad).transcribe(pcm)

    /** 新主路径：单段 multipart 转写。失败返回 [Result.failure]（[AsrException] 携带 [AsrError]）。 */
    override suspend fun transcribeSegment(segment: WavSegment): Result<String> {
        val startedAt = System.nanoTime()
        return try {
            // 阻塞 OkHttp execute() 必须在 IO dispatcher 上执行，调用方协程不被网络阻塞；
            // 不能裸 runCatching：它会把 CancellationException 吞成 Result.failure，
            // 父协程无法及时结束。取消必须原样重新抛出。
            val text = withContext(Dispatchers.IO) { transcribeSegmentBlocking(segment) }
            SafeLog.d(
                "asr_complete",
                mapOf(
                    "module" to "OpenAiCompatAsrEngine",
                    "engine" to name,
                    "segmentId" to segment.id,
                    "chars" to text.length,
                    "elapsedMs" to (System.nanoTime() - startedAt) / 1_000_000L,
                ),
            )
            Result.success(text)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            SafeLog.w(
                "asr_failed",
                mapOf(
                    "module" to "OpenAiCompatAsrEngine",
                    "engine" to name,
                    "segmentId" to segment.id,
                    "errorCode" to if (t is AsrException) t.error.kind.name else "NETWORK",
                    "elapsedMs" to (System.nanoTime() - startedAt) / 1_000_000L,
                ),
            )
            Result.failure(t)
        }
    }

    /** 单段阻塞转写（IO 线程执行）。 */
    private fun transcribeSegmentBlocking(segment: WavSegment): String {
        val req = buildRequest(segment)
        return executeWithRetry(req, segment.id)
    }

    private fun buildRequest(segment: WavSegment): Request {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", segment.bytes.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", model)
            .build()
        return Request.Builder()
            .url("${baseUrl.trimEnd('/')}/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
    }

    /**
     * 执行并映射 HTTP → [AsrError]：
     * - 5xx / 429：有限重试（[MAX_RETRIES] 次），失败返回 SERVER / RATE_LIMIT（retriable）；
     * - 401 / 403：只请求一次，返回 AUTH（non-retriable）；
     * - 200 但空/空白 text：返回 EMPTY（non-retriable）；
     * - 成功只返回一次非空文本。
     */
    private fun executeWithRetry(req: Request, segmentId: String): String {
        var last: IOException? = null
        repeat(MAX_RETRIES + 1) { attempt ->
            try {
                client.newCall(req).execute().use { resp ->
                    val code = resp.code
                    if (code in 500..599 || code == 429) {
                        SafeLog.w(
                            "asr_http_retry",
                            mapOf(
                                "module" to "OpenAiCompatAsrEngine",
                                "engine" to name,
                                "segmentId" to segmentId,
                                "httpCode" to code,
                                "retryCount" to attempt,
                            ),
                        )
                        throw IOException("HTTP $code")
                    }
                    if (code == 401 || code == 403) {
                        SafeLog.w(
                            "asr_http_failed",
                            mapOf(
                                "module" to "OpenAiCompatAsrEngine",
                                "engine" to name,
                                "segmentId" to segmentId,
                                "httpCode" to code,
                                "retryCount" to attempt,
                            ),
                        )
                        throw AsrException(AsrError.fromHttp(code))
                    }
                    if (!resp.isSuccessful) {
                        SafeLog.w(
                            "asr_http_failed",
                            mapOf(
                                "module" to "OpenAiCompatAsrEngine",
                                "engine" to name,
                                "segmentId" to segmentId,
                                "httpCode" to code,
                                "retryCount" to attempt,
                            ),
                        )
                        throw AsrException(AsrError.fromHttp(code))
                    }
                    val text = JSONObject(resp.body!!.string()).optString("text", "")
                    if (text.isBlank()) throw AsrException(AsrError.emptyText())
                    return text
                }
            } catch (e: AsrException) {
                throw e // 契约性失败不再重试（AUTH / EMPTY / 未知状态）
            } catch (e: IOException) {
                last = e
            }
        }
        val err = last ?: IOException("transcribe failed")
        val code = err.message?.let { Regex("HTTP (\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        throw if (code != null) AsrException(AsrError.fromHttp(code)) else AsrException(AsrError.network())
    }
}
