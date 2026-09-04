package com.classsentinel.core.speech

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.flow.Flow

class XunfeiRtasrEngineTest {

    // 官方文档签名示例：appid=595f23df ts=1512041814 apiKey=d9f4aa7ea6d94faca62cd88a28fd5234
    // MD5(appid+ts)=0829d4012497c14a30e7e72aeebe565e → signa=IrrzsJeOFk1NGfJHW6SkHUoN9CU=
    @Test
    fun `signa matches official example`() {
        val engine = XunfeiRtasrEngine(
            appId = "595f23df",
            apiKey = "d9f4aa7ea6d94faca62cd88a28fd5234",
        )
        assertEquals("IrrzsJeOFk1NGfJHW6SkHUoN9CU=", engine.signa("1512041814"))
    }

    @Test
    fun `buildUrl contains required params`() {
        val url = XunfeiRtasrEngine("myapp", "mykey").buildUrl()
        assertTrue(url.startsWith("wss://rtasr.xfyun.cn/v1/ws?"))
        assertTrue(url.contains("appid=myapp"))
        assertTrue(url.contains("pd=edu"))
        assertTrue(url.contains("lang=cn"))
        assertTrue(url.contains("signa="))
        assertTrue(url.contains("ts="))
    }

    // 注意：data 值是转义 JSON 字符串，必须单行——多行裸换行是非法 JSON
    @Test
    fun `parseResult extracts final sentence`() {
        val json = """{"action":"result","code":"0","data":"{\"cn\":{\"st\":{\"bg\":\"820\",\"ed\":\"0\",\"rt\":[{\"ws\":[{\"cw\":[{\"w\":\"张伟\",\"wp\":\"n\"}]},{\"cw\":[{\"w\":\"，\",\"wp\":\"p\"}]},{\"cw\":[{\"w\":\"你来\",\"wp\":\"n\"}]},{\"cw\":[{\"w\":\"回答\",\"wp\":\"n\"}]}],\"type\":\"0\"}]}},\"seg_id\":5}","desc":"success"}"""
        val (isFinal, text) = XunfeiRtasrEngine("a", "k").parseResult(json)
        assertTrue(isFinal)
        assertEquals("张伟，你来回答", text)
    }

    @Test
    fun `parseResult intermediate not final`() {
        val json = """{"action":"result","code":"0","data":"{\"cn\":{\"st\":{\"bg\":\"0\",\"ed\":\"0\",\"rt\":[{\"ws\":[{\"cw\":[{\"w\":\"傅里\",\"wp\":\"n\"}]}],\"type\":\"1\"}]}},\"seg_id\":0}","desc":"success"}"""
        val (isFinal, _) = XunfeiRtasrEngine("a", "k").parseResult(json)
        assertFalse(isFinal)
    }

    @Test
    fun `parseResult handles error action`() {
        val json = """{"action":"error","code":"10110","desc":"invalid authorization"}"""
        val (isFinal, text) = XunfeiRtasrEngine("a", "k").parseResult(json)
        assertFalse(isFinal)
        assertEquals("", text)
    }

    // M1e-1b: action=error 的安全 AsrError 映射 —— 只分类，message 永不泄漏 code/desc/body
    @Test
    fun `parseError maps code 10110 to AUTH non-retriable`() {
        val json = """{"action":"error","code":"10110","desc":"invalid authorization"}"""
        val error = XunfeiRtasrEngine("a", "k").parseError(json)
        assertEquals(AsrError.Kind.AUTH, error.kind)
        assertFalse(error.retriable)
        assertEquals("Xunfei authentication failed", error.message)
    }

    @Test
    fun `parseError maps desc containing auth to AUTH non-retriable`() {
        val json = """{"action":"error","code":"10105","desc":"unauthorized user"}"""
        val error = XunfeiRtasrEngine("a", "k").parseError(json)
        assertEquals(AsrError.Kind.AUTH, error.kind)
        assertFalse(error.retriable)
        assertEquals("Xunfei authentication failed", error.message)
    }

