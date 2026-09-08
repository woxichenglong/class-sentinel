package com.classsentinel.core.speech

import java.io.File
import kotlinx.coroutines.CancellationException

internal enum class RuntimeAsrFallbackReason {
    PREFERENCE_MISSING,
    PREFERENCE_UNKNOWN,
    REMOTE_NOT_READY,
    REMOTE_INTEGRITY_INVALID,
    MODEL_INIT_FAILED,
}

internal data class RuntimeAsrModelSelection(
    val requestedProfile: ModelProfile?,
    val selectedProfile: ModelProfile,
    val fallbackReason: RuntimeAsrFallbackReason?,
)

internal class RuntimeAsrModelResolver(
    private val readiness: suspend (ModelProfile) -> Boolean,
    private val remoteDirectoryExists: (ModelProfile) -> Boolean = { false },
    private val fallbackProfile: ModelProfile = ModelProfiles.ZIPFORMER_ZH_14M,
) {
    constructor(
        filesDir: File,
        readinessChecker: ModelReadinessChecker,
        fallbackProfile: ModelProfile = ModelProfiles.ZIPFORMER_ZH_14M,
    ) : this(
        readiness = { profile -> readinessChecker.isReady(profile) },
        remoteDirectoryExists = remoteDirectoryExistsFor(filesDir),
        fallbackProfile = fallbackProfile,
    )

    suspend fun resolve(preferredModelId: String?): RuntimeAsrModelSelection {
        if (preferredModelId.isNullOrBlank()) {
            return fallback(RuntimeAsrFallbackReason.PREFERENCE_MISSING)
        }
        val requested = ModelProfiles.EVALUATION_CATALOG.firstOrNull { it.id == preferredModelId }
            ?: return fallback(RuntimeAsrFallbackReason.PREFERENCE_UNKNOWN)
        if (requested.distribution is ModelDistribution.Bundled) {
            return RuntimeAsrModelSelection(requested, requested, null)
        }
        if (readiness(requested)) {
            return RuntimeAsrModelSelection(requested, requested, null)
        }
        val reason = if (remoteDirectoryExists(requested)) {
            RuntimeAsrFallbackReason.REMOTE_INTEGRITY_INVALID
        } else {
            RuntimeAsrFallbackReason.REMOTE_NOT_READY
        }
        return fallback(reason, requested)
    }

    private fun fallback(
        reason: RuntimeAsrFallbackReason,
        requested: ModelProfile? = null,
    ): RuntimeAsrModelSelection = RuntimeAsrModelSelection(
        requestedProfile = requested,
        selectedProfile = fallbackProfile,
        fallbackReason = reason,
    )
}

private fun remoteDirectoryExistsFor(filesDir: File): (ModelProfile) -> Boolean = { profile ->
    runCatching {
        ModelIntegrityVerifier.resolveTargetDirectory(filesDir, profile).isDirectory
    }.getOrDefault(false)
}

internal data class RuntimeAsrEngineCreation(
    val engine: ProfileBoundStreamingSpeechEngine,
    val selection: RuntimeAsrModelSelection,
)

internal class RuntimeAsrModelInitializationException(
    cause: Throwable? = null,
) : IllegalStateException("ASR_MODEL_INIT_FAILED", cause)

/** One-shot startup seam: model init failure may fall back once, never recursively. */
internal class RuntimeAsrEngineStarter(
    private val prepareDirectory: suspend (ModelProfile) -> File,
    private val initializeModel: suspend (File, ModelProfile) -> Unit,
    private val createEngine: (File, ModelProfile) -> ProfileBoundStreamingSpeechEngine,
    private val fallbackProfile: ModelProfile = ModelProfiles.ZIPFORMER_ZH_14M,
) {
    suspend fun create(selection: RuntimeAsrModelSelection): RuntimeAsrEngineCreation {
        return try {
            RuntimeAsrEngineCreation(
                engine = attempt(selection.selectedProfile),
                selection = selection,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (selection.selectedProfile.id == fallbackProfile.id) {
                throw RuntimeAsrModelInitializationException(error)
            }
            val fallbackSelection = selection.copy(
                selectedProfile = fallbackProfile,
                fallbackReason = RuntimeAsrFallbackReason.MODEL_INIT_FAILED,
            )
            try {
                RuntimeAsrEngineCreation(
                    engine = attempt(fallbackProfile),
                    selection = fallbackSelection,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (fallbackError: Exception) {
                throw RuntimeAsrModelInitializationException(fallbackError)
            }
        }
    }

    private suspend fun attempt(profile: ModelProfile): ProfileBoundStreamingSpeechEngine {
        val directory = prepareDirectory(profile)
        initializeModel(directory, profile)
        return createEngine(directory, profile)
    }
}
