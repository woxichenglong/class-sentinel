package com.classsentinel.ui.screens

import android.content.Context
import com.classsentinel.core.pipeline.PipelineState
import com.classsentinel.service.ListenService

internal enum class LivePrimaryAction {
    START,
    STOP,
    DISABLED,
}

internal data class LivePrimaryActionUi(
    val label: String,
    val action: LivePrimaryAction,
    val enabled: Boolean,
)

internal fun livePrimaryActionUi(state: PipelineState): LivePrimaryActionUi = when (state) {
    PipelineState.Idle -> LivePrimaryActionUi("开始", LivePrimaryAction.START, enabled = true)
    is PipelineState.Error -> if (state.retryableStop) {
        LivePrimaryActionUi("再次停止", LivePrimaryAction.STOP, enabled = true)
    } else {
        LivePrimaryActionUi("开始", LivePrimaryAction.START, enabled = true)
    }

    PipelineState.Starting,
    is PipelineState.Listening,
    is PipelineState.Recovering,
    -> LivePrimaryActionUi("停止", LivePrimaryAction.STOP, enabled = true)

    PipelineState.Stopping -> LivePrimaryActionUi("正在停止…", LivePrimaryAction.DISABLED, enabled = false)
}

internal fun performLivePrimaryAction(context: Context, action: LivePrimaryAction, requestStart: () -> Unit) {
    when (action) {
        LivePrimaryAction.START -> requestStart()
        LivePrimaryAction.STOP -> ListenService.stop(context)
        LivePrimaryAction.DISABLED -> Unit
    }
}
