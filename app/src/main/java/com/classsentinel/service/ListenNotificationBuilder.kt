package com.classsentinel.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.classsentinel.MainActivity
import com.classsentinel.R

internal object ListenNotificationBuilder {
    fun build(
        context: Context,
        status: ListenNotificationStatus,
        stopIntent: PendingIntent,
    ): Notification = Notification.Builder(context, ListenService.CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(ListenNotificationFormatter.statusText(status))
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setOngoing(true)
        .addAction(
            Notification.Action.Builder(null, "停止听讲", stopIntent).build(),
        )
        .build()
}