    @Test
    fun `parseError maps generic error to UNKNOWN non-retriable`() {
        val json = """{"action":"error","code":"12345","desc":"some provider failure"}"""
        val error = XunfeiRtasrEngine("a", "k").parseError(json)
        assertEquals(AsrError.Kind.UNKNOWN, error.kind)
        assertFalse(error.retriable)
        assertEquals("Xunfei provider error", error.message)
    }

    @Test
    fun `parseError maps malformed json to UNKNOWN safely`() {
        val error = XunfeiRtasrEngine("a", "k").parseError("not json at all")
        assertEquals(AsrError.Kind.UNKNOWN, error.kind)
        assertFalse(error.retriable)
        assertEquals("Xunfei provider error", error.message)
    }

    @Test
    fun `parseError maps non-error action json to UNKNOWN safely`() {
        val json = """{"action":"started","code":"0","desc":"success"}"""
        val error = XunfeiRtasrEngine("a", "k").parseError(json)
        assertEquals(AsrError.Kind.UNKNOWN, error.kind)
        assertFalse(error.retriable)
        assertEquals("Xunfei provider error", error.message)
    }

    @Test
    fun `parseError message never leaks code desc or body`() {
        val json = """{"action":"error","code":"10110","desc":"invalid authorization"}"""
        val error = XunfeiRtasrEngine("a", "k").parseError(json)
        assertFalse(error.message.contains("10110"))
        assertFalse(error.message.contains("invalid"))
        assertFalse(error.message.contains("authorization"))
        assertFalse(error.message.contains("{"))
    }

    // M1e-1a: 连接创建必须经过注入的 WebSocketFactory —— fake 工厂完全不触网
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `transcribe creates websocket via injected factory`() = runTest {
        var factoryCalls = 0
        lateinit var capturedRequest: Request
        lateinit var capturedListener: WebSocketListener
        val fakeWs = object : WebSocket {
            override fun request(): Request = throw UnsupportedOperationException("not needed")
            override fun queueSize(): Long = 0
            override fun send(text: String): Boolean = true
            override fun send(bytes: ByteString): Boolean = true
            override fun close(code: Int, reason: String?): Boolean = true
            override fun cancel() {}
        }
        val factory = WebSocketFactory { client, request, listener ->
            factoryCalls++
            capturedRequest = request
            capturedListener = listener
            fakeWs
        }
        val engine = XunfeiRtasrEngine(
            appId = "myapp",
            apiKey = "mykey",
            webSocketFactory = factory,
        )
        val received = mutableListOf<String>()
        launch { engine.transcribe(flowOf(shortArrayOf(1, 2))).collect { received += it } }
        runCurrent()
        // 工厂已被调用且拿到真实请求（wss URL 由 buildUrl 生成）
        assertEquals(1, factoryCalls)
        assertTrue(capturedRequest.url.toString().startsWith("https://rtasr.xfyun.cn/v1/ws?"))
        assertEquals("myapp", capturedRequest.url.queryParameter("appid"))
        assertEquals("edu", capturedRequest.url.queryParameter("pd"))
        assertEquals("cn", capturedRequest.url.queryParameter("lang"))
        assertTrue(capturedRequest.url.isHttps)
        // 服务端在 watchdog 阈值内必须来消息：先发 started 让 watchdog 视为已响应，
        // 再驱动 result → onClosed，全程无网络
        val startedJson = """{"action":"started","code":"0","desc":"success"}"""
        capturedListener.onMessage(fakeWs, startedJson)
        val resultJson = """{"action":"result","code":"0","data":"{\"cn\":{\"st\":{\"bg\":\"0\",\"ed\":\"0\",\"rt\":[{\"ws\":[{\"cw\":[{\"w\":\"你好\"}]}],\"type\":\"0\"}]}}}","desc":"success"}"""
        capturedListener.onMessage(fakeWs, resultJson)
        capturedListener.onClosed(fakeWs, 1000, "done")
        advanceUntilIdle()
        assertEquals(listOf("你好"), received)
    }

