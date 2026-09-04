package com.classsentinel.core.speech

/**
 * v0.2 Task 3：类型化 ASR 错误契约。
 *
 * 纯值对象，不携带日志、凭证或用户文本副作用，供 Pipeline/UI 消费。
 * [retriable] 是语义化重试决策字段，不承载重试次数/延迟策略。
 */
data class AsrError(
    val kind: Kind,
    val retriable: Boolean,
    val message: String = "",
) {
    enum class Kind {
        /** 鉴权失败（401/403 等），重试无意义 */
        AUTH,

        /** 限流（429 等），可稍后重试 */
        RATE_LIMIT,

        /** 网络不可达/连接中断，可重试 */
        NETWORK,

        /** 服务端 5xx 等，可重试 */
        SERVER,

        /** 空识别结果 */
        EMPTY,

        /** 配置错误（引擎未配置/参数非法），重试无意义 */
        CONFIG,

        /** 未知/其他错误，默认不可重试 */
        UNKNOWN,
    }

    companion object {
        /** 由 HTTP 状态码映射错误类型；非法/未知状态统一归为不可重试的 UNKNOWN。 */
        fun fromHttp(code: Int): AsrError = when (code) {
            401, 403 -> AsrError(Kind.AUTH, retriable = false, message = "http $code")
            429 -> AsrError(Kind.RATE_LIMIT, retriable = true, message = "http $code")
            in 500..599 -> AsrError(Kind.SERVER, retriable = true, message = "http $code")
            else -> AsrError(Kind.UNKNOWN, retriable = false, message = "http $code")
        }

        /** 空文本的安全构造（网络/服务端错误不在此列）。 */
        fun emptyText(): AsrError = AsrError(Kind.EMPTY, retriable = false, message = "empty")

        /** 网络错误的安全构造；[cause] 仅作为人类可读描述，不保留堆栈/凭证。 */
        fun network(message: String = "network error"): AsrError =
            AsrError(Kind.NETWORK, retriable = true, message = message)
    }
}
