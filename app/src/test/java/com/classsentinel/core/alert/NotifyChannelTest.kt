package com.classsentinel.core.alert

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Test

class NotifyChannelTest {

    @Test
    fun `lockscreen content is always secret regardless of legacy preference`() {
        assertEquals(Notification.VISIBILITY_SECRET, notificationVisibility(lockscreenNotify = true))
        assertEquals(Notification.VISIBILITY_SECRET, notificationVisibility(lockscreenNotify = false))
    }
}
