package com.classsentinel.ui

import com.classsentinel.core.pipeline.PipelineState

fun PipelineState.isSessionActive(): Boolean = when (this) {
    PipelineState.Idle, is PipelineState.Error -> false
    PipelineState.Starting,
    is PipelineState.Listening,
    is PipelineState.Recovering,
    PipelineState.Stopping,
    -> true
}
