package com.classsentinel.core.llm

import com.classsentinel.core.log.SafeLog
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URI
import javax.net.ssl.SSLException
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only protocol comparison requested by the transport investigation. */
internal enum class ProbeProtocol(
    val label: String,
    val okhttpProtocols: List<Protocol>?,
) {
    HTTP_1_1("HTTP_1_1", listOf(Protocol.HTTP_1_1)),
    DEFAULT("DEFAULT", null),
}

/** Fixed request stages; no request or response content is part of the report. */
internal enum class ProbeRequest(
    val label: String,
    val requiresKey: Boolean,
    val isStreaming: Boolean,
) {
    MODELS_NO_AUTH("MODELS_NO_AUTH", false, false),
    MODELS_AUTH("MODELS_AUTH", true, false),
    MINIMAL_NON_STREAM("MINIMAL_NON_STREAM", true, false),
    MINIMAL_STREAM("MINIMAL_STREAM", true, true),
    APP_EQUIVALENT("APP_EQUIVALENT", true, true),
}

/** Safe transport failure categories; this enum deliberately has no raw exception field. */
internal enum class ProbeErrorCode {
    DNS,
    CONNECT,
    TLS,
    TIMEOUT,
    CONNECTION_RESET,
    HTTP,
    UNKNOWN,
}

/** Privacy-safe result: status/protocol/timing only, never body, URL, headers, or exceptions. */
internal data class ProbeResult(
    val protocol: ProbeProtocol,
    val request: ProbeRequest,
    val httpCode: Int?,
    val negotiatedProtocol: String?,
    val elapsedMs: Long,
    val transportError: ProbeErrorCode?,
    val sseStarted: Boolean,
)

/**
 * Debug-only transport probe. It uses the same OkHttp builder as [LlmClient] and only adds
 * EventListener instrumentation. The probe sends fixed minimal payloads and never logs them.
 */
