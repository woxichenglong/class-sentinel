package com.classsentinel.core.speech

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Continuous sherpa-onnx recognizer adapter.
 *
 * One recognizer stream is kept for the whole PCM flow. An endpoint only closes
 * the current utterance; the flow remains alive for the next classroom utterance.
 */
internal class SherpaOnnxStreamingEngine(
    private val recognizerFactory: () -> SherpaOnlineRecognizerPort,
    private val profile: ModelProfile = ModelProfiles.ZIPFORMER_ZH_14M,
    private val nowNanos: () -> Long = System::nanoTime,
) : ProfileBoundStreamingSpeechEngine, ReplayTimingSource {
    override val modelProfileId: String = profile.id
    override val sampleRate: Int = profile.recognizer.sampleRate

    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
    }

    override val name: String = "sherpa-onnx"

    @Volatile
    override var lastReplayTimings: StreamingAsrTimings? = null

    override fun transcribe(pcm: Flow<ShortArray>): Flow<StreamingAsrEvent> = flow {
        var recognizer: SherpaOnlineRecognizerPort? = null
        var stream: SherpaOnlineStreamPort? = null
        var totalSamples = 0L
        var utteranceId = 1
        var utteranceStartOffsetMs = 0L
        var lastPartialText: String? = null
        var hasPendingAudio = false
        var inputFinished = false
        var recognizerInitMs: Long? = null
        var decodeElapsedNanos = 0L
        var decodeStarted = false
        lastReplayTimings = null

        try {
            val recognizerInitStartedAtNanos = nowNanos()
            recognizer = recognizerFactory()
            stream = recognizer.createStream()
            recognizerInitMs = elapsedMs(recognizerInitStartedAtNanos)
            decodeStarted = true
            val activeStream = stream

            pcm.collect { chunk ->
                if (chunk.isEmpty()) return@collect

                val decodeChunkStartedAtNanos = nowNanos()
                try {
                    val normalized = FloatArray(chunk.size) { index ->
                        chunk[index] / 32_768.0f
                    }
                    activeStream.acceptWaveform(normalized, sampleRate)
                    totalSamples += chunk.size.toLong()
                    hasPendingAudio = true

                    while (activeStream.isReady()) {
                        activeStream.decode()
                    }

                    val text = activeStream.resultText()
                    val offsetMs = totalSamples * 1_000L / sampleRate
                    if (activeStream.isEndpoint()) {
                        if (text.isNotBlank()) {
                            emit(
                                StreamingAsrEvent.Final(
                                    utteranceId = utteranceId,
                                    text = text,
                                    startOffsetMs = utteranceStartOffsetMs,
                                    endOffsetMs = offsetMs,
                                ),
                            )
                        }
                        activeStream.reset()
                        utteranceId++
                        utteranceStartOffsetMs = offsetMs
                        lastPartialText = null
                        hasPendingAudio = false
                    } else if (text.isNotBlank() && text != lastPartialText) {
                        emit(
                            StreamingAsrEvent.Partial(
                                utteranceId = utteranceId,
                                text = text,
                                audioOffsetMs = offsetMs,
                            ),
                        )
                        lastPartialText = text
                    }
                } finally {
                    decodeElapsedNanos += elapsedNanos(decodeChunkStartedAtNanos)
                }
            }

            val flushStartedAtNanos = nowNanos()
            try {
                activeStream.inputFinished()
                inputFinished = true
                if (hasPendingAudio) {
                    while (activeStream.isReady()) {
                        activeStream.decode()
                    }
                    val finalText = activeStream.resultText()
                    val endOffsetMs = totalSamples * 1_000L / sampleRate
                    if (finalText.isNotBlank()) {
                        emit(
                            StreamingAsrEvent.Final(
                                utteranceId = utteranceId,
                                text = finalText,
                                startOffsetMs = utteranceStartOffsetMs,
                                endOffsetMs = endOffsetMs,
                            ),
                        )
                    }
                }
            } finally {
                decodeElapsedNanos += elapsedNanos(flushStartedAtNanos)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emit(StreamingAsrEvent.Failed(StreamingAsrErrorKind.ASR_RUNTIME))
        } finally {
            lastReplayTimings = StreamingAsrTimings(
                recognizerInitMs = recognizerInitMs,
                decodeElapsedMs = if (decodeStarted) decodeElapsedNanos / 1_000_000L else null,
            )
            stream?.let { activeStream ->
                if (!inputFinished) runCatching { activeStream.inputFinished() }
                runCatching { activeStream.release() }
            }
            recognizer?.let { activeRecognizer ->
                runCatching { activeRecognizer.release() }
            }
        }
    }

    private fun elapsedNanos(startedAtNanos: Long): Long =
        (nowNanos() - startedAtNanos).coerceAtLeast(0L)

    private fun elapsedMs(startedAtNanos: Long): Long = elapsedNanos(startedAtNanos) / 1_000_000L
}
