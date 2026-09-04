package com.classsentinel.core.speech

import java.io.InputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/** Distinguishes setup, warm-up, and steady-state measurements in a replay run. */
enum class ReplayPhase {
    COLD,
    WARMUP,
    WARM,
    STEADY,
}

/** Whether replay emits packets as fast as possible or follows transport timing. */
enum class ReplayMode {
    FAST,
    REALTIME,
}

/** Replay/transport packet settings; deliberately independent from artifact metadata. */
data class ReplayInputConfig(
    val inputPacketMs: Int = DEFAULT_INPUT_PACKET_MS,
    val mode: ReplayMode = ReplayMode.FAST,
) {
    init {
        require(inputPacketMs > 0) { "REPLAY_INPUT_PACKET_INVALID" }
    }

    companion object {
        const val DEFAULT_INPUT_PACKET_MS = 100
    }
}

/** One event and the elapsed time from replay start to observation. */
data class ReplayObservation(
    val event: StreamingAsrEvent,
    val observedLatencyMs: Long,
)

/** Structured output for one deterministic PCM replay invocation. */
data class PcmReplayResult(
    val modelProfileId: String,
    val gitCommitSha: String,
    val runId: String,
    val phase: ReplayPhase,
    val engineName: String,
    val inputSamples: Long,
    val inputDurationMs: Long,
    val totalElapsedMs: Long,
    val observations: List<ReplayObservation>,
    val inputPacketMs: Int = ReplayInputConfig.DEFAULT_INPUT_PACKET_MS,
    val replayMode: ReplayMode = ReplayMode.FAST,
    val recognizerInitMs: Long? = null,
    val decodeElapsedMs: Long? = null,
    val artifactSetHash: String = "",
) {
    val firstPartialLatencyMs: Long?
        get() = observations
            .firstOrNull { it.event is StreamingAsrEvent.Partial }
            ?.observedLatencyMs

    val firstFinalLatencyMs: Long?
        get() = observations
            .firstOrNull { it.event is StreamingAsrEvent.Final }
            ?.observedLatencyMs
}

/**
 * Replays fixed PCM chunks directly into the streaming ASR boundary.
 * [run] accepts PCM directly; [runWav] uses the dedicated PCM16 WAV source and never invokes
 * the legacy importer/VAD path.
 */
internal class PcmReplayRunner(
    private val nowNanos: () -> Long = System::nanoTime,
    private val delayBetweenPackets: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun run(
        preparedModel: PreparedModel,
        gitCommitSha: String,
        runId: String,
        phase: ReplayPhase,
        pcm: Flow<ShortArray>,
        config: ReplayInputConfig = ReplayInputConfig(),
    ): PcmReplayResult {
        require(gitCommitSha.isNotBlank()) { "REPLAY_GIT_SHA_REQUIRED" }
        require(runId.isNotBlank()) { "REPLAY_RUN_ID_REQUIRED" }
        val profile = preparedModel.profile
        val engine = preparedModel.engine
        val startedAtNanos = nowNanos()
        var inputSamples = 0L
        val observations = mutableListOf<ReplayObservation>()
        val pacedPcm = flow {
            var samplesBeforePacket = 0L
            pcm.collect { chunk ->
                if (config.mode == ReplayMode.REALTIME) {
                    awaitPacketTarget(
                        replayStartNanos = startedAtNanos,
                        samplesBeforePacket = samplesBeforePacket,
                        sampleRate = profile.recognizer.sampleRate,
                    )
                }
                inputSamples += chunk.size.toLong()
                emit(chunk)
                samplesBeforePacket += chunk.size.toLong()
            }
        }

        engine.transcribe(
            pacedPcm,
        ).collect { event ->
            observations += ReplayObservation(
                event = event,
                observedLatencyMs = elapsedMs(startedAtNanos),
            )
        }
        val totalElapsedMs = elapsedMs(startedAtNanos)
        val timings = (engine as? ReplayTimingSource)?.lastReplayTimings

        return PcmReplayResult(
            modelProfileId = profile.id,
            gitCommitSha = gitCommitSha,
            runId = runId,
            phase = phase,
            engineName = engine.name,
            inputSamples = inputSamples,
            inputDurationMs = inputSamples * 1_000L / profile.recognizer.sampleRate,
            totalElapsedMs = totalElapsedMs,
            observations = observations.toList(),
            inputPacketMs = config.inputPacketMs,
            replayMode = config.mode,
            recognizerInitMs = timings?.recognizerInitMs,
            decodeElapsedMs = timings?.decodeElapsedMs,
            artifactSetHash = preparedModel.artifactSetHash,
        )
    }

    suspend fun runWav(
        preparedModel: PreparedModel,
        gitCommitSha: String,
        runId: String,
        phase: ReplayPhase,
        wav: InputStream,
        config: ReplayInputConfig = ReplayInputConfig(),
    ): PcmReplayResult = run(
        preparedModel = preparedModel,
        gitCommitSha = gitCommitSha,
        runId = runId,
        phase = phase,
        pcm = PcmReplayWavSource.chunks(
            input = wav,
            expectedSampleRate = preparedModel.profile.recognizer.sampleRate,
            packetMs = config.inputPacketMs,
        ),
        config = config,
    )

    private suspend fun awaitPacketTarget(
        replayStartNanos: Long,
        samplesBeforePacket: Long,
        sampleRate: Int,
    ) {
        val targetNanos = replayStartNanos +
            (samplesBeforePacket / sampleRate.toLong()) * 1_000_000_000L +
            (samplesBeforePacket % sampleRate.toLong()) * 1_000_000_000L / sampleRate.toLong()
        val waitNanos = targetNanos - nowNanos()
        if (waitNanos > 0L) {
            val waitMs = waitNanos / 1_000_000L + if (waitNanos % 1_000_000L == 0L) 0L else 1L
            delayBetweenPackets(waitMs)
        }
    }

    private fun elapsedMs(startedAtNanos: Long): Long =
        ((nowNanos() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)

}
