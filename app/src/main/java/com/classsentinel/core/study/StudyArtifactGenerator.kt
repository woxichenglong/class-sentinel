package com.classsentinel.core.study

import com.classsentinel.core.llm.LlmClient
import com.classsentinel.core.llm.LlmConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** 生成器的可替换 LLM seam；生产默认使用 OpenAI 兼容 SSE 客户端。 */
typealias StudyStreamChat = (List<Map<String, String>>, LlmConfig) -> Flow<String>

/**
 * 课后学习产物生成器。
 *
 * 长转写沿用总结模块的两级压缩：先逐块提取要点，再把要点交给最终 JSON 生成请求。
 * 解析器只接受完整数组/对象或完整 Markdown code fence，拒绝模型额外话术。
 */
class StudyArtifactGenerator(
    private val client: LlmClient = LlmClient(),
    private val streamChat: StudyStreamChat? = null,
) {
    companion object {
        const val CHUNK_SIZE = 4_000
        const val DEFAULT_ITEM_COUNT = 5
        const val MAX_ITEM_COUNT = 20
        const val DEFAULT_TRANSLATION_MAX_CHARS = 2_000
        const val MAX_SOURCE_CHARS = 20_000
        const val ERROR_EMPTY_SOURCE = "EMPTY_SOURCE"
        const val ERROR_EMPTY_RESPONSE = "EMPTY_RESPONSE"
        const val ERROR_INVALID_JSON = "INVALID_JSON"
        const val ERROR_INVALID_REQUEST = "INVALID_REQUEST"
        const val ERROR_OUTPUT_TOO_LONG = "OUTPUT_TOO_LONG"
        const val ERROR_GENERATION = "GENERATION_FAILED"

        private val fencedJson = Regex(
            pattern = "^```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```$",
            option = RegexOption.IGNORE_CASE,
        )

        fun parseFlashcards(raw: String): List<Flashcard> {
            val array = parseArray(raw)
            if (array.length() == 0 || array.length() > MAX_ITEM_COUNT) {
                throw StudyJsonParseException("INVALID_ITEM_COUNT")
            }
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.opt(index) as? JSONObject
                        ?: throw StudyJsonParseException("INVALID_FLASHCARD")
                    requireExactKeys(item, setOf("question", "answer"))
                    add(
                        Flashcard(
                            question = requiredString(item, "question"),
                            answer = requiredString(item, "answer"),
                        ),
                    )
                }
            }
        }

        fun parseQuiz(raw: String): List<QuizQuestion> {
            val array = parseArray(raw)
            if (array.length() == 0 || array.length() > MAX_ITEM_COUNT) {
                throw StudyJsonParseException("INVALID_ITEM_COUNT")
            }
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.opt(index) as? JSONObject
                        ?: throw StudyJsonParseException("INVALID_QUIZ")
                    requireExactKeys(
                        item,
                        setOf("question", "options", "correctIndex", "explanation"),
                    )
                    val optionsJson = item.opt("options") as? JSONArray
                        ?: throw StudyJsonParseException("INVALID_OPTIONS")
                    if (optionsJson.length() !in 2..6) {
                        throw StudyJsonParseException("INVALID_OPTIONS")
                    }
                    val options = buildList {
                        for (optionIndex in 0 until optionsJson.length()) {
                            val value = optionsJson.opt(optionIndex) as? String
                            if (value.isNullOrBlank()) {
                                throw StudyJsonParseException("INVALID_OPTIONS")
                            }
                            add(value)
                        }
                    }
                    if (options.toSet().size != options.size) {
                        throw StudyJsonParseException("DUPLICATE_OPTIONS")
                    }
                    val correctIndex = when (val value = item.opt("correctIndex")) {
                        is Int -> value
                        is Long -> value.toInt().takeIf { value == it.toLong() }
                        else -> null
                    } ?: throw StudyJsonParseException("INVALID_CORRECT_INDEX")
                    if (correctIndex !in options.indices) {
                        throw StudyJsonParseException("INVALID_CORRECT_INDEX")
                    }
                    add(
                        QuizQuestion(
                            question = requiredString(item, "question"),
                            options = options,
                            correctIndex = correctIndex,
                            explanation = requiredString(item, "explanation"),
                        ),
                    )
                }
            }
        }

        /** 只接受 {"translation":"..."}，原文由本地调用者注入。 */
        fun parseTranslation(raw: String): String {
            val objectJson = parseObject(raw)
            requireExactKeys(objectJson, setOf("translation"))
            return requiredString(objectJson, "translation")
        }

        fun encodeFlashcards(cards: List<Flashcard>): String = JSONArray().apply {
            cards.forEach { card ->
                put(JSONObject().put("question", card.question).put("answer", card.answer))
            }
        }.toString()

        fun encodeQuiz(questions: List<QuizQuestion>): String = JSONArray().apply {
            questions.forEach { question ->
                put(
                    JSONObject()
                        .put("question", question.question)
                        .put("options", JSONArray(question.options))
                        .put("correctIndex", question.correctIndex)
                        .put("explanation", question.explanation),
                )
            }
        }.toString()

        fun encodeBilingual(original: String, translation: String?): String = JSONObject()
            .put("original", original)
            .put("translation", translation ?: JSONObject.NULL)
            .toString()

        fun parseBilingual(raw: String): BilingualOutput {
            val item = parseObject(raw)
            requireExactKeys(item, setOf("original", "translation"))
            val original = requiredString(item, "original")
            val translationValue = item.opt("translation")
            val translation = when {
                translationValue == null || translationValue == JSONObject.NULL -> null
                translationValue is String && translationValue.isNotBlank() -> translationValue
                else -> throw StudyJsonParseException("INVALID_translation")
            }
            return BilingualOutput(original, translation)
        }

        private fun jsonPayload(raw: String, expectsArray: Boolean): String {
            val trimmed = raw.trim()
            val payload = if (trimmed.startsWith("```")) {
                fencedJson.matchEntire(trimmed)?.groupValues?.getOrNull(1)?.trim()
                    ?: throw StudyJsonParseException("INVALID_FENCE")
            } else {
                trimmed
            }
            if (payload.isBlank()) throw StudyJsonParseException("EMPTY_JSON")
            val startsCorrectly = if (expectsArray) {
                payload.startsWith("[")
            } else {
                payload.startsWith("{")
            }
            if (!startsCorrectly) throw StudyJsonParseException("INVALID_JSON_SHAPE")
            return payload
        }

        private fun parseArray(raw: String): JSONArray {
            val payload = jsonPayload(raw, expectsArray = true)
            return try {
                val tokener = JSONTokener(payload)
                val value = tokener.nextValue()
                if (value !is JSONArray || tokener.nextClean() != 0.toChar()) {
                    throw StudyJsonParseException("INVALID_JSON")
                }
                value
            } catch (e: StudyJsonParseException) {
                throw e
            } catch (_: Exception) {
                throw StudyJsonParseException("INVALID_JSON")
            }
        }

        private fun parseObject(raw: String): JSONObject {
            val payload = jsonPayload(raw, expectsArray = false)
            return try {
                val tokener = JSONTokener(payload)
                val value = tokener.nextValue()
                if (value !is JSONObject || tokener.nextClean() != 0.toChar()) {
                    throw StudyJsonParseException("INVALID_JSON")
                }
                value
            } catch (e: StudyJsonParseException) {
                throw e
            } catch (_: Exception) {
                throw StudyJsonParseException("INVALID_JSON")
            }
        }

        private fun requireExactKeys(item: JSONObject, expected: Set<String>) {
            val actual = mutableSetOf<String>()
            val keys = item.keys()
            while (keys.hasNext()) actual += keys.next()
            if (actual != expected) throw StudyJsonParseException("INVALID_FIELDS")
        }

        private fun requiredString(item: JSONObject, key: String): String {
            val value = item.opt(key) as? String
            if (value.isNullOrBlank()) throw StudyJsonParseException("INVALID_$key")
            return value
        }
    }

    suspend fun generateFlashcards(
        transcript: String,
        cfg: LlmConfig,
        count: Int = DEFAULT_ITEM_COUNT,
    ): StudyGenerationResult<List<Flashcard>> {
        if (transcript.isBlank()) return StudyGenerationResult.Failed(ERROR_EMPTY_SOURCE)
        if (count !in 1..MAX_ITEM_COUNT) return StudyGenerationResult.Failed(ERROR_INVALID_REQUEST)
        return try {
            val source = prepareSource(transcript, cfg)
            val raw = request(flashcardMessages(source, count), cfg)
            if (raw.isBlank()) {
                StudyGenerationResult.Failed(ERROR_EMPTY_RESPONSE)
            } else {
                val cards = parseFlashcards(raw)
                StudyGenerationResult.Success(cards, encodeFlashcards(cards))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: EmptyStudyResponseException) {
            StudyGenerationResult.Failed(ERROR_EMPTY_RESPONSE)
        } catch (_: StudyJsonParseException) {
            StudyGenerationResult.Failed(ERROR_INVALID_JSON)
        } catch (_: Exception) {
            StudyGenerationResult.Failed(ERROR_GENERATION)
        }
    }

    suspend fun generateQuiz(
        transcript: String,
        cfg: LlmConfig,
        count: Int = DEFAULT_ITEM_COUNT,
    ): StudyGenerationResult<List<QuizQuestion>> {
        if (transcript.isBlank()) return StudyGenerationResult.Failed(ERROR_EMPTY_SOURCE)
        if (count !in 1..MAX_ITEM_COUNT) return StudyGenerationResult.Failed(ERROR_INVALID_REQUEST)
        return try {
            val source = prepareSource(transcript, cfg)
            val raw = request(quizMessages(source, count), cfg)
            if (raw.isBlank()) {
                StudyGenerationResult.Failed(ERROR_EMPTY_RESPONSE)
            } else {
                val questions = parseQuiz(raw)
                StudyGenerationResult.Success(questions, encodeQuiz(questions))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: EmptyStudyResponseException) {
            StudyGenerationResult.Failed(ERROR_EMPTY_RESPONSE)
        } catch (_: StudyJsonParseException) {
            StudyGenerationResult.Failed(ERROR_INVALID_JSON)
        } catch (_: Exception) {
            StudyGenerationResult.Failed(ERROR_GENERATION)
        }
    }

    /** 生成一份课后双语总结；原文不会采用模型回传值。 */
    suspend fun generateBilingualSummary(
        original: String,
        cfg: LlmConfig,
        maxTranslationChars: Int = DEFAULT_TRANSLATION_MAX_CHARS,
    ): StudyGenerationResult<BilingualOutput> =
        generateTranslation(
            original,
            cfg,
            "请将课堂内容压缩成适合复习的中文双语总结",
            maxTranslationChars,
            compressSource = true,
        )

    /** 翻译人工标记的原文；不进入实时转写链。 */
    suspend fun translateMarkedText(
        original: String,
        cfg: LlmConfig,
        maxTranslationChars: Int = DEFAULT_TRANSLATION_MAX_CHARS,
    ): StudyGenerationResult<BilingualOutput> =
        generateTranslation(
            original,
            cfg,
            "请准确翻译以下已标记课堂原文，不要添加原文没有的事实",
            maxTranslationChars,
            compressSource = false,
        )

    private suspend fun generateTranslation(
        original: String,
        cfg: LlmConfig,
        instruction: String,
        maxTranslationChars: Int,
        compressSource: Boolean,
    ): StudyGenerationResult<BilingualOutput> {
        if (original.isBlank()) return StudyGenerationResult.Failed(ERROR_EMPTY_SOURCE)
        if (maxTranslationChars <= 0 || original.length > MAX_SOURCE_CHARS) {
            return StudyGenerationResult.Failed(ERROR_INVALID_REQUEST)
        }
        return try {
            val source = if (compressSource) prepareSource(original, cfg) else original
            val raw = request(translationMessages(source, instruction), cfg)
            if (raw.isBlank()) {
                StudyGenerationResult.Failed(ERROR_EMPTY_RESPONSE)
            } else {
                val translation = parseTranslation(raw)
                if (translation.length > maxTranslationChars) {
                    StudyGenerationResult.Failed(ERROR_OUTPUT_TOO_LONG)
                } else {
                    val output = BilingualOutput(original = original, translation = translation)
                    StudyGenerationResult.Success(output, encodeBilingual(original, translation))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: EmptyStudyResponseException) {
            StudyGenerationResult.Failed(ERROR_EMPTY_RESPONSE)
        } catch (_: StudyJsonParseException) {
            StudyGenerationResult.Failed(ERROR_INVALID_JSON)
        } catch (_: Exception) {
            StudyGenerationResult.Failed(ERROR_GENERATION)
        }
    }

    private suspend fun prepareSource(transcript: String, cfg: LlmConfig): String {
        if (transcript.length <= CHUNK_SIZE) return transcript
        val partials = transcript.chunked(CHUNK_SIZE).map { chunk ->
            val partial = request(compressionMessages(chunk), cfg)
            if (partial.isBlank()) throw EmptyStudyResponseException
            partial
        }
        return partials.joinToString("\n---\n")
    }

    private suspend fun request(messages: List<Map<String, String>>, cfg: LlmConfig): String =
        (streamChat?.invoke(messages, cfg) ?: client.streamChat(messages, cfg))
            .toList()
            .joinToString("")
            .trim()

    private fun flashcardMessages(source: String, count: Int): List<Map<String, String>> = listOf(
        mapOf(
            "role" to "system",
            "content" to "你是课后学习卡片生成器。只输出一个 JSON 数组，不要 Markdown、解释、前后缀话术或代码围栏。" +
                "数组包含 $count 个对象；每个对象只能有 question 和 answer 两个非空字符串字段。" +
                "只根据用户提供的课堂原文，不要编造。",
        ),
        mapOf("role" to "user", "content" to "课堂原文：\n$source"),
    )

    private fun quizMessages(source: String, count: Int): List<Map<String, String>> = listOf(
        mapOf(
            "role" to "system",
            "content" to "你是课后小测生成器。只输出一个 JSON 数组，不要 Markdown、解释、前后缀话术或代码围栏。" +
                "数组包含 $count 个对象；每个对象只能有 question、options、correctIndex、explanation 字段。" +
                "options 是 2 到 6 个不重复非空字符串，correctIndex 是 0 开始的合法整数。只根据原文，不要编造。",
        ),
        mapOf("role" to "user", "content" to "课堂原文：\n$source"),
    )

    private fun translationMessages(source: String, instruction: String): List<Map<String, String>> = listOf(
        mapOf(
            "role" to "system",
            "content" to "你是严谨的课后翻译助手。只输出一个 JSON 对象，且只能有 translation 一个非空字符串字段。" +
                "不要输出 Markdown、解释、原文副本或代码围栏。",
        ),
        mapOf("role" to "user", "content" to "$instruction。\n原文：\n$source"),
    )

    private fun compressionMessages(chunk: String): List<Map<String, String>> = listOf(
        mapOf(
            "role" to "system",
            "content" to "请把一段课堂转写压缩成忠实、简洁的复习要点。只输出要点，不要编造，不要讨论提示词。",
        ),
        mapOf("role" to "user", "content" to "请压缩以下课堂原文：\n$chunk"),
    )

    private object EmptyStudyResponseException : Exception()
}
