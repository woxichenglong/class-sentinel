package com.classsentinel.core.llm

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Provider errors safe to cross the generator/Worker boundary. */
data class LlmError(
    val kind: Kind,
    /** Bounded, non-sensitive retry hint from Retry-After; never included in the message. */
    val retryAfterMs: Long? = null,
) {
    enum class Kind {
        AUTH,
        FORBIDDEN,
        NOT_FOUND,
        MODEL_UNSUPPORTED,
        CAPABILITY_UNSUPPORTED,
        CONFIG,
        RATE_LIMIT,
        QUOTA_EXHAUSTED,
        DNS,
        NETWORK,
        TIMEOUT,
        SERVER,
        EMPTY,
        INVALID_RESPONSE,
        UNKNOWN,
    }

    val safeCode: String get() = kind.name
    val retryable: Boolean get() = kind.isRetryable
}

/** Typed LLM failure with no provider body, credential, or classroom content. */
class LlmException(
    val error: LlmError,
) : Exception(error.safeCode)

internal val LlmError.Kind.isRetryable: Boolean
    get() = when (this) {
        LlmError.Kind.DNS,
        LlmError.Kind.NETWORK,
        LlmError.Kind.TIMEOUT,
        LlmError.Kind.RATE_LIMIT,
        LlmError.Kind.SERVER,
        -> true
        else -> false
    }

/** Classify only the transport layer; HTTP status mapping is handled by [LlmClient]. */
internal fun classifyTransportException(error: IOException): LlmError.Kind {
    val seen = HashSet<Throwable>()
    var current: Throwable? = error
    while (current != null && seen.add(current)) {
        when (current) {
            is UnknownHostException -> return LlmError.Kind.DNS
            is SocketTimeoutException,
            is InterruptedIOException,
            -> return LlmError.Kind.TIMEOUT
        }
        current = current.cause
    }
    return LlmError.Kind.NETWORK
}
