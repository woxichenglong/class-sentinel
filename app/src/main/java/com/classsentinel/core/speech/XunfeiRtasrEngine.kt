package com.classsentinel.core.speech

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
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

/**
 * 讯飞实时语音转写（增强引擎，低延迟流式，免费额度手动开启）。
 * 协议要点（官方文档 2026-08 实抓）：
 * - wss://rtasr.xfyun.cn/v1/ws?appid=&ts=&signa=&pd=edu&lang=cn&vadMdn=2
 * - signa = base64(HmacSHA1(key=apiKey, data=MD5(appid+ts)))  ← 注意是 SHA1 不是 SHA256
 * - 握手后持续发 binary PCM16 16k mono 帧，每 40ms 1280 字节
 * - 结束发 binary {"end": true}
 * - 服务端 text JSON: action=started/result/error；结果在 data.cn.st.rt[].ws[].cw[].w，type=0 为最终句
 *
 * TODO: 静音 >8s 主动断连省额度（当前版本静音数据照发，连接不断）
 */
class XunfeiRtasrEngine(
    private val appId: String,
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
) : SpeechEngine {

    override val name = "XunfeiRtasr"

    private sealed class WsResult {
        data class Text(val s: String) : WsResult()
        data class Err(val msg: String) : WsResult()
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

    override fun transcribe(pcm: Flow<ShortArray>): Flow<String> = channelFlow {
        val results = Channel<WsResult>(Channel.UNLIMITED)
        val listener = object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                val obj = JSONObject(text)
                when (obj.optString("action")) {
                    "result" -> {
                        val (isFinal, sentence) = parseResult(text)
                        if (isFinal && sentence.isNotBlank()) {
                            results.trySend(WsResult.Text(sentence))
                        }
                    }
                    "error" -> results.trySend(
                        WsResult.Err("${obj.optString("code")} ${obj.optString("desc")}"),
                    )
                }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                results.trySend(WsResult.Err(t.message ?: "ws failure"))
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                results.close()
            }
        }

        val request = Request.Builder().url(buildUrl()).build()
        val ws = client.newWebSocket(request, listener)

        // 接收协程：服务端句流 → 下游
        val recvJob = launch {
            for (r in results) {
                when (r) {
                    is WsResult.Text -> send(r.s)
                    is WsResult.Err -> println("[ASR:$name] $r.msg")
                }
            }
        }

        // 发送循环：PCM → 1280 字节/帧，每帧间隔 40ms
        val pending = ArrayDeque<Byte>()
        try {
            pcm.collect { chunk ->
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
        } finally {
            ws.send("""{"end": true}""".encodeUtf8())
            kotlinx.coroutines.delay(200)
            ws.close(1000, "done")
        }
        recvJob.join()
    }
}