    // ---- M1e-2: typed event flow (transcribeEvents) + fatal completion ----
    // 最小 fake：记录所有发来的 binary 帧 + 收到的 close 调用，绝不触网
    private class FakeWs : WebSocket {
        val sentFrames = mutableListOf<String>()
        var closeCalls = 0
        var cancelCalls = 0
        override fun request(): Request = throw UnsupportedOperationException("not needed")
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean {
            sentFrames += text
            return true
        }
        override fun send(bytes: ByteString): Boolean {
            sentFrames += bytes.utf8()
            return true
        }
        override fun close(code: Int, reason: String?): Boolean {
            closeCalls++
            return true
        }
        override fun cancel() {
            cancelCalls++
        }
    }

    private class Captured(
        val ws: FakeWs = FakeWs(),
        var listener: WebSocketListener? = null,
    )

    private fun engineWith(captured: Captured): XunfeiRtasrEngine = engineWith(captured, 8_000L)

    private fun engineWith(
        captured: Captured,
        responseTimeoutMs: Long,
        silenceTimeoutMs: Long = 8_000L,
        silenceAmplitudeThreshold: Int = 500,
    ): XunfeiRtasrEngine {
        val factory = WebSocketFactory { _, _, listener ->
            captured.listener = listener
            captured.ws
        }
        return XunfeiRtasrEngine(
            appId = "myapp",
            apiKey = "mykey",
            webSocketFactory = factory,
            responseTimeoutMs = responseTimeoutMs,
            silenceTimeoutMs = silenceTimeoutMs,
            silenceAmplitudeThreshold = silenceAmplitudeThreshold,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `transcribeEvents emits Text for final result and completes`() = runTest {
        val captured = Captured()
        val engine = engineWith(captured)
        val collected = async { engine.transcribeEvents(flowOf(shortArrayOf(1, 2))).toList() }
        // 先让收集协程跑到 watchdog 挂起点，listener 已被工厂捕获
        // （用 runCurrent 而非 advanceUntilIdle：后者会推进虚拟时间耗掉默认 8s watchdog）
        runCurrent()
        val resultJson = """{"action":"result","code":"0","data":"{\"cn\":{\"st\":{\"bg\":\"0\",\"ed\":\"0\",\"rt\":[{\"ws\":[{\"cw\":[{\"w\":\"你好\"}]}],\"type\":\"0\"}]}}}","desc":"success"}"""
        captured.listener!!.onMessage(captured.ws, resultJson)
        captured.listener!!.onClosed(captured.ws, 1000, "done")
        advanceUntilIdle()
        assertEquals(
            listOf(SpeechEvent.Text(segmentId = "xunfei-stream", text = "你好")),
            collected.await(),
        )
        assertEquals(1, captured.ws.closeCalls)
        assertTrue(captured.ws.sentFrames.any { it.contains("\"end\": true") })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `transcribeEvents emits Failed with parseError and completes on action error`() = runTest {
        val captured = Captured()
        val engine = engineWith(captured)
        val collected = async { engine.transcribeEvents(flowOf(shortArrayOf(1, 2))).toList() }
        runCurrent()
        val errorJson = """{"action":"error","code":"10110","desc":"invalid authorization"}"""
        captured.listener!!.onMessage(captured.ws, errorJson)
        advanceUntilIdle()
        // Failed 必须携带 parseError 的结果，且这是唯一事件（流随后正常完成）
        assertEquals(
            listOf(SpeechEvent.Failed(segmentId = null, error = engine.parseError(errorJson))),
            collected.await(),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `transcribeEvents emits NETWORK Failed and completes on onFailure`() = runTest {
        val captured = Captured()
        val engine = engineWith(captured)
        val collected = async { engine.transcribeEvents(flowOf(shortArrayOf(1, 2))).toList() }
        runCurrent()
        captured.listener!!.onFailure(captured.ws, RuntimeException("boom"), null)
        advanceUntilIdle()
        assertEquals(
            listOf(SpeechEvent.Failed(segmentId = null, error = AsrError.network("Xunfei websocket failure"))),
            collected.await(),
        )
    }

    // ---- M1e-3: hard cancellation cleanup + non-Text event filtering ----
    // 收集协程被 cancel 时：必须 ws.cancel()、不发送 {"end":true}、不 close()、快速完成。
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `collector cancellation cancels ws without end or close`() = runTest {
        val captured = Captured()
        val engine = engineWith(captured)
        val pcm = flow<ShortArray> { awaitCancellation() }
        val job = launch { engine.transcribe(pcm).collect {} }
        runCurrent()
        // 引擎已建立 ws（sendJob 已挂起在 pcm.collect 的 awaitCancellation）
        assertNotNull(captured.listener)
        job.cancelAndJoin()
        assertEquals("cancelCalls", 1, captured.ws.cancelCalls)
        assertEquals("closeCalls", 0, captured.ws.closeCalls)
        assertTrue("no end frame on cancellation", captured.ws.sentFrames.none { it.contains("\"end\": true") })
        assertTrue(captured.ws.sentFrames.isEmpty())
    }

    // 正常 PCM 自然结束（非取消）：仍发 end + close，不 cancel。
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `collector normal completion sends end and close without cancel`() = runTest {
        val captured = Captured()
        val engine = engineWith(captured)
        val job = launch { engine.transcribe(flowOf(shortArrayOf(1, 2))).collect {} }
        runCurrent()
        // 先发 started 满足 watchdog，再驱动 onClosed 关通道
        captured.listener!!.onMessage(captured.ws, """{"action":"started","code":"0","desc":"success"}""")
        captured.listener!!.onClosed(captured.ws, 1000, "done")
        advanceUntilIdle()
        assertEquals("cancelCalls", 0, captured.ws.cancelCalls)
        assertEquals("closeCalls", 1, captured.ws.closeCalls)
        assertTrue(captured.ws.sentFrames.any { it.contains("\"end\": true") })
    }

    // ---- M1e-2: 初始响应 watchdog（responseTimeoutMs）----
    // 服务端在 timeout 内没有任何消息（连 action=started 都没有）时：
    // 发安全 NETWORK Failed、流正常完成、ws.cancel()；不发 end、不 close。
    // PCM 用 flow { awaitCancellation() } 保持 sender 存活，虚拟时间推进越过阈值。
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `transcribeEvents emits timeout Failed and completes when server silent`() = runTest {
        val captured = Captured()
        val engine = engineWith(captured, responseTimeoutMs = 1_000L)
        val collected = async { engine.transcribeEvents(flow<ShortArray> { awaitCancellation() }).toList() }
        advanceUntilIdle()
        assertNotNull(captured.listener)
        // 虚拟时间推进到阈值之后，response watchdog 必须收口挂起的 collector。
        advanceTimeBy(2_000L)
        advanceUntilIdle()
        // watchdog 生效后 collected 会完成；未生效时（RED）此断言直接失败，无需等 runTest 收尾
        assertTrue("collector must complete after watchdog timeout", collected.isCompleted)
        val events = collected.getCompleted()
        assertEquals(
            listOf(SpeechEvent.Failed(segmentId = null, error = AsrError.network("Xunfei response timeout"))),
            events,
        )
        assertEquals("cancelCalls", 1, captured.ws.cancelCalls)
        assertEquals("closeCalls", 0, captured.ws.closeCalls)
        assertTrue("no end frame on timeout", captured.ws.sentFrames.none { it.contains("\"end\": true") })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `transcribeEvents closes normally after sustained silence once server responds`() = runTest {
        val captured = Captured()
        val engine = engineWith(
            captured = captured,
            responseTimeoutMs = 10_000L,
            silenceTimeoutMs = 1_000L,
            silenceAmplitudeThreshold = 100,
        )
        val pcmGate = CompletableDeferred<Unit>()
        val collected = async {
            engine.transcribeEvents(
                flow {
                    pcmGate.await()
                    emit(ShortArray(16_000))
                    awaitCancellation()
                },
            ).toList()
        }
        runCurrent()
        captured.listener!!.onMessage(captured.ws, """{"action":"started","code":"0","desc":"success"}""")
        pcmGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(emptyList<SpeechEvent>(), collected.await())
        assertEquals("cancelCalls", 0, captured.ws.cancelCalls)
        assertEquals("closeCalls", 1, captured.ws.closeCalls)
        assertTrue("silence close must not send end", captured.ws.sentFrames.none { it.contains("\"end\": true") })
    }

    // ---- M1e-2: 服务端在 timeout 内来消息则 watchdog 不触发（正常路径保留）----
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `transcribeEvents does not time out when server responds in time`() = runTest {
        val captured = Captured()
        val engine = engineWith(captured, responseTimeoutMs = 10_000L)
        val collected = async { engine.transcribeEvents(flowOf(shortArrayOf(1, 2))).toList() }
        runCurrent()
        val resultJson = """{"action":"result","code":"0","data":"{\"cn\":{\"st\":{\"bg\":\"0\",\"ed\":\"0\",\"rt\":[{\"ws\":[{\"cw\":[{\"w\":\"你好\"}]}],\"type\":\"0\"}]}}}","desc":"success"}"""
        captured.listener!!.onMessage(captured.ws, resultJson)
        captured.listener!!.onClosed(captured.ws, 1000, "done")
        advanceUntilIdle()
        assertEquals(
            listOf(SpeechEvent.Text(segmentId = "xunfei-stream", text = "你好")),
            collected.await(),
        )
        assertEquals("cancelCalls", 0, captured.ws.cancelCalls)
        assertEquals("closeCalls", 1, captured.ws.closeCalls)
    }

    // transcribe 兼容层只透传 Text；EngineChanged/Recovering 不产生空字符串。
    // 子类仅替换事件源（注入非 Text 事件），transcribe 的映射逻辑继承自被测类。
    private class StubEventsEngine(
        private val events: List<SpeechEvent>,
    ) : XunfeiRtasrEngine(appId = "myapp", apiKey = "mykey") {
        override fun transcribeEvents(pcm: Flow<ShortArray>): Flow<SpeechEvent> = events.asFlow()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `transcribe drops non-Text events without empty strings`() = runTest {
        val engine = StubEventsEngine(
            listOf(
                SpeechEvent.Recovering(segmentId = "x", message = "recovering"),
                SpeechEvent.EngineChanged(engine = "stub"),
                SpeechEvent.Text(segmentId = "s", text = "你好"),
            ),
        )
        val out = engine.transcribe(flowOf(shortArrayOf(1, 2))).toList()
        assertEquals(listOf("你好"), out)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `legacy transcribe only emits strings and throws typed AsrException on error`() = runTest {
        val captured = Captured()
        val engine = engineWith(captured)
        val texts = async { engine.transcribe(flowOf(shortArrayOf(1, 2))).toList() }
        runCurrent()
        // 先发 started 满足 watchdog（默认 8s），再驱动 result → onClosed
        captured.listener!!.onMessage(captured.ws, """{"action":"started","code":"0","desc":"success"}""")
        val resultJson = """{"action":"result","code":"0","data":"{\"cn\":{\"st\":{\"bg\":\"0\",\"ed\":\"0\",\"rt\":[{\"ws\":[{\"cw\":[{\"w\":\"你好\"}]}],\"type\":\"0\"}]}}}","desc":"success"}"""
        captured.listener!!.onMessage(captured.ws, resultJson)
        captured.listener!!.onClosed(captured.ws, 1000, "done")
        advanceUntilIdle()
        assertEquals(listOf("你好"), texts.await())

        // 错误路径：transcribe 不得 println/静默丢失，必须抛 AsrException(event.error)
        val captured2 = Captured()
        val engine2 = engineWith(captured2)
        val thrown = async { runCatching { engine2.transcribe(flowOf(shortArrayOf(1, 2))).toList() } }
        runCurrent()
        // 错误路径同样先发 started 满足 watchdog，再发 action=error
        captured2.listener!!.onMessage(captured2.ws, """{"action":"started","code":"0","desc":"success"}""")
        val errorJson = """{"action":"error","code":"12345","desc":"some provider failure"}"""
        captured2.listener!!.onMessage(captured2.ws, errorJson)
        advanceUntilIdle()
        val exception = thrown.await().exceptionOrNull()
        assertTrue(exception is AsrException)
        assertEquals(AsrError.Kind.UNKNOWN, (exception as AsrException).error.kind)
        assertEquals("Xunfei provider error", exception.error.message)
    }
}
