package com.classsentinel.service

import com.classsentinel.core.detect.ClassEvent
import com.classsentinel.core.pipeline.PipelineState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 实时监听总线（进程内单例）。
 *
 * ListenService 目前只暴露 ACTION_START/STOP，不对外流式输出；
 * 本总线作为监听数据的 UI 侧落点：转写句、事件、管线状态都汇聚到这里，
 * Home/Live 屏收集展示，自检页「模拟事件」也推到这里做全链路演练。
 * 后续 ListenService 接入时在其转写/事件出口调用 push* 即可，UI 无需改动。
 */
object LiveStreamBus {

    private const val MAX = 100
    private const val BUFFER = 32

    /** 逐句转写（瞬时流，无订阅者时丢旧） */
    private val _segments: MutableSharedFlow<String> =
        MutableSharedFlow(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 瞬时转写流（UI 收集用） */
    val segments: SharedFlow<String> = _segments

    private val _segmentList = MutableStateFlow<List<String>>(emptyList())
    /** 转写句历史（含推送期间未订阅的句子，UI 用） */
    val segmentList: StateFlow<List<String>> = _segmentList

    private val _events = MutableStateFlow<List<ClassEvent>>(emptyList())
    /** 最近事件列表（新→旧顺位追加） */
    val events: StateFlow<List<ClassEvent>> = _events

    /** 管线状态（Idle/Listening/Error） */
    val pipelineState = MutableStateFlow<PipelineState>(PipelineState.Idle)

    fun pushSegment(text: String) {
        if (text.isBlank()) return
        _segmentList.value = (_segmentList.value + text).takeLast(MAX)
        _segments.tryEmit(text)
    }

    fun pushEvent(event: ClassEvent) {
        _events.value = (_events.value + event).takeLast(MAX)
    }

    fun pushState(state: PipelineState) {
        pipelineState.value = state
    }

    fun clear() {
        _segmentList.value = emptyList()
        _events.value = emptyList()
    }
}