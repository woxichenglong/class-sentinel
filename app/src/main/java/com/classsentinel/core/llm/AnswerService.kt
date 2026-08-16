package com.classsentinel.core.llm

import kotlinx.coroutines.flow.Flow

/** 回答风格：口语简短 / 学术要点 */
enum class AnswerStyle { TERSENESS, ACADEMIC }

/** 课堂答题服务：拼提示词 → LLM 流式回答 */
class AnswerService(
    private val client: LlmClient = LlmClient(),
) {

    fun answer(question: String, context: String, style: AnswerStyle, cfg: LlmConfig): Flow<String> {
        val system = when (style) {
            AnswerStyle.TERSENESS ->
                "你是课堂答题助手。答案务必简短(≤80字)、口语化、可直接口头说出。只给答案不解释过程。"
            AnswerStyle.ACADEMIC ->
                "你是课堂答题助手。给出结构清晰要点化的回答(≤200字)。"
        }
        val user = "老师提问: \"$question\"\n课堂上下文: $context\n请直接给出可以口头回答的内容。"
        return client.streamChat(
            messages = listOf(
                mapOf("role" to "system", "content" to system),
                mapOf("role" to "user", "content" to user),
            ),
            cfg = cfg,
        )
    }
}
