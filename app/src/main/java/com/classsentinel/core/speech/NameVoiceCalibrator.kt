package com.classsentinel.core.speech

import android.content.Context
import com.classsentinel.core.audio.AudioCaptureException
import com.classsentinel.core.audio.AudioStreamer
import com.classsentinel.core.detect.NameEntry
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Onboarding 专用姓名语音采样边界；不进入课堂事件或转写持久化链。 */
interface NameVoiceCalibrator {
    suspend fun captureOnce(expectedDisplayName: String): NameCalibrationAttempt
}

sealed interface NameCalibrationAttempt {
    data class Success(val finalTranscript: String) : NameCalibrationAttempt
    data class Failure(val reason: NameCalibrationFailure) : NameCalibrationAttempt
}

enum class NameCalibrationFailure {
    INVALID_INPUT,
    MICROPHONE,
    MODEL_UNAVAILABLE,
    TIMEOUT,
    EMPTY_TRANSCRIPT,
    ASR_RUNTIME,
    UNKNOWN,
}

/** AudioStreamer 的最小可测试包装；实际 PCM 仍由 AudioRecord 在 AudioStreamer 内管理。 */
internal interface NameCalibrationAudioCapture {
    fun prepareForCapture()
    fun pcm(): Flow<ShortArray>
    fun stop()
}

/**
 * 一次短姓名 utterance 的 X480 实现。
 * readiness、AudioRecord、sherpa stream 都属于本次 attempt，finally 统一停止采集。
 */
