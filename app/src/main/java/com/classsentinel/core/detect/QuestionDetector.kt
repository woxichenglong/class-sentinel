package com.classsentinel.core.detect

/**
 * 提问检测：三级触发词表，规则层零成本零延迟。
 * level 1=少（最保守）、2=中（默认）、3=多（激进）。
 */
object QuestionDetector {

    private val level1 = listOf("谁来回答", "哪位同学", "谁来", "回答一下", "这个问题")
    private val level2 = level1 + listOf("说一下", "谈谈", "思考一下", "你们觉得", "怎么看", "为什么")
    private val level3 = level2 + listOf("什么是", "举个例子", "对不对", "有没有同学", "讲讲", "说说看")

    private val directMarkers = listOf(
        "你来",
        "请你",
        "你能",
        "你觉得",
        "你认为",
        "你怎么看",
        "你的看法",
    )
    private val classInviteMarkers = listOf(
        "谁来回答",
        "哪位同学",
        "有没有同学",
        "有没有人",
        "请一位同学",
        "谁能",
    )
    private val binaryMarkers = listOf(
        "对不对",
        "是不是",
        "是否",
        "对吧",
        "好不好",
        "行不行",
        "有没有问题",
        "吗",
    )
    private val openMarkers = listOf(
        "为什么",
        "怎么",
        "如何",
        "什么是",
        "解释",
        "举个例子",
        "举例",
        "谈谈",
        "说一下",
        "说说",
        "讲讲",
        "回答一下",
        "回答这个问题",
        "思考一下",
        "怎么看",
    )

    /** 命中返回触发词，否则 null */
    fun detect(segment: String, level: Int): String? =
        words(level).firstOrNull { segment.contains(it) }

    /**
     * Detect only questions worth sending to the answer service. This is a
     * conservative final-text rule: binary confirmations and rhetorical
     * prompts are rejected before open-question markers are considered.
     */
    fun detectAnswerable(segment: String, level: Int): AnswerableQuestion? {
        if (segment.isBlank()) return null
        if (classInviteMarkers.any { segment.contains(it) }) {
            return AnswerableQuestion(EventScope.CLASS_OPEN, "CLASS_INVITE")
        }
        if (binaryMarkers.any { segment.contains(it) }) return null
        if (directMarkers.any { segment.contains(it) } && openMarkers.any { segment.contains(it) }) {
            return AnswerableQuestion(EventScope.DIRECT, "DIRECT_REQUEST")
        }
        if (openMarkers.any { segment.contains(it) } || detect(segment, level) != null) {
            return AnswerableQuestion(EventScope.CLASS_OPEN, "OPEN_QUESTION")
        }
        return null
    }

    private fun words(level: Int): List<String> = when (level) {
        1 -> level1
        2 -> level2
        else -> level3
    }
}

data class AnswerableQuestion(
    val scope: EventScope,
    val reason: String,
)
