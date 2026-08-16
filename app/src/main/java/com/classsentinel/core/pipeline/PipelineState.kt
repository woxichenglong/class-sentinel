package com.classsentinel.core.pipeline

sealed interface PipelineState {
    object Idle : PipelineState
    data class Listening(val sentences: Int) : PipelineState
    data class Error(val message: String) : PipelineState
}
