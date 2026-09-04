package com.classsentinel.core.llm

import com.classsentinel.data.AiSettings
import java.net.URI

/**
 * OpenAI-compatible provider preset used by settings and onboarding.
 * The API key is deliberately supplied by the caller; presets never copy a key
 * from another provider or from ASR settings.
 */
data class AiProviderPreset(
    val label: String,
    val baseUrl: String,
    val model: String,
    val thinkingDisabled: Boolean = true,
) {

    /** Convert this preset to persisted AI settings with an explicitly supplied key. */
    fun toAiSettings(apiKey: String = ""): AiSettings =
        normalizeSettings(AiSettings(baseUrl = baseUrl, apiKey = apiKey, model = model))

    /** Convert this preset to the runtime LLM configuration. */
    fun toLlmConfig(apiKey: String = ""): LlmConfig {
        val settings = toAiSettings(apiKey)
        return LlmConfig(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            model = settings.model,
            thinkingDisabled = thinkingDisabled,
        )
    }

    companion object {
        val DEEPSEEK_OFFICIAL = AiProviderPreset(
            label = "DeepSeek 官方",
            baseUrl = "https://api.deepseek.com",
            model = "deepseek-v4-flash",
            thinkingDisabled = true,
        )

        val SILICON_FLOW = AiProviderPreset(
            label = "硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            model = "deepseek-ai/DeepSeek-V4-Flash",
            thinkingDisabled = true,
        )

        val COMMAND_CODE = AiProviderPreset(
            label = "Command Code",
            baseUrl = "https://api.commandcode.ai/provider/v1",
            model = "deepseek/deepseek-v4-flash",
            thinkingDisabled = true,
        )

        val BUILT_INS = listOf(DEEPSEEK_OFFICIAL, SILICON_FLOW, COMMAND_CODE)

        /** Returns null when the endpoint and model are valid for an AI setting. */
        fun validationError(baseUrl: String, model: String): String? {
            val normalizedUrl = baseUrl.trim()
            if (!isHttpsUrl(normalizedUrl)) return "BASE_URL_HTTPS_REQUIRED"
            if (model.trim().isBlank()) return "MODEL_BLANK"
            return null
        }

        fun isValid(baseUrl: String, model: String): Boolean =
            validationError(baseUrl, model) == null

        /** Trim user whitespace and trailing slash while preserving the endpoint path. */
        fun normalizeBaseUrl(baseUrl: String): String {
            val normalized = baseUrl.trim().trimEnd('/')
            require(isHttpsUrl(normalized)) { "AI base URL must use https://" }
            return normalized
        }

        /** Normalize all user-editable fields at the repository boundary. */
        fun normalizeSettings(settings: AiSettings): AiSettings {
            val error = validationError(settings.baseUrl, settings.model)
            require(error == null) { error ?: "INVALID_AI_SETTINGS" }
            return settings.copy(
                baseUrl = normalizeBaseUrl(settings.baseUrl),
                apiKey = settings.apiKey.trim(),
                model = settings.model.trim(),
            )
        }

        private fun isHttpsUrl(value: String): Boolean {
            val uri = runCatching { URI(value) }.getOrNull() ?: return false
            return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
        }
    }
}

/** Named collection alias for call sites that prefer a plural provider catalog. */
object AiProviderPresets {
    val DEEPSEEK_OFFICIAL = AiProviderPreset.DEEPSEEK_OFFICIAL
    val SILICON_FLOW = AiProviderPreset.SILICON_FLOW
    val COMMAND_CODE = AiProviderPreset.COMMAND_CODE
    val BUILT_INS = AiProviderPreset.BUILT_INS
}
