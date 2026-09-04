package com.classsentinel.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationTest {

    @Test
    fun `answer detail route keeps event id as a decimal string`() {
        assertEquals("answer/42", answerDetailRoute(42L))
        assertEquals(42L, parseAnswerEventId("42"))
    }

    @Test
    fun `invalid answer route ids are safe`() {
        assertNull(parseAnswerEventId(null))
        assertNull(parseAnswerEventId(""))
        assertNull(parseAnswerEventId("-1"))
        assertNull(parseAnswerEventId("42/extra"))
        assertNull(parseAnswerEventId("not-a-number"))
    }
}
