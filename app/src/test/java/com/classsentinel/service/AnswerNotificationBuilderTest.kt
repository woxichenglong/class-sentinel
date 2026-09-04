package com.classsentinel.service

import android.app.Notification
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnswerNotificationBuilderTest {

    @Test
    fun `answer notification is secret concise and targets one event detail`() {
        val context = RuntimeEnvironment.getApplication()

        val notification = AnswerNotificationBuilder.build(
            context = context,
            eventId = 42L,
            question = "问题原文",
            answer = "短答案",
            contextSummary = "前置课堂上下文",
            occurredAtMs = 123_000L,
        )

        assertEquals("短答案", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(Notification.VISIBILITY_SECRET, notification.visibility)
        assertEquals(Notification.CATEGORY_MESSAGE, notification.category)
        assertNull(notification.fullScreenIntent)
        assertEquals(3, notification.actions.size)
        assertEquals("看依据", notification.actions[0].title)
        assertEquals("重试", notification.actions[1].title)
        assertEquals("忽略", notification.actions[2].title)

        val detailIntent = AnswerNotificationBuilder.detailIntent(context, 42L)
        assertEquals(42L, detailIntent.getLongExtra(AnswerNotificationBuilder.EXTRA_EVENT_ID, -1L))
        assertTrue(notification.contentIntent != null)
        assertFalse(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("问题原文"))
    }
}
