package com.classsentinel.core.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerPromptPolicyTest {

    private fun systemPrompt(): String =
        buildAnswerSystemPrompt(
            style = AnswerStyle.TERSENESS,
            policy = answerLengthPolicy("mid", AnswerStyle.TERSENESS),
        )

    @Test
    fun `question is primary and context is assisted knowledge rather than the only source`() {
        val prompt = systemPrompt()

        assertTrue(prompt.contains("优先理解并回答老师当前提出的问题"))
        assertTrue(prompt.contains("问题是主要回答对象"))
        assertTrue(prompt.contains("课堂上下文是辅助信息，不是唯一知识来源"))
        assertTrue(prompt.contains("依靠可靠的一般知识回答"))
        assertTrue(prompt.contains("即使课堂上下文没有直接提供答案，也应正常作答"))
        assertFalse(prompt.contains("只根据用户提供的课堂上下文和问题回答"))
    }

    @Test
    fun `course-specific missing information requires insufficient instead of guessing`() {
        val prompt = systemPrompt()

        assertTrue(prompt.contains("课程特定事实"))
        assertTrue(prompt.contains("实验数据"))
        assertTrue(prompt.contains("公式"))
        assertTrue(prompt.contains("图表"))
        assertTrue(prompt.contains("材料"))
        assertTrue(prompt.contains("指代"))
        assertTrue(prompt.contains("现有课堂上下文不足"))
        assertTrue(prompt.contains("不得对缺失的课程特定事实进行猜测"))
        assertTrue(prompt.contains(INSUFFICIENT_ANSWER_SENTINEL))
    }

    @Test
    fun `representative question and context pairs remain inputs to one answer prompt`() {
        val cases = listOf(
            "机器学习和深度学习有什么区别？" to "今天我们开始上课。",
            "牛顿第二定律是什么？" to "",
            "为什么 TCP 建立连接需要三次握手？" to "无相关课堂内容",
            "老师刚才说的第二个原因是什么？" to "没有对应前文",
            "这个式子为什么这里要除以 n？" to "没有公式",
            "为什么会这样？" to "上一段明确讨论训练集过拟合导致验证误差上升",
        )

        val prompt = systemPrompt()
        assertTrue(prompt.contains("问题是主要回答对象"))
        assertTrue(prompt.contains("课堂上下文用于"))
        cases.forEach { (question, context) ->
            val user = buildAnswerUserPrompt(question, context)
            assertTrue(user.contains(question))
            assertTrue(user.contains(context))
        }
    }

    @Test
    fun `insufficient remains an exact single sentinel while ordinary answers stay short`() {
        val prompt = systemPrompt()

        assertTrue(prompt.contains("只能输出唯一标记 $INSUFFICIENT_ANSWER_SENTINEL"))
        assertTrue(prompt.contains("不得因为“课堂上下文中没有直接写出答案”而输出"))
        assertTrue(prompt.contains("不要输出 Markdown 长文"))
        assertTrue(prompt.contains("答案务必简短(≤80字)"))
    }

    @Test
    fun `academic style keeps its structured short-answer instruction`() {
        val prompt = buildAnswerSystemPrompt(
            style = AnswerStyle.ACADEMIC,
            policy = answerLengthPolicy("short", AnswerStyle.ACADEMIC),
        )

        assertTrue(prompt.contains("回答保持结构清晰、要点化且不超过120字"))
        assertTrue(prompt.contains("问题是主要回答对象"))
    }
}
