package com.classsentinel.core.summary

/**
 * 总结模板的稳定配置。课堂转写永远作为 user message 传入，不能拼进 system message。
 * [customPrompt] 只保存用户明确编辑的模板要求；模板正文仍由本类构造为结构化消息。
 */
data class SummaryTemplate(
    val id: String,
    val label: String,
    val sections: List<String>,
    val customPrompt: String? = null,
) {
    init {
        require(id.isNotBlank()) { "template id must not be blank" }
        require(label.isNotBlank()) { "template label must not be blank" }
        require(sections.isNotEmpty()) { "template must contain at least one section" }
    }

    /** 给 LLM 的 system 指令；这里不包含课堂转写正文。 */
    val systemPrompt: String
        get() = buildString {
            append("你是课堂笔记助手。根据课堂转写内容，输出四段式总结（结构清晰的中文 Markdown）。")
            append("\n格式严格如下：\n")
            sections.forEach { section -> append("## ").append(section).append('\n') }
            append("未提及的段落写「（本节未提及）」；只根据转写内容总结，不要编造。")
        }

    /**
     * 构造完整总结请求。转写只放在 user message，避免课堂原文改变 system 指令边界。
     */
    fun messages(transcript: String): List<Map<String, String>> = listOf(
        mapOf("role" to "system", "content" to systemPrompt),
        mapOf("role" to "user", "content" to userContent("课堂转写", transcript)),
    )

    /** 长转写的分块压缩请求，同样保持正文与 system 指令隔离。 */
    fun partialMessages(chunk: String): List<Map<String, String>> = listOf(
        mapOf(
            "role" to "system",
            "content" to "你是课堂笔记助手。把这段课堂转写压缩成要点摘要（300字内），" +
                "保留与${sections.joinToString("、")}相关的信息，只根据原文总结，不要编造。用中文。",
        ),
        mapOf("role" to "user", "content" to userContent("课堂转写片段", chunk)),
    )

    private fun userContent(label: String, content: String): String = buildString {
        customPrompt?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append("用户自定义总结要求：\n")
            append(it)
            append("\n\n")
        }
        append(label).append("：\n").append(content)
    }
}

/** DataStore 中持久化的最小模板配置；不保存渲染后的 prompt 或课堂内容。 */
data class SummaryTemplateSettings(
    val templateId: String,
    val customPrompt: String,
)

/**
 * 内置总结模板目录。
 * 只提供有限的本地模板，不做模板市场，也不执行远程模板内容。
 */
object SummaryTemplates {
    const val DEFAULT_ID = "default"
    const val EXAM_REVIEW_ID = "exam_review"
    const val SEMINAR_ID = "seminar"
    const val LAB_ID = "lab"
    const val CUSTOM_ID = "custom"

    /** 自定义提示词的持久化上限，防止设置页把异常大文本带入每次总结请求。 */
    const val MAX_CUSTOM_PROMPT_LENGTH = 1_000

    val DEFAULT = SummaryTemplate(
        id = DEFAULT_ID,
        label = "默认四段式",
        sections = listOf("知识点", "作业", "考试重点", "下节预告"),
    )

    val EXAM_REVIEW = SummaryTemplate(
        id = EXAM_REVIEW_ID,
        label = "考试复习",
        sections = listOf("概念", "公式", "易错点", "可能考题"),
    )

    val SEMINAR = SummaryTemplate(
        id = SEMINAR_ID,
        label = "研讨课",
        sections = listOf("立场", "证据", "分歧", "后续跟进"),
    )

    val LAB = SummaryTemplate(
        id = LAB_ID,
        label = "实验课",
        sections = listOf("目标", "材料", "步骤", "结果"),
    )

    val BUILT_INS = listOf(DEFAULT, EXAM_REVIEW, SEMINAR, LAB)

    /** 读取未知/损坏的 ID 时安全回退，避免历史设置让 Worker 无法执行。 */
    fun byId(id: String): SummaryTemplate =
        BUILT_INS.firstOrNull { it.id == id } ?: DEFAULT

    fun isKnownId(id: String): Boolean = id == CUSTOM_ID || BUILT_INS.any { it.id == id }

    fun requireKnownId(id: String): String {
        require(isKnownId(id)) { "UNKNOWN_TEMPLATE_ID" }
        return id
    }

    /** 返回固定错误码；null 表示通过。 */
    fun validateCustomPrompt(prompt: String, required: Boolean = true): String? {
        val normalized = prompt.trim()
        if (required && normalized.isBlank()) return "CUSTOM_PROMPT_BLANK"
        if (normalized.length > MAX_CUSTOM_PROMPT_LENGTH) return "CUSTOM_PROMPT_TOO_LONG"
        return null
    }

    fun custom(prompt: String): SummaryTemplate {
        val normalized = prompt.trim()
        val error = validateCustomPrompt(normalized, required = true)
        require(error == null) { error ?: "INVALID_CUSTOM_PROMPT" }
        return SummaryTemplate(
            id = CUSTOM_ID,
            label = "自定义",
            // 自定义提示词负责调整要求，仍保留稳定四段标题，便于 UI 展示和复制。
            sections = DEFAULT.sections,
            customPrompt = normalized,
        )
    }

    /** 将 DataStore 的 ID + 文本解析为可执行模板；损坏的自定义值回退默认模板。 */
    fun resolve(id: String, customPrompt: String): SummaryTemplate = when (id) {
        CUSTOM_ID -> runCatching { custom(customPrompt) }.getOrElse { DEFAULT }
        else -> byId(id)
    }
}
