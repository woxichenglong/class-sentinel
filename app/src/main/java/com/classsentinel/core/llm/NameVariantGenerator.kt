package com.classsentinel.core.llm

import com.classsentinel.data.AiSettings
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** 可替换的结构化姓名容错请求 seam；生产实现复用 [LlmClient]。 */
typealias NameVariantStreamChat = (List<Map<String, String>>, LlmConfig) -> Flow<String>

/** 首启姓名容错生成边界；调用者永远不会接触 LLM 自由文本。 */
interface NameVariantGenerator {
    suspend fun generate(displayName: String): NameVariantGenerationResult
}

enum class NameVariantFailureCode {
    INVALID_INPUT,
    CONFIG,
    AUTH,
    FORBIDDEN,
    NOT_FOUND,
    MODEL_UNSUPPORTED,
    RATE_LIMIT,
    QUOTA_EXHAUSTED,
    DNS,
    NETWORK,
    SERVER,
    TIMEOUT,
    EMPTY_RESPONSE,
    INVALID_JSON,
    UNKNOWN,
}

sealed interface NameVariantGenerationResult {
    data class Success(val asrVariants: List<String>) : NameVariantGenerationResult
    data class Failure(val code: NameVariantFailureCode) : NameVariantGenerationResult
}

/**
 * LLM 返回值的本地安全门。
 * 这里不做姓名匹配，只限制输出形状和明显不属于 ASR 候选的内容。
 */
object NameVariantSanitizer {
    const val MAX_VARIANTS = 10
    const val MAX_VARIANT_LENGTH = 12
    private const val MAX_LENGTH_DELTA = 1

    private val rejectedTokens = listOf(
        "先生",
        "女士",
        "老师",
        "同学",
        "昵称",
        "别名",
        "英文名",
        "英文",
        "金刚",
        "今天",
        "回答",
        "名字",
        "姓名",
    )

    fun sanitize(displayName: String, candidates: List<String>): List<String> {
        val display = displayName.trim()
        if (display.isBlank()) return emptyList()

        val minLength = maxOf(1, display.length - MAX_LENGTH_DELTA)
        val maxLength = minOf(MAX_VARIANT_LENGTH, display.length + MAX_LENGTH_DELTA)
        if (maxLength < minLength) return emptyList()

        return candidates.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filter { it != display }
            .filter { it.length in minLength..maxLength }
            .filter { candidate -> candidate.all { it.isLetter() } }
            .filterNot { candidate -> isObviousNonAsr(candidate, display) }
            .distinct()
            .take(MAX_VARIANTS)
            .toList()
    }

    private fun isObviousNonAsr(candidate: String, display: String): Boolean {
        if (rejectedTokens.any(candidate::contains)) return true
        if (candidate != display &&
            (candidate.startsWith("小") || candidate.startsWith("阿")) &&
            !display.startsWith(candidate.take(1))
        ) {
            return true
        }
        // 包含完整姓名或只是姓名的一段，通常是称呼/句子而不是同音误写。
        return candidate.contains(display) || display.contains(candidate)
    }
}

