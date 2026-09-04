package com.classsentinel.core.speech

import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach

/** Distinguishes setup, warm-up, and steady-state measurements in a replay run. */
enum class ReplayPhase {
    COLD,
    WARMUP,
    WARM,
    STEADY,
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
    val elapsedMs: Long,
    val observations: List<ReplayObservation>,
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
 * This class deliberately does not parse WAV files or invoke the legacy importer/VAD path.
 */
internal class PcmReplayRunner(
    private val nowNanos: () -> Long = System::nanoTime,
) {
    suspend fun run(
        profile: ModelProfile,
        gitCommitSha: String,
        runId: String,
        phase: ReplayPhase,
        pcm: Flow<ShortArray>,
        engine: StreamingSpeechEngine,
    ): PcmReplayResult {
        require(gitCommitSha.isNotBlank()) { "REPLAY_GIT_SHA_REQUIRED" }
        require(runId.isNotBlank()) { "REPLAY_RUN_ID_REQUIRED" }
        val startedAtNanos = nowNanos()
        var inputSamples = 0L
        val observations = mutableListOf<ReplayObservation>()

        engine.transcribe(
            pcm.onEach { chunk -> inputSamples += chunk.size.toLong() },
        ).collect { event ->
            observations += ReplayObservation(
                event = event,
                observedLatencyMs = elapsedMs(startedAtNanos),
            )
        }
        val totalElapsedMs = elapsedMs(startedAtNanos)

        return PcmReplayResult(
            modelProfileId = profile.id,
            gitCommitSha = gitCommitSha,
            runId = runId,
            phase = phase,
            engineName = engine.name,
            inputSamples = inputSamples,
            inputDurationMs = inputSamples * 1_000L / profile.recognizer.sampleRate,
            elapsedMs = totalElapsedMs,
            observations = observations.toList(),
        )
    }

    suspend fun runWav(
        profile: ModelProfile,
        gitCommitSha: String,
        runId: String,
        phase: ReplayPhase,
        wav: InputStream,
        chunkMs: Int = profile.recognizer.streamChunkMs ?: DEFAULT_CHUNK_MS,
        engine: StreamingSpeechEngine,
    ): PcmReplayResult = run(
        profile = profile,
        gitCommitSha = gitCommitSha,
        runId = runId,
        phase = phase,
        pcm = PcmReplayWavSource.chunks(
            input = wav,
            expectedSampleRate = profile.recognizer.sampleRate,
            chunkMs = chunkMs,
        ),
        engine = engine,
    )

    private fun elapsedMs(startedAtNanos: Long): Long =
        ((nowNanos() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)

    private companion object {
        const val DEFAULT_CHUNK_MS = 100
    }
}