internal class LlmTransportProbe(
    private val log: (String, Map<String, Any?>) -> Unit = { event, fields ->
        SafeLog.d(event, fields)
    },
    private val nowNanos: () -> Long = System::nanoTime,
) {

    fun run(
        config: LlmConfig,
        maxTokens: Int,
        protocols: List<ProbeProtocol> = ProbeProtocol.values().toList(),
        requests: List<ProbeRequest> = ProbeRequest.values().toList(),
    ): List<ProbeResult> {
        logJavaDns(config)
        return protocols.flatMap { protocol ->
        val client = newLlmTransportClient(
            protocols = protocol.okhttpProtocols,
            eventListenerFactory = EventListener.Factory { call ->
                val request = call.request().tag(ProbeRequest::class.java)
                    ?: ProbeRequest.MODELS_NO_AUTH
                ProbeEventListener(
                    protocol = protocol,
                    request = request,
                    log = log,
                    nowNanos = nowNanos,
                )
            },
        )
        try {
            requests.map { request ->
                runRequest(client, protocol, request, config, maxTokens)
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
        }
    }

    private fun logJavaDns(config: LlmConfig) {
        val startedAt = nowNanos()
        val fields = mapOf(
            "module" to "LlmTransportProbe",
            "engine" to "JAVA",
            "status" to "DNS_SANITY",
        )
        try {
            val host = URI(config.baseUrl).host
            require(!host.isNullOrBlank())
            java.net.InetAddress.getAllByName(host)
            log("llm_probe_java_dns_ok", fields + ("elapsedMs" to elapsedMs(startedAt)))
        } catch (e: Exception) {
            val error = classifyProbeFailure(e)
            log(
                "llm_probe_failure",
                fields + ("errorCode" to error.name) + ("elapsedMs" to elapsedMs(startedAt)),
            )
        }
    }

    private fun runRequest(
        client: okhttp3.OkHttpClient,
        protocol: ProbeProtocol,
        requestKind: ProbeRequest,
        config: LlmConfig,
        maxTokens: Int,
    ): ProbeResult {
        val startedAt = nowNanos()
        val fields = fields(protocol, requestKind)
        log("llm_probe_start", fields)

        if (requestKind.requiresKey && config.apiKey.isBlank()) {
            val elapsedMs = elapsedMs(startedAt)
            log(
                "llm_probe_failure",
                fields + ("errorCode" to ProbeErrorCode.UNKNOWN.name) + ("elapsedMs" to elapsedMs),
            )
            return ProbeResult(
                protocol = protocol,
                request = requestKind,
                httpCode = null,
                negotiatedProtocol = null,
                elapsedMs = elapsedMs,
                transportError = ProbeErrorCode.UNKNOWN,
                sseStarted = false,
            )
        }

        return try {
            val request = buildRequest(config, requestKind, maxTokens)
            client.newCall(request).execute().use { response ->
                val sseStarted = if (requestKind.isStreaming && response.isSuccessful) {
                    response.body?.hasSseDataLine() == true
                } else {
                    false
                }
                val error = when {
                    !response.isSuccessful && requestKind != ProbeRequest.MODELS_NO_AUTH ->
                        ProbeErrorCode.HTTP
                    requestKind.isStreaming && response.isSuccessful && !sseStarted ->
                        ProbeErrorCode.UNKNOWN
                    else -> null
                }
                if (requestKind.isStreaming && response.isSuccessful && sseStarted) {
                    log("llm_probe_sse_ok", fields + ("elapsedMs" to elapsedMs(startedAt)))
                }
                if (error != null) {
                    log(
                        "llm_probe_failure",
                        fields + ("errorCode" to error.name) + ("elapsedMs" to elapsedMs(startedAt)),
                    )
                }
                ProbeResult(
                    protocol = protocol,
                    request = requestKind,
                    httpCode = response.code,
                    negotiatedProtocol = response.protocol.safeName(),
                    elapsedMs = elapsedMs(startedAt),
                    transportError = error,
                    sseStarted = sseStarted,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = classifyProbeFailure(e)
            val elapsedMs = elapsedMs(startedAt)
            log(
                "llm_probe_failure",
                fields + ("errorCode" to error.name) + ("elapsedMs" to elapsedMs),
            )
            ProbeResult(
                protocol = protocol,
                request = requestKind,
                httpCode = null,
                negotiatedProtocol = null,
                elapsedMs = elapsedMs,
                transportError = error,
                sseStarted = false,
            )
        }
    }

    private fun buildRequest(
        config: LlmConfig,
        requestKind: ProbeRequest,
        maxTokens: Int,
    ): Request {
        val baseUrl = config.baseUrl.trimEnd('/')
        val builder = Request.Builder()
            .tag(ProbeRequest::class.java, requestKind)
        if (requestKind == ProbeRequest.MODELS_NO_AUTH || requestKind == ProbeRequest.MODELS_AUTH) {
            builder.url("$baseUrl/models").get()
        } else {
            val stream = requestKind.isStreaming
            val payload = JSONObject()
                .put("model", config.model)
                .put("messages", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", "Reply OK"),
                ))
                .put("stream", stream)
            if (requestKind == ProbeRequest.APP_EQUIVALENT && config.thinkingDisabled) {
                payload.put("thinking", JSONObject().put("type", "disabled"))
            }
            if (requestKind == ProbeRequest.APP_EQUIVALENT) {
                payload.put("max_tokens", maxTokens)
            }
            builder
                .url("$baseUrl/chat/completions")
                .addHeader("Accept", if (stream) "text/event-stream" else "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
        }
        if (requestKind.requiresKey) {
            builder.addHeader("Authorization", "Bearer ${config.apiKey}")
        }
        return builder.build()
    }

    private fun fields(protocol: ProbeProtocol, request: ProbeRequest): Map<String, Any?> = mapOf(
        "module" to "LlmTransportProbe",
        "engine" to protocol.label,
        "status" to request.label,
    )

    private fun elapsedMs(startedAt: Long): Long =
        ((nowNanos() - startedAt) / 1_000_000L).coerceAtLeast(0L)
}

private class ProbeEventListener(
    private val protocol: ProbeProtocol,
    private val request: ProbeRequest,
    private val log: (String, Map<String, Any?>) -> Unit,
    private val nowNanos: () -> Long,
) : EventListener() {

    private var callStartedAt = 0L
    override fun callStart(call: Call) {
        callStartedAt = nowNanos()
    }

    override fun dnsEnd(
        call: Call,
        domainName: String,
        inetAddressList: List<java.net.InetAddress>,
    ) {
        log("llm_probe_dns_ok", fields(elapsedMs()))
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: java.net.InetSocketAddress,
        proxy: java.net.Proxy,
        protocol: Protocol?,
    ) {
        log("llm_probe_connect_ok", fields(elapsedMs()))
    }

    override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) {
        log("llm_probe_tls_ok", fields(elapsedMs()))
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        log(
            "llm_probe_response",
            fields(elapsedMs(), response.protocol.safeName()) + ("httpCode" to response.code),
        )
    }


    private fun fields(elapsedMs: Long, negotiated: String? = null): Map<String, Any?> = mapOf(
        "module" to "LlmTransportProbe",
        "engine" to if (negotiated != null) "${protocol.label}_$negotiated" else protocol.label,
        "status" to request.label,
        "elapsedMs" to elapsedMs,
    )

    private fun elapsedMs(): Long =
        ((nowNanos() - callStartedAt) / 1_000_000L).coerceAtLeast(0L)
}

private fun ResponseBody.hasSseDataLine(): Boolean {
    val source = source()
    repeat(16) {
        val line = source.readUtf8Line() ?: return false
        if (line.trim().startsWith("data:")) return true
    }
    return false
}

internal fun classifyProbeFailure(throwable: Throwable): ProbeErrorCode = when {
    throwable is UnknownHostException -> ProbeErrorCode.DNS
    throwable is SSLException -> ProbeErrorCode.TLS
    throwable is SocketTimeoutException || throwable is InterruptedIOException -> ProbeErrorCode.TIMEOUT
    throwable is ConnectException || throwable is NoRouteToHostException -> ProbeErrorCode.CONNECT
    throwable is EOFException || throwable is SocketException -> ProbeErrorCode.CONNECTION_RESET
    throwable is IOException -> ProbeErrorCode.UNKNOWN
    else -> ProbeErrorCode.UNKNOWN
}

private fun Protocol.safeName(): String = when (this) {
    Protocol.HTTP_1_1 -> "HTTP_1_1"
    Protocol.HTTP_2 -> "HTTP_2"
    Protocol.QUIC -> "QUIC"
    else -> "OTHER"
}
