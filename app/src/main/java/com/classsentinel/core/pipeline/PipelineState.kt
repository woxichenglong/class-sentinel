package com.classsentinel.core.pipeline

/**
 * 监听管线状态契约。
 *
 * 只定义状态数据，不包含任何转写文本、凭证或敏感信息；
 * [Error.message] / [Recovering.message] 仅允许存放不含原始课堂文本的简短描述。
 */
sealed interface PipelineState {
    object Idle : PipelineState
    object Starting : PipelineState
    object Stopping : PipelineState

    /**
     * 监听中。
     * [sentences] 必须保持第一个构造参数以兼容既有调用（如 `Listening(2)`、`Listening(1)`）；
     * [engine]、[elapsedMs]、[pendingSegments] 提供安全默认值，供后续小批接入状态转换。
     */
    data class Listening(
        val sentences: Int,
        val engine: String = "",
        val elapsedMs: Long = 0L,
        val pendingSegments: Int = 0,
    ) : PipelineState

    /** 引擎故障后自动恢复中（不带凭证、不带课堂原文）。 */
    data class Recovering(
        val engine: String,
        val message: String,
    ) : PipelineState

    data class Error(val message: String) : PipelineState
}
