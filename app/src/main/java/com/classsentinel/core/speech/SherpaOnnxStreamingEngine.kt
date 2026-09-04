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
    private val sampleRate: Int = 16_000,
) : StreamingSpeechEngine {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
    }

    override val name: String = "sherpa-onnx"

    override fun transcribe(pcm: Flow<ShortArray>): Flow<StreamingAsrEvent> = flow {
        var recognizer: SherpaOnlineRecognizerPort? = null
        var stream: SherpaOnlineStreamPort? = null
        var totalSamples = 0L
        var utteranceId = 1
        var utteranceStartOffsetMs = 0L
        var lastPartialText: String? = null
        var hasPendingAudio = false
        var inputFinished = false

        try {
            recognizer = recognizerFactory()
            stream = recognizer.createStream()
            val activeStream = stream

            pcm.collect { chunk ->
                if (chunk.isEmpty()) return@collect

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
            }

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
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emit(StreamingAsrEvent.Failed(StreamingAsrErrorKind.ASR_RUNTIME))
        } finally {
            stream?.let { activeStream ->
                if (!inputFinished) runCatching { activeStream.inputFinished() }
                runCatching { activeStream.release() }
            }
            recognizer?.let { activeRecognizer ->
                runCatching { activeRecognizer.release() }
            }
        }
    }
}