internal class X480NameVoiceCalibrator(
    private val ensureReady: suspend () -> Boolean,
    private val audioFactory: () -> NameCalibrationAudioCapture,
    private val modelDirectory: () -> File,
    private val engineFactory: (File) -> ProfileBoundStreamingSpeechEngine,
    private val attemptTimeoutMs: Long = DEFAULT_ATTEMPT_TIMEOUT_MS,
) : NameVoiceCalibrator {

    init {
        require(attemptTimeoutMs > 0L) { "attempt timeout must be positive" }
    }

    override suspend fun captureOnce(expectedDisplayName: String): NameCalibrationAttempt {
        if (expectedDisplayName.trim().isBlank()) {
            return NameCalibrationAttempt.Failure(NameCalibrationFailure.INVALID_INPUT)
        }

        val ready = try {
            ensureReady()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        if (!ready) {
            return NameCalibrationAttempt.Failure(NameCalibrationFailure.MODEL_UNAVAILABLE)
        }

        val audio = try {
            audioFactory()
        } catch (_: Exception) {
            return NameCalibrationAttempt.Failure(NameCalibrationFailure.MICROPHONE)
        }

        return try {
            audio.prepareForCapture()
            val engine = try {
                engineFactory(modelDirectory())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return NameCalibrationAttempt.Failure(NameCalibrationFailure.MODEL_UNAVAILABLE)
            }
            if (engine.modelProfileId != ModelProfiles.PRODUCTION.id ||
                engine.sampleRate != ModelProfiles.PRODUCTION.recognizer.sampleRate
            ) {
                return NameCalibrationAttempt.Failure(NameCalibrationFailure.MODEL_UNAVAILABLE)
            }

            val terminal = withTimeoutOrNull(attemptTimeoutMs) {
                engine.transcribe(audio.pcm()).first { event ->
                    event is StreamingAsrEvent.Final || event is StreamingAsrEvent.Failed
                }
            }
            when (terminal) {
                null -> NameCalibrationAttempt.Failure(NameCalibrationFailure.TIMEOUT)
                is StreamingAsrEvent.Failed -> NameCalibrationAttempt.Failure(NameCalibrationFailure.ASR_RUNTIME)
                is StreamingAsrEvent.Final -> when {
                    terminal.text.trim().isBlank() ->
                        NameCalibrationAttempt.Failure(NameCalibrationFailure.EMPTY_TRANSCRIPT)
                    else -> NameCalibrationAttempt.Success(terminal.text.trim())
                }
                else -> NameCalibrationAttempt.Failure(NameCalibrationFailure.ASR_RUNTIME)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: AudioCaptureException) {
            NameCalibrationAttempt.Failure(NameCalibrationFailure.MICROPHONE)
        } catch (_: NoSuchElementException) {
            NameCalibrationAttempt.Failure(NameCalibrationFailure.EMPTY_TRANSCRIPT)
        } catch (_: Exception) {
            NameCalibrationAttempt.Failure(NameCalibrationFailure.UNKNOWN)
        } finally {
            // AudioStreamer.stop() interrupts a blocking AudioRecord.read(); its own pcm finally
            // releases AudioRecord/AEC/NS, while sherpa transcribe finally releases stream/recognizer.
            runCatching { audio.stop() }
        }
    }

    companion object {
        const val DEFAULT_ATTEMPT_TIMEOUT_MS = 9_000L

        /** Production factory is deliberately pinned to the single bundled X480 profile. */
        fun create(context: Context): NameVoiceCalibrator {
            val appContext = context.applicationContext
            val profile = ModelProfiles.PRODUCTION
            check(profile === ModelProfiles.X_ASR_480) { "PRODUCTION_ASR_PROFILE_MISMATCH" }
            val preflight = LocalListenStartPreflight(
                readinessChecker = ModelReadinessChecker(appContext.filesDir),
                assetOpener = appContext.assets::open,
            )
            return X480NameVoiceCalibrator(
                ensureReady = { preflight.ensureReady(profile) },
                audioFactory = {
                    AudioStreamerNameCapture(AudioStreamer(context = appContext))
                },
                modelDirectory = {
                    ModelIntegrityVerifier.resolveTargetDirectory(appContext.filesDir, profile)
                },
                engineFactory = { directory ->
                    check(profile === ModelProfiles.X_ASR_480) { "PRODUCTION_ASR_PROFILE_MISMATCH" }
                    SherpaOnnxStreamingEngine(
                        profile = profile,
                        recognizerFactory = {
                            SherpaOnnxRecognizerFactory.create(directory, profile)
                        },
                    )
                },
            )
        }
    }

}

private class AudioStreamerNameCapture(
    private val streamer: AudioStreamer,
) : NameCalibrationAudioCapture {
    override fun prepareForCapture() = streamer.prepareForCapture()

    override fun pcm(): Flow<ShortArray> = streamer.pcm()

    override fun stop() = streamer.stop()
}

/** 三个采样槽位的纯状态控制器；失败可重试当前槽位，Skip 不会再调用 calibrator。 */
internal class NameCalibrationController(
    private val displayName: String,
    aiSeedVariants: List<String>,
    private val calibrator: NameVoiceCalibrator,
) {
    private val aiSeedVariants = aiSeedVariants.toList()

    var state: NameCalibrationUiState = NameCalibrationUiState()
        private set

    suspend fun captureNext(): NameCalibrationUiState {
        if (state.finished || state.isListening) return state
        state = state.copy(isListening = true, lastFailure = null)
        state = try {
            when (val attempt = calibrator.captureOnce(displayName)) {
                is NameCalibrationAttempt.Success -> {
                    val transcript = attempt.finalTranscript.trim()
                    if (transcript.isBlank()) {
                        state.copy(
                            isListening = false,
                            lastFailure = NameCalibrationFailure.EMPTY_TRANSCRIPT,
                        )
                    } else {
                        val completed = state.completedSlots + 1
                        state.copy(
                            completedSlots = completed,
                            measuredTranscripts = state.measuredTranscripts + transcript,
                            isListening = false,
                            lastFailure = null,
                            finished = completed >= MAX_SAMPLES,
                        )
                    }
                }
                is NameCalibrationAttempt.Failure -> state.copy(
                    isListening = false,
                    lastFailure = attempt.reason,
                )
            }
        } catch (error: CancellationException) {
            state = state.copy(isListening = false)
            throw error
        } catch (_: Exception) {
            state.copy(
                isListening = false,
                lastFailure = NameCalibrationFailure.UNKNOWN,
            )
        }
        return state
    }

    fun skipCurrent(): NameCalibrationUiState {
        if (state.finished || state.isListening) return state
        val completed = (state.completedSlots + 1).coerceAtMost(MAX_SAMPLES)
        state = state.copy(
            completedSlots = completed,
            isListening = false,
            lastFailure = null,
            finished = completed >= MAX_SAMPLES,
        )
        return state
    }

    fun skipAll(): NameCalibrationUiState {
        if (!state.isListening) state = state.copy(isListening = false, finished = true, lastFailure = null)
        return state
    }

    fun mergedVariants(): List<String> =
        NameVariantMergePolicy.merge(
            displayName = displayName,
            aiSeedVariants = aiSeedVariants,
            measuredTranscripts = state.measuredTranscripts,
        )

    fun mergeEntry(base: NameEntry): NameEntry =
        base.copy(asrVariants = mergedVariants())

    companion object {
        const val MAX_SAMPLES = 3
    }
}

data class NameCalibrationUiState(
    val completedSlots: Int = 0,
    val measuredTranscripts: List<String> = emptyList(),
    val isListening: Boolean = false,
    val lastFailure: NameCalibrationFailure? = null,
    val finished: Boolean = false,
)
