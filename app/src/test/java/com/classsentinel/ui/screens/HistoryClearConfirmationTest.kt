package com.classsentinel.ui.screens

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Task 17：清空历史确认分支的纯 Kotlin 契约。 */
class HistoryClearConfirmationTest {

    @Test
    fun `canceling confirmation does not invoke clear action`() = runBlocking {
        var calls = 0

        val result = clearHistoryIfConfirmed(confirmed = false) {
            calls++
            "cleared"
        }

        assertNull(result)
        assertEquals(0, calls)
    }

    @Test
    fun `confirming history clear invokes action exactly once`() = runBlocking {
        var calls = 0

        val result = clearHistoryIfConfirmed(confirmed = true) {
            calls++
            "cleared"
        }

        assertEquals("cleared", result)
        assertEquals(1, calls)
    }
}
