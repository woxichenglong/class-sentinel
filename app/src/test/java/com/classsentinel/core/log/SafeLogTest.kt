package com.classsentinel.core.log

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeLogTest {

    @Test
    fun `format keeps diagnostic metadata and drops sensitive fields`() {
        val formatted = SafeLog.format(
            event = "asr_failed",
            fields = mapOf(
                "module" to "OpenAiCompatAsrEngine",
                "elapsedMs" to 123L,
                "httpCode" to 500,
                "engine" to "telespeech",
                "chars" to 42,
                "segmentId" to "segment-7",
                "retryCount" to 1,
                "apiKey" to "sk-secret-value",
                "text" to "课堂原文不应出现",
                "answer" to "答案不应出现",
                "body" to "provider body 不应出现",
                "url" to "wss://provider.test?signa=secret-value",
                "exception" to "raw provider exception",
            ),
        )

        assertTrue(formatted.contains("asr_failed"))
        assertTrue(formatted.contains("module=OpenAiCompatAsrEngine"))
        assertTrue(formatted.contains("elapsedMs=123"))
        assertTrue(formatted.contains("httpCode=500"))
        assertTrue(formatted.contains("engine=telespeech"))
        assertTrue(formatted.contains("chars=42"))
        assertTrue(formatted.contains("segmentId=segment-7"))
        assertTrue(formatted.contains("retryCount=1"))
        assertFalse(formatted.contains("sk-secret-value"))
        assertFalse(formatted.contains("课堂原文不应出现"))
        assertFalse(formatted.contains("答案不应出现"))
        assertFalse(formatted.contains("provider body 不应出现"))
        assertFalse(formatted.contains("secret-value"))
        assertFalse(formatted.contains("raw provider exception"))
    }

    @Test
    fun `format ignores arbitrary fields instead of stringifying them`() {
        val formatted = SafeLog.format(
            event = "answer_complete",
            fields = mapOf(
                "module" to "ListenService",
                "chars" to 8,
                "nested" to mapOf("content" to "hidden"),
                "object" to object {
                    override fun toString(): String = "sensitive object"
                },
            ),
        )

        assertTrue(formatted.contains("module=ListenService"))
        assertTrue(formatted.contains("chars=8"))
        assertFalse(formatted.contains("hidden"))
        assertFalse(formatted.contains("sensitive object"))
    }
}
