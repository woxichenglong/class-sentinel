package com.classsentinel.service

import com.classsentinel.core.detect.ClassEvent
import com.classsentinel.core.llm.AnswerResult
import com.classsentinel.core.pipeline.PipelineState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Current live transcript line; partial hypotheses are never historical. */
sealed interface LiveTranscriptLine {
    val utteranceId: Int
    val text: String

    data class Partial(
        override val utteranceId: Int,
        override val text: String,
        val audioOffsetMs: Long,
    ) : LiveTranscriptLine

    data class Final(
        override val utteranceId: Int,
        override val text: String,
        val startOffsetMs: Long,
        val endOffsetMs: Long,
    ) : LiveTranscriptLine
}

data class LiveAnswerState(
    val eventId: Long,
    val question: String,
    val context: String,
    val timestampMs: Long,
    val result: AnswerResult,
)

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

    private val _transcript = MutableStateFlow<List<LiveTranscriptLine>>(emptyList())
    /** Final history plus the replaceable live partial lines. */
    val transcript: StateFlow<List<LiveTranscriptLine>> = _transcript

    private val _latestAnswer = MutableStateFlow<LiveAnswerState?>(null)
    /** Latest answer status for the live screen; Room remains the history source. */
    val latestAnswer: StateFlow<LiveAnswerState?> = _latestAnswer

    private val _events = MutableStateFlow<List<ClassEvent>>(emptyList())
    /** 最近事件列表（新→旧顺位追加） */
    val events: StateFlow<List<ClassEvent>> = _events

    /** 管线状态（Idle/Listening/Error） */
    val pipelineState = MutableStateFlow<PipelineState>(PipelineState.Idle)

    /** 当前前台课程 id；没有活动课程时为 null，供 Live 的手动标记动作做资格判断。 */
    val activeCourseId = MutableStateFlow<Long?>(null)

    /** 当前课程最后一个已落库转写块 id；落库前保持 null，避免标记到旧句子。 */
    val latestChunkId = MutableStateFlow<Long?>(null)

    /** 新课程开始：清除旧的进程内展示状态，但不篡改管线状态。 */
    fun startCourse(courseId: Long) {
        activeCourseId.value = courseId
        latestChunkId.value = null
        clear()
    }

    /** 只有当前课程的已落库块才能成为“最近一句”。 */
    fun pushLatestChunk(courseId: Long, chunkId: Long) {
        if (activeCourseId.value == courseId) latestChunkId.value = chunkId
    }

    /** 课程完成后释放标记资格，避免下一次误用旧 chunk id。 */
    fun finishCourse(courseId: Long) {
        if (activeCourseId.value == courseId) {
            activeCourseId.value = null
            latestChunkId.value = null
        }
    }

    fun pushSegment(text: String) {
        if (text.isBlank()) return
        _segmentList.value = (_segmentList.value + text).takeLast(MAX)
        _transcript.value = (
            _transcript.value + LiveTranscriptLine.Final(
                utteranceId = LEGACY_UTTERANCE_ID--,
                text = text,
                startOffsetMs = 0L,
                endOffsetMs = 0L,
            )
        ).takeLast(MAX)
        _segments.tryEmit(text)
    }

    /** Replace only the current partial hypothesis for [utteranceId]. */
    fun pushPartial(utteranceId: Int, text: String, offsetMs: Long) {
        if (text.isBlank()) return
        _transcript.value = (
            _transcript.value.filterNot {
                it is LiveTranscriptLine.Partial && it.utteranceId == utteranceId
            } + LiveTranscriptLine.Partial(utteranceId, text, offsetMs)
        ).takeLast(MAX)
    }

    /** Commit one final utterance, remove its partial, and ignore duplicate finals. */
    fun pushFinal(utteranceId: Int, text: String, startOffsetMs: Long, endOffsetMs: Long) {
        if (text.isBlank()) return
        if (_transcript.value.any {
                it is LiveTranscriptLine.Final && it.utteranceId == utteranceId
            }
        ) return
        val line = LiveTranscriptLine.Final(utteranceId, text, startOffsetMs, endOffsetMs)
        _transcript.value = (
            _transcript.value.filterNot {
                it is LiveTranscriptLine.Partial && it.utteranceId == utteranceId
            } + line
        ).takeLast(MAX)
        _segmentList.value = (_segmentList.value + text).takeLast(MAX)
        _segments.tryEmit(text)
    }

    fun pushAnswer(
        eventId: Long,
        question: String,
        context: String,
        timestampMs: Long,
        result: AnswerResult,
    ) {
        _latestAnswer.value = LiveAnswerState(eventId, question, context, timestampMs, result)
    }

    fun pushEvent(event: ClassEvent) {
        _events.value = (_events.value + event).takeLast(MAX)
    }

    fun pushState(state: PipelineState) {
        pipelineState.value = state
    }

    fun clear() {
        _segmentList.value = emptyList()
        _transcript.value = emptyList()
        _latestAnswer.value = null
        _events.value = emptyList()
        LEGACY_UTTERANCE_ID = -1
    }

    private var LEGACY_UTTERANCE_ID = -1
}