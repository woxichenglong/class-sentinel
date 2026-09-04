package com.classsentinel.core.detect

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class QuestionDetectorTest {

    @Test
    fun `level1 hits classic ask`() {
        assertNotNull(QuestionDetector.detect("谁来回答这个问题", 1))
    }

    @Test
    fun `level1 misses level2 words`() {
        assertNull(QuestionDetector.detect("大家思考一下为什么", 1))
    }

    @Test
    fun `level2 hits why`() {
        assertNotNull(QuestionDetector.detect("大家思考一下为什么", 2))
    }

    @Test
    fun `level3 hits more`() {
        assertNotNull(QuestionDetector.detect("谁能举个例子", 3))
    }

    @Test
    fun `plain statement not hit`() {
        assertNull(QuestionDetector.detect("大家先看书十分钟", 3))
    }

    @Test
    fun `level out of range falls to level3`() {
        assertNotNull(QuestionDetector.detect("谁能举个例子", 99))
    }

    @Test
    fun `answerable detector classifies direct and class open questions`() {
        assertEquals(
            EventScope.DIRECT,
            QuestionDetector.detectAnswerable("你来回答这个问题", 2)?.scope,
        )
        assertEquals(
            EventScope.CLASS_OPEN,
            QuestionDetector.detectAnswerable("为什么这个结论成立", 2)?.scope,
        )
        assertEquals(
            EventScope.CLASS_OPEN,
            QuestionDetector.detectAnswerable("有没有同学来回答", 2)?.scope,
        )
    }

    @Test
    fun `answerable detector rejects binary confirmation`() {
        assertNull(QuestionDetector.detectAnswerable("这个结论对不对", 3))
        assertNull(QuestionDetector.detectAnswerable("是不是这样", 3))
    }
}
