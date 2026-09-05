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
            answer = "短答案",
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

    @Test
    fun `answer notification does not disclose question context or provider details`() {
        val context = RuntimeEnvironment.getApplication()
        val question = "QUESTION-PRIVATE-42"
        val classroomContext = "CONTEXT-PRIVATE-17 provider body sk-provider-secret"

        val notification = AnswerNotificationBuilder.build(
            context = context,
            eventId = 42L,
            answer = "短答案",
        )
        val extrasText = notification.extras.toString()

        assertTrue(extrasText.contains("短答案"))
        assertFalse(extrasText.contains(question))
        assertFalse(extrasText.contains(classroomContext))
        assertFalse(extrasText.contains("provider body"))
        assertFalse(extrasText.contains("sk-provider-secret"))
        assertTrue(notification.contentIntent != null)
    }
}
