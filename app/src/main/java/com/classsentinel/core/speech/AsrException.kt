package com.classsentinel.core.speech

/**
 * v0.2 Task 4（后半）：携带类型化 [AsrError] 的单段转写失败。
 *
 * 纯值载体：不携带日志、凭证、堆栈或课堂文本副作用；
 * 供 Pipeline/UI 以 [AsrError] 精确决策（retriable / kind）。
 */
class AsrException(val error: AsrError) : Exception(error.message)
