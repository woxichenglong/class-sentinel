package com.classsentinel.core.pipeline

import com.classsentinel.core.audio.AudioStreamer
import com.classsentinel.core.speech.StreamingAsrEvent
import com.classsentinel.core.speech.StreamingSpeechEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Continuous live-listening pipeline. It owns only PCM collection, streaming
 * ASR events, and safe lifecycle state; persistence and question handling belong
 * to the service adapter.
 */
internal class StreamingListenPipeline(
    private val streamer: AudioStreamer,
    private val speech: StreamingSpeechEngine,
    private val onEvent: suspend (StreamingAsrEvent) -> Unit = {},
    private val onFinal: suspend (StreamingAsrEvent.Final) -> Unit = {},
    private val onStateChanged: (PipelineState) -> Unit = {},
) {
    private val _state = MutableStateFlow<PipelineState>(PipelineState.Idle)
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<StreamingAsrEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<StreamingAsrEvent> = _events.asSharedFlow()

    @Volatile
    private var collectorJob: Job? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var terminalFailure = false

    @Volatile
    var finalCount: Int = 0
        private set

    fun start(scope: CoroutineScope): Job {
        collectorJob?.takeIf { it.isActive }?.let { return it }
        synchronized(this) {
            collectorJob?.takeIf { it.isActive }?.let { return it }
            streamer.prepareForCapture()
            stopRequested = false
            terminalFailure = false
            finalCount = 0
            publishState(PipelineState.Starting)
            val job = scope.launch {
                try {
                    speech.transcribe(streamer.pcm()).collect { event ->
                        _events.emit(event)
                        onEvent(event)
                        when (event) {
                            is StreamingAsrEvent.Partial -> {
                                publishState(
                                    PipelineState.Listening(
                                        sentences = finalCount,
                                        engine = speech.name,
                                    ),
                                )
                            }

                            is StreamingAsrEvent.Final -> {
                                finalCount++
                                onFinal(event)
                                publishState(
                                    PipelineState.Listening(
                                        sentences = finalCount,
                                        engine = speech.name,
                                    ),
                                )
                            }

                            is StreamingAsrEvent.UtteranceEnded -> Unit

                            is StreamingAsrEvent.EngineChanged -> {
                                publishState(
                                    PipelineState.Listening(
                                        sentences = finalCount,
                                        engine = event.engine,
                                    ),
                                )
                            }

                            is StreamingAsrEvent.Recovering -> {
                                publishState(
                                    PipelineState.Recovering(
                                        engine = speech.name,
                                        message = event.reason,
                                    ),
                                )
                            }

                            is StreamingAsrEvent.Failed -> {
                                terminalFailure = true
                                publishState(PipelineState.Error("转写中断"))
                                throw TerminalFailure()
                            }
                        }
                    }
                    // A live PCM/ASR stream should end only through stop/cancel.
                    // Natural completion is therefore a visible interruption.
                    if (!stopRequested && !terminalFailure) {
                        publishState(PipelineState.Error("转写中断"))
                    }
                } catch (_: TerminalFailure) {
                    // Failed already published the safe terminal Error state.
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    terminalFailure = true
                    publishState(PipelineState.Error("转写中断"))
                }
            }
            collectorJob = job
            return job
        }
    }

    suspend fun stop() {
        val job = synchronized(this) {
            collectorJob?.takeIf { it.isActive }?.also { stopRequested = true }
        } ?: return
        publishState(PipelineState.Stopping)
        streamer.stop()
        job.join()
        synchronized(this) {
            if (collectorJob === job) collectorJob = null
        }
        publishState(PipelineState.Idle)
    }

    private fun publishState(value: PipelineState) {
        _state.value = value
        onStateChanged(value)
    }

    private class TerminalFailure : RuntimeException()
}
