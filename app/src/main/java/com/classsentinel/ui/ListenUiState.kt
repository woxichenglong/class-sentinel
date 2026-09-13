package com.classsentinel.ui

import com.classsentinel.core.pipeline.PipelineState

fun PipelineState.isSessionActive(): Boolean = when (this) {
    PipelineState.Idle -> false
    is PipelineState.Error -> retryableStop
    PipelineState.Starting,
    is PipelineState.Listening,
    is PipelineState.Recovering,
    PipelineState.Stopping,
    -> true
}
