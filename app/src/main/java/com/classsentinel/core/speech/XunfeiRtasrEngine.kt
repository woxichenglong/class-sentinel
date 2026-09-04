package com.classsentinel.core.speech

import com.classsentinel.core.log.SafeLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONObject
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

private const val XUNFEI_SAMPLE_RATE_HZ = 16_000L
private const val DEFAULT_SILENCE_AMPLITUDE_THRESHOLD = 500

/**
 * 讯飞实时语音转写（增强引擎，低延迟流式，免费额度手动开启）。
 * 协议要点（官方文档 2026-08 实抓）：
 * - wss://rtasr.xfyun.cn/v1/ws?appid=&ts=&signa=&pd=edu&lang=cn&vadMdn=2
 * - signa = base64(HmacSHA1(key=apiKey, data=MD5(appid+ts)))  ← 注意是 SHA1 不是 SHA256
 * - 握手后持续发 binary PCM16 16k mono 帧，每 40ms 1280 字节
 * - 结束发 binary {"end": true}
 * - 服务端 text JSON: action=started/result/error；结果在 data.cn.st.rt[].ws[].cw[].w，type=0 为最终句
 *
 * 连续静音达到 [silenceTimeoutMs] 后主动正常关闭连接，避免空耗实时额度。
 */
/**
 * 可注入的 WebSocket 创建工厂。
 * 默认委托 [client.newWebSocket]；测试可注入 fake，完全不触网。
 */
fun interface WebSocketFactory {
    fun create(client: OkHttpClient, request: Request, listener: WebSocketListener): WebSocket
}

