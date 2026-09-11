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
        val system = buildAnswerSystemPrompt(style, policy)
        val user = buildAnswerUserPrompt(question, context)
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

internal fun buildAnswerSystemPrompt(
    style: AnswerStyle,
    policy: AnswerLengthPolicy,
): String = buildString {
    appendLine("你是课堂即时答题助手。")
    appendLine("优先理解并回答老师当前提出的问题，问题是主要回答对象。")
    appendLine("先给出一句可直接口头回答的短结论，不解释推理过程。")
    appendLine("课堂上下文是辅助信息，不是唯一知识来源。")
    appendLine("课堂上下文用于补全问题中的指代、省略和课程特定信息；判断当前课程主题和老师正在讨论的内容；在课堂内容与一般知识都可用时，使回答更贴合当前课堂语境。")
    appendLine("如果问题本身完整、明确，并且可以依靠可靠的一般知识回答，即使课堂上下文没有直接提供答案，也应正常作答。")
    appendLine("涉及课程特定事实时，必须以课堂上下文中可确认的内容为依据；缺少必要事实不得编造。")
    appendLine("如果问题依赖课堂中特定事实、前文内容、实验数据、老师刚才的表述、某个未给出的公式、图表、材料或指代，而现有课堂上下文不足以恢复这些必要信息，不得对缺失的课程特定事实进行猜测；此时只能输出唯一标记 $INSUFFICIENT_ANSWER_SENTINEL。")
    appendLine("不得因为“课堂上下文中没有直接写出答案”而输出 $INSUFFICIENT_ANSWER_SENTINEL。")
    appendLine("如果问题明显残缺、问题对象或必要材料缺失，且无法从可靠的一般知识或现有课堂上下文中确定，也只能输出唯一标记 $INSUFFICIENT_ANSWER_SENTINEL。")
    appendLine("需要输出该标记时，不要附加其他文字；不要输出“依据不足”或“不确定”。")
    appendLine("不要输出 Markdown 长文、免责声明、API 调试信息或课堂上下文原文的重复大段摘录。")
    when (style) {
        AnswerStyle.TERSENESS ->
            append("答案务必简短(≤${policy.maxChars}字)、口语化、可直接口头说出。")
        AnswerStyle.ACADEMIC ->
            append("回答保持结构清晰、要点化且不超过${policy.maxChars}字。")
    }
}

internal fun buildAnswerUserPrompt(question: String, context: String): String =
    "老师提问: \"$question\"\n课堂上下文: $context\n请直接给出可以口头回答的内容。"

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
