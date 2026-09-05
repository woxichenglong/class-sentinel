package com.classsentinel.core.llm

/** Provider errors safe to cross the generator/Worker boundary. */
data class LlmError(
    val kind: Kind,
) {
    enum class Kind {
        AUTH,
        CONFIG,
        RATE_LIMIT,
        NETWORK,
        SERVER,
        EMPTY,
        UNKNOWN,
    }

    val safeCode: String get() = kind.name
}

/** Typed LLM failure with no provider body, credential, or classroom content. */
class LlmException(
    val error: LlmError,
) : Exception(error.safeCode)
