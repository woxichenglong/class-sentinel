package com.classsentinel.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListenNotificationBuilderTest {
    @Test
    fun `notification exposes stop action and safe status text`() {
        val context = RuntimeEnvironment.getApplication()
        val stopIntent = PendingIntent.getService(
            context,
            17,
            Intent(context, ListenService::class.java).setAction(ListenService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = ListenNotificationBuilder.build(
            context = context,
            status = ListenNotificationStatus(
                elapsedMs = 125_000L,
                engine = "TeleSpeech",
                pendingSegments = 3,
            ),
            stopIntent = stopIntent,
        )

        assertEquals(
            "已听 02:05 · 引擎 TeleSpeech · 待处理 3 段",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(),
        )
        assertEquals(1, notification.actions.size)
        assertEquals("停止听讲", notification.actions.single().title)
        assertEquals(true, notification.contentIntent != null)
        assertFalse(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("课堂原文"))
        assertFalse(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("AI答案"))
    }
}
