package com.classsentinel.core.llm

import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LlmTransportProbeTest {

    private lateinit var server: MockWebServer
    private val requests = mutableListOf<RecordedRequest>()
    private val logs = mutableListOf<Pair<String, Map<String, Any?>>>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                return when {
                    request.path == "/v1/models" && request.getHeader("Authorization") == null ->
                        MockResponse().setResponseCode(401).setBody("provider body must not escape")
                    request.path == "/v1/models" ->
                        MockResponse().setResponseCode(200).setBody("models body must not escape")
                    request.path == "/v1/chat/completions" ->
                        MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "text/event-stream")
                            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}\n\ndata: [DONE]\n\n")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `probe runs five safe stages for HTTP1 and default protocols`() {
        val key = "probe-secret-key"
        val report = LlmTransportProbe(
            log = { event, fields -> logs += event to fields },
        ).run(
            config = LlmConfig(
                baseUrl = server.url("/v1").toString(),
                apiKey = key,
                model = "deepseek/deepseek-v4-flash",
            ),
            maxTokens = 256,
            protocols = listOf(ProbeProtocol.HTTP_1_1, ProbeProtocol.DEFAULT),
        )

        assertEquals(10, report.size)
        assertEquals(10, requests.size)
        assertEquals(
            listOf(ProbeProtocol.HTTP_1_1, ProbeProtocol.DEFAULT).flatMap {
                listOf(
                    ProbeRequest.MODELS_NO_AUTH,
                    ProbeRequest.MODELS_AUTH,
                    ProbeRequest.MINIMAL_NON_STREAM,
                    ProbeRequest.MINIMAL_STREAM,
                    ProbeRequest.APP_EQUIVALENT,
                )
            },
            report.map { it.request },
        )
        assertEquals(listOf(401, 200, 200, 200, 200, 401, 200, 200, 200, 200), report.map { it.httpCode })
        assertTrue(report.filter { it.request == ProbeRequest.MINIMAL_STREAM || it.request == ProbeRequest.APP_EQUIVALENT }
            .all { it.sseStarted })
        assertTrue(report.all { it.transportError == null })
        assertTrue(report.all { it.elapsedMs >= 0L })
        assertTrue(report.all { it.negotiatedProtocol != null })

        val noAuthRequest = requests[0]
        assertEquals(null, noAuthRequest.getHeader("Authorization"))
        assertEquals("Bearer $key", requests[1].getHeader("Authorization"))
        assertEquals("Bearer $key", requests[6].getHeader("Authorization"))

        val nonStream = JSONObject(requests[2].body.readUtf8())
        assertEquals(false, nonStream.getBoolean("stream"))
        assertEquals("Reply OK", nonStream.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertFalse(nonStream.has("thinking"))

        val appEquivalent = JSONObject(requests[4].body.readUtf8())
        assertEquals(true, appEquivalent.getBoolean("stream"))
        assertEquals("disabled", appEquivalent.getJSONObject("thinking").getString("type"))
        assertEquals(256, appEquivalent.getInt("max_tokens"))

        val allLogText = logs.joinToString(" ")
        assertTrue(logs.any { it.first == "llm_probe_start" })
        assertTrue(logs.any { it.first == "llm_probe_dns_ok" })
        assertTrue(logs.any { it.first == "llm_probe_response" })
        assertFalse(allLogText.contains(key))
        assertFalse(allLogText.contains("Reply OK"))
        assertFalse(allLogText.contains("provider body"))
        assertFalse(allLogText.contains("chat/completions"))
        assertTrue(logs.all { (_, fields) ->
            fields.keys.all { it in setOf("module", "engine", "status", "errorCode", "elapsedMs", "httpCode") }
        })
    }

    @Test
    fun `transport exceptions map to safe categories without reading messages`() {
        assertEquals(ProbeErrorCode.DNS, classifyProbeFailure(UnknownHostExceptionForTest()))
        assertEquals(ProbeErrorCode.CONNECT, classifyProbeFailure(ConnectException("secret host")))
        assertEquals(ProbeErrorCode.TLS, classifyProbeFailure(object : SSLException("certificate body") {}))
        assertEquals(ProbeErrorCode.TIMEOUT, classifyProbeFailure(SocketTimeoutException("url")))
        assertEquals(ProbeErrorCode.CONNECTION_RESET, classifyProbeFailure(SocketException("api key")))
        assertEquals(ProbeErrorCode.CONNECTION_RESET, classifyProbeFailure(EOFException("classroom text")))
        assertEquals(ProbeErrorCode.UNKNOWN, classifyProbeFailure(IllegalStateException("provider body")))
    }

    @Test
    fun `probe can select one protocol and one stage for a short device check`() {
        val report = LlmTransportProbe(
            log = { event, fields -> logs += event to fields },
        ).run(
            config = LlmConfig(server.url("/v1").toString(), "probe-key", "model"),
            maxTokens = 256,
            protocols = listOf(ProbeProtocol.DEFAULT),
            requests = listOf(ProbeRequest.MODELS_NO_AUTH),
        )

        assertEquals(1, report.size)
        assertEquals(ProbeProtocol.DEFAULT, report.single().protocol)
        assertEquals(ProbeRequest.MODELS_NO_AUTH, report.single().request)
        assertEquals(401, report.single().httpCode)
        assertTrue(logs.none { it.first == "llm_probe_start" && it.second["status"] == "APP_EQUIVALENT" })
    }

    private class UnknownHostExceptionForTest : java.net.UnknownHostException("private host")
}
