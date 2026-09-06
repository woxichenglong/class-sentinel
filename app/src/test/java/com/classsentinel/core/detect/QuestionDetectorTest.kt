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
    fun `bare answer cue is an answerable open question`() {
        assertEquals(
            EventScope.CLASS_OPEN,
            QuestionDetector.detectAnswerable("请张伟回答", 1)?.scope,
        )
    }

    @Test
    fun `spoken say cue is available at standard level`() {
        assertEquals(
            EventScope.CLASS_OPEN,
            QuestionDetector.detectAnswerable("张伟说说他的看法", 2)?.scope,
        )
    }

    @Test
    fun `answerable detector rejects binary confirmation`() {
        assertNull(QuestionDetector.detectAnswerable("这个结论对不对", 3))
        assertNull(QuestionDetector.detectAnswerable("是不是这样", 3))
    }

    @Test
    fun `open direct question with trailing ma is not rejected as binary`() {
        assertEquals(
            EventScope.DIRECT,
            QuestionDetector.detectAnswerable("你能解释一下为什么 CAPM 成立吗", 2)?.scope,
        )
    }

    @Test
    fun `answerable open markers honor question word level`() {
        assertNull(QuestionDetector.detectAnswerable("我们谈谈资本成本", 1))
        assertEquals(
            EventScope.CLASS_OPEN,
            QuestionDetector.detectAnswerable("我们谈谈资本成本", 2)?.scope,
        )
    }

    @Test
    fun `sensitivity presets map question levels from conservative to aggressive`() {
        assertEquals(1, Sensitivity.STRICT.questionWordLevel)
        assertEquals(2, Sensitivity.STANDARD.questionWordLevel)
        assertEquals(3, Sensitivity.LOOSE.questionWordLevel)
    }

    @Test
    fun `real classroom open questions remain answerable`() {
        assertEquals(
            EventScope.CLASS_OPEN,
            QuestionDetector.detectAnswerable(
                "有没有同学来回答，为什么二氧化碳增加会导致全球变暖",
                2,
            )?.scope,
        )
        assertEquals(
            EventScope.CLASS_OPEN,
            QuestionDetector.detectAnswerable("为什么二氧化碳增加会导致全球变暖", 2)?.scope,
        )
        assertEquals(
            EventScope.CLASS_OPEN,
            QuestionDetector.detectAnswerable("你们觉得为什么价格会上涨", 2)?.scope,
        )
        assertEquals(
            EventScope.CLASS_OPEN,
            QuestionDetector.detectAnswerable("什么是边际效用", 3)?.scope,
        )
    }

    @Test
    fun `high confidence rhetorical prompts are not answerable questions`() {
        assertNull(QuestionDetector.detectAnswerable("这个天赋设计有点垃圾，为什么这么说呢？", 2))
        assertNull(QuestionDetector.detectAnswerable("看着数值很低，其实已经很慷慨了。为什么这么说呢？", 2))
        assertNull(QuestionDetector.detectAnswerable("这个东西吧，怎么说呢……", 2))
        assertNull(QuestionDetector.detectAnswerable("你也许会问这个设计有什么意义", 2))
    }

    @Test
    fun `strong classroom targets override rhetorical guard`() {
        assertEquals(
            EventScope.CLASS_OPEN,
            QuestionDetector.detectAnswerable("有没有同学来回答，为什么这么说？", 2)?.scope,
        )
        assertEquals(
            EventScope.DIRECT,
            QuestionDetector.detectAnswerable("张伟，你来回答为什么这么说", 2)?.scope,
        )
    }
}
