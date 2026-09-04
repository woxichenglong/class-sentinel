package com.classsentinel.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.classsentinel.MainActivity
import com.classsentinel.R

/** Builds answer notifications without performing any network or persistence work. */
internal object AnswerNotificationBuilder {
    const val EXTRA_EVENT_ID = "com.classsentinel.extra.EVENT_ID"
    const val EXTRA_ACTION = "com.classsentinel.extra.ANSWER_ACTION"
    const val ACTION_EVIDENCE = "evidence"
    const val ACTION_RETRY = "retry"
    const val ACTION_IGNORE = "ignore"
    const val CHANNEL_ID = "answer_updates"
    const val NOTIFICATION_ID = 2002

    fun build(
        context: Context,
        eventId: Long,
        question: String,
        answer: String,
        contextSummary: String,
        occurredAtMs: Long,
    ): Notification {
        val shortAnswer = compactAnswer(answer)
        val detail = buildString {
            append("问题：")
            append(question.trim())
            append("\n答案：")
            append(shortAnswer)
            append("\n依据：")
            append(compactDetail(contextSummary))
            append("\n时间点：")
            append(occurredAtMs)
        }
        val contentIntent = pendingActivity(context, eventId, action = null, actionIndex = 0)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("课堂答案")
            .setContentText(shortAnswer)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)

        listOf(
            ACTION_EVIDENCE to "看依据",
            ACTION_RETRY to "重试",
            ACTION_IGNORE to "忽略",
        ).forEachIndexed { index, (action, title) ->
            notification.addAction(
                Notification.Action.Builder(
                    null,
                    title,
                    pendingActivity(context, eventId, action, index + 1),
                ).build(),
            )
        }
        return notification.build()
    }

    /** Shared route construction used by the notification and MainActivity deep-link handling. */
    fun detailIntent(context: Context, eventId: Long, action: String? = null): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_EVENT_ID, eventId)
            if (action != null) putExtra(EXTRA_ACTION, action)
        }

    private fun pendingActivity(
        context: Context,
        eventId: Long,
        action: String?,
        actionIndex: Int,
    ): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode(eventId, actionIndex),
        detailIntent(context, eventId, action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun requestCode(eventId: Long, actionIndex: Int): Int =
        ((eventId xor (eventId ushr 32)).toInt() * 31) + actionIndex

    private fun compactAnswer(answer: String): String =
        answer.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .take(2)
            .joinToString(" ")
            .take(MAX_ANSWER_CHARS)
            .ifBlank { "依据不足" }

    private fun compactDetail(value: String): String =
        value.replace(Regex("\\s+"), " ").trim().take(MAX_CONTEXT_CHARS).ifBlank { "无" }

    private const val MAX_ANSWER_CHARS = 160
    private const val MAX_CONTEXT_CHARS = 500
}
