package com.classsentinel.core.detect

/**
 * 提问检测：三级触发词表，规则层零成本零延迟。
 * level 1=少（最保守）、2=中（默认）、3=多（激进）。
 */
object QuestionDetector {

    private val level1 = listOf("谁来回答", "哪位同学", "谁来", "回答一下", "这个问题")
    private val level2 = level1 + listOf("说一下", "谈谈", "思考一下", "你们觉得", "怎么看", "为什么")
    private val level3 = level2 + listOf("什么是", "举个例子", "对不对", "有没有同学", "讲讲", "说说看")

    /** 命中返回触发词，否则 null */
    fun detect(segment: String, level: Int): String? =
        words(level).firstOrNull { segment.contains(it) }

    private fun words(level: Int): List<String> = when (level) {
        1 -> level1
        2 -> level2
        else -> level3
    }
}