open class XunfeiRtasrEngine(
    private val appId: String,
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val webSocketFactory: WebSocketFactory = WebSocketFactory { c, r, l -> c.newWebSocket(r, l) },
    private val responseTimeoutMs: Long = 8_000L,
    private val silenceTimeoutMs: Long = 8_000L,
    private val silenceAmplitudeThreshold: Int = DEFAULT_SILENCE_AMPLITUDE_THRESHOLD,
) : SpeechEngine {

    init {
        require(responseTimeoutMs > 0L) { "responseTimeoutMs must be positive" }
        require(silenceTimeoutMs > 0L) { "silenceTimeoutMs must be positive" }
        require(silenceAmplitudeThreshold >= 0) { "silenceAmplitudeThreshold must be non-negative" }
    }

    private val silenceSampleLimit =
        (XUNFEI_SAMPLE_RATE_HZ * silenceTimeoutMs / 1_000L).coerceAtLeast(1L)

    override val name = "XunfeiRtasr"

    private sealed class WsResult {
        data class Text(val s: String) : WsResult()
        data class Err(val error: AsrError) : WsResult()
    }

    /** 签名：base64(HmacSHA1(key=apiKey, data=MD5(appid+ts))) */
    internal fun signa(ts: String): String {
        val md5Hex = MessageDigest.getInstance("MD5")
            .digest((appId + ts).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(apiKey.toByteArray(), "HmacSHA1"))
        return Base64.getEncoder().encodeToString(mac.doFinal(md5Hex.toByteArray()))
    }

    internal fun buildUrl(): String {
        val ts = (System.currentTimeMillis() / 1000).toString()
        return "wss://rtasr.xfyun.cn/v1/ws?appid=$appId&ts=$ts&signa=${signa(ts)}&pd=edu&lang=cn&vadMdn=2"
    }

    /** 解析服务端 result 帧；返回 (是否最终句, 拼接文本) */
    internal fun parseResult(text: String): Pair<Boolean, String> {
        val obj = JSONObject(text)
        if (obj.optString("action") != "result") return false to ""
        val data = JSONObject(obj.optString("data"))
        val rt = data.optJSONObject("cn")?.optJSONObject("st")?.optJSONArray("rt") ?: return false to ""
        val sb = StringBuilder()
        var isFinal = false
        for (i in 0 until rt.length()) {
            val seg = rt.optJSONObject(i) ?: continue
            if (seg.optString("type") == "0") isFinal = true
            val wsArr = seg.optJSONArray("ws") ?: continue
            for (j in 0 until wsArr.length()) {
                val cwArr = wsArr.optJSONObject(j)?.optJSONArray("cw") ?: continue
                for (k in 0 until cwArr.length()) {
                    sb.append(cwArr.optJSONObject(k)?.optString("w") ?: "")
                }
            }
        }
        return isFinal to sb.toString()
    }

    /**
     * 解析服务端 error 帧 → 类型化 [AsrError]。
     * action/code/desc 仅用于分类；返回的 message 是固定安全描述，
     * 绝不包含 code/desc/URL/原始 body，避免向 UI/日志泄漏凭证或课堂文本。
     * malformed / 非 error JSON 一律返回不可重试的 UNKNOWN，不抛给调用者。
     */
    internal fun parseError(text: String): AsrError {
        val obj = try {
            JSONObject(text)
        } catch (e: Exception) {
            return AsrError(AsrError.Kind.UNKNOWN, retriable = false, message = "Xunfei provider error")
        }
        if (obj.optString("action") != "error") {
            return AsrError(AsrError.Kind.UNKNOWN, retriable = false, message = "Xunfei provider error")
        }
        val code = obj.optString("code")
        val desc = obj.optString("desc").lowercase()
        val isAuth = code == "10110" || desc.contains("auth") || desc.contains("authorization")
        return if (isAuth) {
            AsrError(AsrError.Kind.AUTH, retriable = false, message = "Xunfei authentication failed")
        } else {
            AsrError(AsrError.Kind.UNKNOWN, retriable = false, message = "Xunfei provider error")
        }
    }

    /**
     * 类型化事件流（新主路径）：最终句发 [SpeechEvent.Text]，action=error 发
     * [SpeechEvent.Failed]（携带 [parseError] 的安全错误），websocket [WebSocketListener.onFailure]
     * 发 [SpeechEvent.Failed]（NETWORK）后流正常完成。
     *
     * fatal 事件后 receiver/sender 都立即结束：关闭事件通道、取消发送协程，
     * 不再等待 [kotlinx.coroutines.delay] 或 onClosed——绝不能在 fatal 路径挂住。
     * 初始响应 watchdog：握手后 [responseTimeoutMs] 内服务端无任何消息（连 started 都没有）
     * 时发 [SpeechEvent.Failed]（NETWORK "Xunfei response timeout"）并 [WebSocket.cancel]，
     * 防 receiver 永久挂起；首个服务端消息到达即视为已响应，不触发。服务端已响应后，
     * 连续 [silenceTimeoutMs] 的静音 PCM 会主动 close，流正常结束且不发送 end 帧。
     */
    open fun transcribeEvents(pcm: Flow<ShortArray>): Flow<SpeechEvent> = channelFlow {
        val results = Channel<WsResult>(Channel.UNLIMITED)
        // 首个服务端消息（任意 action，含 started）到达即关闭 → watchdog 视为已响应
        val serverSpoke = CompletableDeferred<Unit>()
        // 事件出口统一走 ProducerScope：watchdog 直接发 Failed 前，
        // 先取消 recvJob/sendJob 两个子协程，保证流真正完成且无 job 泄露。
        val eventSink = this
        // fatal 标记：Err 分支置位，发送协程据此跳过 end/close（跨协程可见）
        val fatal = java.util.concurrent.atomic.AtomicBoolean(false)
        val listener = object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                // 服务端消息=已响应；watchdog 看到 serverSpoke 完成就不再介入。
                serverSpoke.complete(Unit)
                val obj = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    SafeLog.w(
                        "xunfei_frame_invalid",
                        mapOf("module" to "XunfeiRtasrEngine", "errorCode" to "INVALID_JSON"),
                    )
                    results.trySend(WsResult.Err(AsrError(AsrError.Kind.UNKNOWN, retriable = false, message = "Xunfei provider error")))
                    return
                }
                when (obj.optString("action")) {
                    "result" -> {
                        val (isFinal, sentence) = parseResult(text)
                        if (isFinal && sentence.isNotBlank()) {
                            results.trySend(WsResult.Text(sentence))
                        }
                    }
                    "error" -> {
                        val error = parseError(text)
                        SafeLog.w(
                            "xunfei_error",
                            mapOf("module" to "XunfeiRtasrEngine", "errorCode" to error.kind.name),
                        )
                        results.trySend(WsResult.Err(error))
                    }
                }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                serverSpoke.complete(Unit)
                SafeLog.w(
                    "xunfei_socket_failed",
                    mapOf("module" to "XunfeiRtasrEngine", "errorCode" to "NETWORK"),
                )
                results.trySend(WsResult.Err(AsrError.network("Xunfei websocket failure")))
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                serverSpoke.complete(Unit)
                SafeLog.d(
                    "xunfei_closed",
                    mapOf("module" to "XunfeiRtasrEngine", "status" to "CLOSED"),
                )
                results.close()
            }
        }

        val request = Request.Builder().url(buildUrl()).build()
        val ws = webSocketFactory.create(client, request, listener)

        // 接收协程：服务端事件 → 下游 typed 事件；Err（fatal）→ 发 Failed 后立即结束
        val recvJob: Job = launch {
            for (r in results) {
                when (r) {
                    is WsResult.Text -> send(SpeechEvent.Text(segmentId = "xunfei-stream", text = r.s))
                    is WsResult.Err -> {
                        fatal.set(true)
                        send(SpeechEvent.Failed(segmentId = null, error = r.error))
                        results.close()
                        break // receiver 立即结束，不等 onClosed
                    }
                }
            }
        }

        // 发送协程：PCM → 1280 字节/帧，每帧间隔 40ms；fatal 后被取消，不再发 end/close
        val sendJob: Job = launch {
            val pending = ArrayDeque<Byte>()
            var silentSamples = 0L
            try {
                pcm.collect { chunk ->
                    if (chunk.isNotEmpty()) {
                        val trailing = trailingSilentSamples(chunk, silenceAmplitudeThreshold)
                        silentSamples = if (trailing == chunk.size.toLong()) {
                            (silentSamples + trailing).coerceAtMost(silenceSampleLimit)
                        } else {
                            trailing
                        }
                        if (serverSpoke.isCompleted && silentSamples >= silenceSampleLimit) {
                            throw SilenceStop
                        }
                    }
                    for (s in chunk) {
                        pending.addLast((s.toInt() and 0xFF).toByte())
                        pending.addLast(((s.toInt() shr 8) and 0xFF).toByte())
                    }
                    while (pending.size >= 1280) {
                        val frame = ByteArray(1280)
                        repeat(1280) { frame[it] = pending.removeFirst() }
                        ws.send(ByteString.of(*frame))
                        kotlinx.coroutines.delay(40)
                    }
                }
            } catch (_: SilenceStop) {
                fatal.set(true)
                recvJob.cancel()
                ws.close(1000, "silence")
                SafeLog.w(
                    "xunfei_silence_timeout",
                    mapOf(
                        "module" to "XunfeiRtasrEngine",
                        "errorCode" to "SILENCE_TIMEOUT",
                        "elapsedMs" to silenceTimeoutMs,
                    ),
                )
            } finally {
                // 仅正常 PCM 结束（协程未被取消）才发 {"end":true} 并 close；
                // 取消路径（fatal 或 collector 取消）一律跳过 end/delay(200)/close：
                // 取消后 isActive=false，不得再向已失效连接发送或等待。
                if (!fatal.get() && currentCoroutineContext().isActive) {
                    ws.send("""{"end": true}""".encodeUtf8())
                    kotlinx.coroutines.delay(200)
                    ws.close(1000, "done")
                }
            }
        }

        // 等 receiver 结束（fatal break 或 onClosed 关通道）。
        // fatal：立即取消发送协程，绝不等它的 delay(200)；
        // 正常：让发送协程完成 finally（发 end → delay(200) → close）后再收尾。
        // watchdog：timeout 内服务端无任何消息（serverSpoke 未完成）→ 发 Failed(NETWORK)、
        // ws.cancel()，随后取消仍挂在 pcm.collect 上的发送协程（fatal 已置位，
        // 其 finally 不会发 end/close），保证 timeout 后所有 sender/receiver job 都结束、无泄露。
        try {
            // watchdog 必须先于 sendJob.join()：PCM 流可能永久不结束（sendJob 一直挂在
            // pcm.collect 上），先 join 会让 watchdog 永不启动、receiver 永久挂起。
            // 数据帧仍由 sendJob 并行照常发送，不依赖这里先 join。
            val spoke = withTimeoutOrNull(responseTimeoutMs) {
                serverSpoke.await()
                true
            } ?: false
            if (!spoke) {
                fatal.set(true)
                // 发 Failed 前先取消挂在空通道/挂起 PCM 上的子协程（recvJob 挂在
                // results 空通道、sendJob 挂在 pcm.collect）——取消后 join 立即完成，
                // 再发 Failed、cancel 连接、真正完成流，不会有任何子 job 挡在收尾前。
                recvJob.cancelAndJoin()
                sendJob.cancelAndJoin()
                ws.cancel()
                SafeLog.w(
                    "xunfei_response_timeout",
                    mapOf(
                        "module" to "XunfeiRtasrEngine",
                        "errorCode" to "TIMEOUT",
                        "elapsedMs" to responseTimeoutMs,
                    ),
                )
                eventSink.send(SpeechEvent.Failed(segmentId = null, error = AsrError.network("Xunfei response timeout")))
                return@channelFlow
            }
            // serverSpoke 已响应：走原有接收路径，等 receiver 结束（fatal break 或 onClosed 关通道）。
            recvJob.join()
            if (fatal.get()) {
                // fatal：立即取消发送协程，绝不等它的 delay(200)/end/close
                sendJob.cancelAndJoin()
            } else {
                // 正常：让发送协程完成 finally（发 end → delay(200) → close）后再收尾
                sendJob.join()
            }
        } finally {
            // hard cancellation：collector 取消时（isActive=false）立即 ws.cancel() 快速收尾，
            // 不依赖正常 close()；fatal/正常路径 try 正常完成（isActive=true），不 cancel。
            if (!isActive) {
                ws.cancel()
            }
        }
    }

    override fun transcribe(pcm: Flow<ShortArray>): Flow<String> =
        transcribeEvents(pcm).mapNotNull { event ->
            when (event) {
                is SpeechEvent.Text -> event.text
                is SpeechEvent.Failed -> throw AsrException(event.error)
                // EngineChanged/Recovering 不是本引擎的转写结果：过滤掉，不产生空字符串
                is SpeechEvent.EngineChanged, is SpeechEvent.Recovering -> null
            }
        }
}

private fun trailingSilentSamples(samples: ShortArray, amplitudeThreshold: Int): Long {
    var trailing = 0L
    for (sample in samples) {
        val value = sample.toInt()
        if (value > amplitudeThreshold || value < -amplitudeThreshold) {
            trailing = 0L
        } else {
            trailing++
        }
    }
    return trailing
}

private object SilenceStop : Exception()
