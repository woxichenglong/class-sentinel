package com.classsentinel.core.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/** 回答风格：口语简短 / 学术要点 */
enum class AnswerStyle { TERSENESS, ACADEMIC }

/** 课堂答题服务：拼提示词 → LLM 流式回答 */
class AnswerService(
    private val client: LlmClient = LlmClient(),
) {

    fun answer(
        question: String,
        context: String,
        style: AnswerStyle,
        cfg: LlmConfig,
        answerLength: String = "mid",
        streamOutput: Boolean = true,
    ): Flow<String> {
        val policy = answerLengthPolicy(answerLength, style)
        val system = buildString {
            appendLine("你是课堂即时答题助手。")
            appendLine("先给出一句可直接口头回答的短结论，不解释推理过程。")
            appendLine("只根据用户提供的课堂上下文和问题回答。")
            appendLine("如果上下文不足、问题未听清或无法确定，明确输出“依据不足”或“不确定”，不要猜测。")
            appendLine("不要输出 Markdown 长文、免责声明、API 调试信息或课堂上下文原文的重复大段摘录。")
            when (style) {
                AnswerStyle.TERSENESS ->
                    append("答案务必简短(≤${policy.maxChars}字)、口语化、可直接口头说出。")
                AnswerStyle.ACADEMIC ->
                    append("回答保持结构清晰、要点化且不超过${policy.maxChars}字。")
            }
        }
        val user = "老师提问: \"$question\"\n课堂上下文: $context\n请直接给出可以口头回答的内容。"
        val deltas = client.streamChat(
            messages = listOf(
                mapOf("role" to "system", "content" to system),
                mapOf("role" to "user", "content" to user),
            ),
            cfg = cfg.copy(maxTokens = policy.maxTokens),
        )
        if (streamOutput) return deltas

        return flow {
            val answer = StringBuilder()
            deltas.collect { answer.append(it) }
            emit(answer.toString())
        }
    }
}

/** 回答长度偏好映射：同时约束提示词字数和 provider 输出 token 预算。 */
internal data class AnswerLengthPolicy(
    val maxChars: Int,
    val maxTokens: Int,
)

internal fun answerLengthPolicy(value: String, style: AnswerStyle): AnswerLengthPolicy = when (value.trim().lowercase()) {
    "short" -> AnswerLengthPolicy(
        maxChars = if (style == AnswerStyle.ACADEMIC) 120 else 60,
        maxTokens = 128,
    )
    "long" -> AnswerLengthPolicy(
        maxChars = if (style == AnswerStyle.ACADEMIC) 400 else 160,
        maxTokens = 512,
    )
    else -> AnswerLengthPolicy(
        maxChars = if (style == AnswerStyle.ACADEMIC) 200 else 80,
        maxTokens = 256,
    )
}
