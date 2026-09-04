package com.classsentinel.service

import java.util.Locale

data class ListenNotificationStatus(
    val elapsedMs: Long,
    val engine: String,
    val pendingSegments: Int,
)

object ListenNotificationFormatter {
    fun statusText(status: ListenNotificationStatus): String {
        val totalSeconds = status.elapsedMs.coerceAtLeast(0L) / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val engine = status.engine.ifBlank { "未知" }
        val pending = status.pendingSegments.coerceAtLeast(0)
        return String.format(
            Locale.ROOT,
            "已听 %02d:%02d · 引擎 %s · 待处理 %d 段",
            minutes,
            seconds,
            engine,
            pending,
        )
    }
}
