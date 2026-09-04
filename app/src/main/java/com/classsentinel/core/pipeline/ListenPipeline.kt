package com.classsentinel.core.pipeline

import com.classsentinel.core.audio.AudioStreamer
import com.classsentinel.core.speech.SegmentAwareSpeechEngine
import com.classsentinel.core.speech.SpeechEngine
import com.classsentinel.core.speech.TranscribedSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 监听管道：PCM → 引擎 → 句子文本流。
 * 只负责串联与状态呈现；引擎自身异常由 FallbackSpeechEngine 重连消化。
 *
 * 状态转换契约（M2a-2）：
 *  - [start] 在真正启动 collector 前同步发布 [PipelineState.Starting]，同一 pipeline 重复 start 不重复创建 collector；
 *  - 每发布一句文本 → [PipelineState.Listening]（sentences 递增、engine=引擎名、elapsedMs 基于可注入时钟且不倒退、pendingSegments=0）；
 *  - 输入流正常结束 → [PipelineState.Idle]；异常 → [PipelineState.Error]；
 *  - [CancellationException] 一律原样向上传播，绝不转成 Error（取消不是故障）；
 *  - [stop] 幂等：有活动任务时 Stopping → cancelAndJoin → Idle，无任务时直接返回。
 */
class ListenPipeline(
    private val streamer: AudioStreamer,
    private val speech: SpeechEngine,
    /** 可注入时钟（ms）。默认取系统时钟；测试注入虚拟时钟保证确定性。 */
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** 每次管线状态变化的同步通知；生产接线用它把状态交给 UI/通知总线。 */
    private val onStateChanged: (PipelineState) -> Unit = {},
) {
    private val _state = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    private val _segments = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val segments: SharedFlow<String> = _segments

    private val _transcribedSegments = MutableSharedFlow<TranscribedSegment>(extraBufferCapacity = 16)
    /** 生产段路径的元数据出口；旧文本出口 [segments] 保持兼容。 */
    val transcribedSegments: SharedFlow<TranscribedSegment> = _transcribedSegments

    /** 当前引擎是否能提供精确的 WAV 段 id/offset；用于生产接线选择元数据出口。 */
    val supportsSegmentMetadata: Boolean = speech is SegmentAwareSpeechEngine

    @Volatile
    private var collectorJob: Job? = null

    private var sessionStartedAtMs: Long = 0L
    private var lastElapsedMs: Long = 0L
    private var sentences = 0

    fun start(scope: CoroutineScope): Job {
        collectorJob?.takeIf { it.isActive }?.let { return it }
        synchronized(this) {
            collectorJob?.takeIf { it.isActive }?.let { return it }
            sessionStartedAtMs = nowMillis()
            lastElapsedMs = 0L
            sentences = 0
            // 同步先发布 Starting，再启动 collector：避免 Unconfined/立即执行下
            // collector 先完成（Listening/Idle）后迟到的 Starting 覆盖真实状态。
            publishState(PipelineState.Starting)
            val job = scope.launch {
                try {
                    val segmentAware = speech as? SegmentAwareSpeechEngine
                    if (segmentAware != null) {
                        segmentAware.transcribeSegments(streamer.pcm()).collect { transcribed ->
                            publishText(transcribed.text)
                            _transcribedSegments.emit(transcribed)
                        }
                    } else {
                        speech.transcribe(streamer.pcm()).collect { text ->
                            publishText(text)
                        }
                    }
                    publishState(PipelineState.Idle)
                } catch (e: CancellationException) {
                    throw e // 取消不是故障：原样向上传播，不得转成 Error
                } catch (e: Exception) {
                    publishState(PipelineState.Error("转写中断"))
                }
            }
            collectorJob = job
            return job
        }
    }

    private fun publishState(state: PipelineState) {
        _state.value = state
        onStateChanged(state)
    }

    private suspend fun publishText(text: String) {
        sentences++
        lastElapsedMs = (nowMillis() - sessionStartedAtMs).coerceAtLeast(lastElapsedMs)
        publishState(
            PipelineState.Listening(
                sentences = sentences,
                engine = speech.name,
                elapsedMs = lastElapsedMs,
                pendingSegments = 0,
            ),
        )
        _segments.emit(text)
    }

    /**
     * 停止监听：有活动任务时 Stopping → cancelAndJoin → Idle；
     * 无活动任务或重复 stop 幂等，不抛错；停止不产生 Error。
     */
    suspend fun stop() {
        val job = collectorJob?.takeIf { it.isActive } ?: return
        publishState(PipelineState.Stopping)
        job.cancelAndJoin()
        collectorJob = null
        publishState(PipelineState.Idle)
    }
}
