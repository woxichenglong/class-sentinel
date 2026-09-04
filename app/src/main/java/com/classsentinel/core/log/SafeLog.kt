package com.classsentinel.core.log

import android.util.Log

/**
 * 只输出白名单诊断字段的日志门面。
 *
 * 任何未列入白名单的字段都会被丢弃，且不调用其 toString()，避免把课堂原文、答案、
 * 凭证或 provider body 藏进异常/对象的字符串表示。字符串字段还必须是受限的 ASCII
 * token；数值字段只能来自 Number。日志调用方因此只能记录可枚举的运行元数据。
 */
object SafeLog {
    private const val TAG = "ClassSentinel"

    private val tokenPattern = Regex("[A-Za-z0-9_.:-]{1,80}")
    private val tokenFields = setOf("module", "engine", "segmentId", "errorCode", "status")
    private val numberFields = setOf("elapsedMs", "httpCode", "chars", "retryCount", "levelDb")
    private val allowedFields = tokenFields + numberFields

    /** 供 JVM 测试和内存自检日志复用的无副作用安全格式化器。 */
    internal fun format(event: String, fields: Map<String, Any?> = emptyMap()): String {
        val safeEvent = event.takeIf { tokenPattern.matches(it) } ?: "event"
        val metadata = fields.asSequence()
            .filter { (key, _) -> key in allowedFields }
            .mapNotNull { (key, value) ->
                val safeValue = when {
                    key in tokenFields -> (value as? String)?.takeIf(tokenPattern::matches)
                    key in numberFields -> (value as? Number)?.toLong()?.toString()
                    else -> null
                }
                safeValue?.let { key to it }
            }
            .sortedBy { it.first }
            .joinToString(separator = " ") { (key, value) -> "$key=$value" }

        return if (metadata.isEmpty()) {
            "event=$safeEvent"
        } else {
            "event=$safeEvent $metadata"
        }
    }

    fun d(event: String, fields: Map<String, Any?> = emptyMap()) {
        // JVM/Robolectric 的 Android Log stub 可能未实现；诊断日志不能改变业务结果。
        runCatching { Log.d(TAG, format(event, fields)) }
    }

    fun w(event: String, fields: Map<String, Any?> = emptyMap()) {
        runCatching { Log.w(TAG, format(event, fields)) }
    }
}
