package com.classsentinel.core.llm

/** OpenAI 兼容 LLM 配置 */
data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /** deepseek-v4-flash 等推理模型必须关思维链，否则 reasoning 吃满 max_tokens 返回空 content */
    val thinkingDisabled: Boolean = true,
    /** 可选的输出 token 上限；不设置时沿用 provider 默认值。 */
    val maxTokens: Int? = null,
)
