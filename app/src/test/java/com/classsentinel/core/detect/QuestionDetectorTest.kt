package com.classsentinel.core.detect

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
