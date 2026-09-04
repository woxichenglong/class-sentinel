package com.classsentinel.core.study

/** 单张闪卡；生成结果必须只包含这两个字段。 */
data class Flashcard(
    val question: String,
    val answer: String,
)

/** 单道小测题；correctIndex 为 options 的 0-based 下标。 */
data class QuizQuestion(
    val question: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String,
)

/** 双语产物的持久化内容；原文由本地转写提供，不信任模型回传的原文。 */
data class BilingualOutput(
    val original: String,
    val translation: String?,
)

sealed interface StudyGenerationResult<out T> {
    data class Success<T>(
        val value: T,
        val contentJson: String,
    ) : StudyGenerationResult<T>

    data class Failed(
        val errorCode: String,
    ) : StudyGenerationResult<Nothing>
}

/** 严格学习产物 JSON 的安全解析错误；message 不携带模型原文。 */
class StudyJsonParseException(
    val code: String,
) : IllegalArgumentException(code)
