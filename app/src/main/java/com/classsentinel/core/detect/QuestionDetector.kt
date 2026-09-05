package com.classsentinel.core.detect

/**
 * 提问检测：三级触发词表，规则层零成本零延迟。
 * level 1=少（最保守）、2=中（默认）、3=多（激进）。
 */
object QuestionDetector {

    private val level1 = listOf("谁来回答", "哪位同学", "谁来", "回答一下", "这个问题")
    private val level2 = level1 + listOf("说一下", "说说", "谈谈", "思考一下", "你们觉得", "怎么看", "为什么")
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
    private val openMarkersLevel1 = listOf(
        "解释",
        "回答一下",
        "回答这个问题",
    )
    private val openMarkersLevel2 = openMarkersLevel1 + listOf(
        "为什么",
        "怎么",
        "如何",
        "说说",
        "谈谈",
        "说一下",
        "思考一下",
        "怎么看",
    )
    private val openMarkersLevel3 = openMarkersLevel2 + listOf(
        "什么是",
        "举个例子",
        "举例",
        "讲讲",
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
        if (answerRequestPattern.containsMatchIn(segment)) {
            return AnswerableQuestion(EventScope.CLASS_OPEN, "ANSWER_REQUEST")
        }
        val openMarkers = openMarkers(level)
        val hasOpenMarker = openMarkers.any { segment.contains(it) }
        if (directMarkers.any { segment.contains(it) } && hasOpenMarker) {
            return AnswerableQuestion(EventScope.DIRECT, "DIRECT_REQUEST")
        }
        if (hasOpenMarker) {
            return AnswerableQuestion(EventScope.CLASS_OPEN, "OPEN_QUESTION")
        }
        if (binaryMarkers.any { segment.contains(it) }) return null
        if (detect(segment, level) != null) {
            return AnswerableQuestion(EventScope.CLASS_OPEN, "OPEN_QUESTION")
        }
        return null
    }

    private fun openMarkers(level: Int): List<String> = when (level) {
        1 -> openMarkersLevel1
        2 -> openMarkersLevel2
        else -> openMarkersLevel3
    }

    private fun words(level: Int): List<String> = when (level) {
        1 -> level1
        2 -> level2
        else -> level3
    }

    private val answerRequestPattern = Regex("(?:请|让|叫)\\s*[^，,。！？!?]{1,12}\\s*回答")
}

data class AnswerableQuestion(
    val scope: EventScope,
    val reason: String,
)
