package com.classsentinel.core.speech

import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

/** Stable event-based ASR boundary for the live listening path. */
internal interface StreamingSpeechEngine {
    val name: String
    fun transcribe(pcm: Flow<ShortArray>): Flow<StreamingAsrEvent>
}

/** Streaming engine whose profile identity and sample rate are explicit and immutable. */
internal interface ProfileBoundStreamingSpeechEngine : StreamingSpeechEngine {
    val modelProfileId: String
    val sampleRate: Int
}

/** Prepared replay model; profile, declared artifact set, and engine cannot be passed separately. */
internal class PreparedModel private constructor(
    val profile: ModelProfile,
    val artifactSetHash: String,
    val engine: ProfileBoundStreamingSpeechEngine,
) {
    companion object {
        fun from(
            profile: ModelProfile,
            engine: ProfileBoundStreamingSpeechEngine,
        ): PreparedModel {
            require(engine.modelProfileId == profile.id) { "REPLAY_ENGINE_PROFILE_MISMATCH" }
            require(engine.sampleRate == profile.recognizer.sampleRate) {
                "REPLAY_ENGINE_SAMPLE_RATE_MISMATCH"
            }
            return PreparedModel(
                profile = profile,
                artifactSetHash = profile.artifactSetHash(),
                engine = engine,
            )
        }
    }
}

private fun ModelProfile.artifactSetHash(): String {
    val manifest = buildString {
        append(id).append('|').append(version)
        artifact.files.forEach { file ->
            append('|').append(file.name)
                .append('|').append(file.expectedSize)
                .append('|').append(file.sha256)
        }
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(manifest.toByteArray())
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}

/** Timings reported by an engine that can separate recognizer setup from decoding. */
internal data class StreamingAsrTimings(
    val recognizerInitMs: Long?,
    val decodeElapsedMs: Long?,
)

/** Optional timing seam consumed by replay; live engines are not required to implement it. */
internal interface ReplayTimingSource {
    val lastReplayTimings: StreamingAsrTimings?
}
