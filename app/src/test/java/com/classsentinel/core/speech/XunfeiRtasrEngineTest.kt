package com.classsentinel.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
