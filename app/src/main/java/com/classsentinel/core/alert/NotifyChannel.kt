package com.classsentinel.core.alert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.classsentinel.MainActivity
import com.classsentinel.R
import com.classsentinel.core.detect.ClassEvent
import com.classsentinel.core.detect.EventType

/**
 * 通知通道：高优先级通知，点名/提问立即弹出。
 * 内容只提示事件类型，不含答案。
 */
class NotifyChannel(
) : AlertChannel {

    override val key = "notify"

    override fun fire(event: ClassEvent, context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "点名/提问提醒",
                NotificationManager.IMPORTANCE_HIGH,
            )
        )

        val title = if (event.type == EventType.ROLLCALL) "老师点名了" else "老师提问了"
        val text = if (event.type == EventType.ROLLCALL) "老师喊到了你，快做好准备！" else "老师开始提问了，快看看是不是你！"

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "class_alert"
        private const val NOTIFICATION_ID = 2001
    }
}

/** 锁屏开关的纯映射：关闭时由系统隐藏通知内容，避免课堂提醒泄露。 */
@Suppress("UNUSED_PARAMETER")
internal fun notificationVisibility(lockscreenNotify: Boolean): Int = Notification.VISIBILITY_SECRET