/** 复用现有 OpenAI-compatible 配置和 LLM 网络层的姓名变体生成器。 */
class LlmNameVariantGenerator(
    private val settingsProvider: suspend () -> AiSettings,
    private val streamChat: NameVariantStreamChat? = null,
    private val client: LlmClient = LlmClient(),
) : NameVariantGenerator {

    override suspend fun generate(displayName: String): NameVariantGenerationResult {
        val display = displayName.trim()
        if (display.isBlank()) {
            return NameVariantGenerationResult.Failure(NameVariantFailureCode.INVALID_INPUT)
        }

        return try {
            val settings = AiProviderPreset.normalizeSettings(settingsProvider())
            if (settings.apiKey.isBlank()) {
                NameVariantGenerationResult.Failure(NameVariantFailureCode.CONFIG)
            } else {
                val config = LlmConfig(
                    baseUrl = settings.baseUrl,
                    apiKey = settings.apiKey,
                    model = settings.model,
                    thinkingDisabled = true,
                    maxTokens = 256,
                    responseFormatJsonObject = true,
                )
                val raw = withTimeoutOrNull(NAME_GENERATION_TIMEOUT_MS) {
                    request(nameVariantMessages(display), config)
                }
                if (raw == null) {
                    NameVariantGenerationResult.Failure(NameVariantFailureCode.TIMEOUT)
                } else if (raw.isBlank()) {
                    NameVariantGenerationResult.Failure(NameVariantFailureCode.EMPTY_RESPONSE)
                } else {
                    NameVariantGenerationResult.Success(parseResponse(raw, display))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            NameVariantGenerationResult.Failure(e.error.toFailureCode())
        } catch (e: NameVariantParseException) {
            NameVariantGenerationResult.Failure(e.code)
        } catch (_: IOException) {
            NameVariantGenerationResult.Failure(NameVariantFailureCode.NETWORK)
        } catch (_: Exception) {
            NameVariantGenerationResult.Failure(NameVariantFailureCode.UNKNOWN)
        }
    }

    private suspend fun request(
        messages: List<Map<String, String>>,
        config: LlmConfig,
    ): String = (streamChat?.invoke(messages, config) ?: client.streamChat(messages, config))
        .toList()
        .joinToString("")
        .trim()

    private fun parseResponse(raw: String, display: String): List<String> {
        val value = try {
            val tokener = JSONTokener(raw.trim())
            val parsed = tokener.nextValue()
            if (parsed !is JSONObject || tokener.nextClean() != 0.toChar()) {
                throw NameVariantParseException(NameVariantFailureCode.INVALID_JSON)
            }
            parsed
        } catch (e: NameVariantParseException) {
            throw e
        } catch (_: Exception) {
            throw NameVariantParseException(NameVariantFailureCode.INVALID_JSON)
        }

        val keys = mutableSetOf<String>()
        val iterator = value.keys()
        while (iterator.hasNext()) keys += iterator.next()
        if (keys != setOf("asrVariants")) {
            throw NameVariantParseException(NameVariantFailureCode.INVALID_JSON)
        }

        val array = value.opt("asrVariants") as? JSONArray
            ?: throw NameVariantParseException(NameVariantFailureCode.INVALID_JSON)
        val candidates = buildList {
            for (index in 0 until array.length()) {
                val candidate = array.opt(index) as? String
                    ?: throw NameVariantParseException(NameVariantFailureCode.INVALID_JSON)
                add(candidate)
            }
        }
        return NameVariantSanitizer.sanitize(display, candidates).ifEmpty {
            throw NameVariantParseException(NameVariantFailureCode.EMPTY_RESPONSE)
        }
    }

    private fun nameVariantMessages(display: String): List<Map<String, String>> = listOf(
        mapOf(
            "role" to "system",
            "content" to "根据普通话发音、中文同音/近音字和常见语音识别混淆，为给定中文姓名生成可能被 ASR 错误转写的文本候选。\n" +
                "这些候选仅用于 ASR 容错。强宁缺毋滥，只生成高可能的 ASR 转写错误，不要为了凑数量生成低可信候选。\n" +
                "禁止生成：昵称、别名、外号、英文名、同义词、人物联想、意义相关词、与姓名发音差异明显的文本。\n" +
                "只输出严格 JSON 对象，不要 Markdown、代码围栏、解释或前后缀文本；对象只能有 asrVariants 字段，值是最多 10 个字符串的数组。",
        ),
        mapOf(
            "role" to "user",
            "content" to "姓名：$display\n请只返回 JSON，例如：{\"asrVariants\":[\"同音或近音误写\"]}。",
        ),
    )

    private class NameVariantParseException(
        val code: NameVariantFailureCode,
    ) : Exception()

    private companion object {
        const val NAME_GENERATION_TIMEOUT_MS = 15_000L
    }
}

private fun LlmError.toFailureCode(): NameVariantFailureCode = when (kind) {
    LlmError.Kind.AUTH -> NameVariantFailureCode.AUTH
    LlmError.Kind.FORBIDDEN -> NameVariantFailureCode.FORBIDDEN
    LlmError.Kind.NOT_FOUND -> NameVariantFailureCode.NOT_FOUND
    LlmError.Kind.MODEL_UNSUPPORTED -> NameVariantFailureCode.MODEL_UNSUPPORTED
    LlmError.Kind.CONFIG -> NameVariantFailureCode.CONFIG
    LlmError.Kind.RATE_LIMIT -> NameVariantFailureCode.RATE_LIMIT
    LlmError.Kind.QUOTA_EXHAUSTED -> NameVariantFailureCode.QUOTA_EXHAUSTED
    LlmError.Kind.DNS -> NameVariantFailureCode.DNS
    LlmError.Kind.NETWORK -> NameVariantFailureCode.NETWORK
    LlmError.Kind.TIMEOUT -> NameVariantFailureCode.TIMEOUT
    LlmError.Kind.SERVER -> NameVariantFailureCode.SERVER
    LlmError.Kind.EMPTY -> NameVariantFailureCode.EMPTY_RESPONSE
    LlmError.Kind.INVALID_RESPONSE -> NameVariantFailureCode.INVALID_JSON
    LlmError.Kind.UNKNOWN -> NameVariantFailureCode.UNKNOWN
}
