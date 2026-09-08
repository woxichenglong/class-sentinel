package com.classsentinel.core.speech

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal enum class ModelDownloadFailureReason {
    INVALID_PROFILE,
    BUNDLED_PROFILE,
    REMOTE_SOURCE_MISSING,
    NETWORK,
    HTTP,
    RANGE_INVALID,
    RESPONSE_TOO_LARGE,
    INTEGRITY,
    INSTALL,
}

internal sealed interface ModelDownloadState {
    data object NotInstalled : ModelDownloadState

    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : ModelDownloadState {
        init {
            require(downloadedBytes >= 0L) { "MODEL_DOWNLOAD_BYTES_INVALID" }
            require(totalBytes > 0L) { "MODEL_DOWNLOAD_TOTAL_INVALID" }
            require(downloadedBytes <= totalBytes) { "MODEL_DOWNLOAD_PROGRESS_INVALID" }
        }
    }

    data object Verifying : ModelDownloadState
    data object Ready : ModelDownloadState

    data class Failed(
        val reason: ModelDownloadFailureReason,
    ) : ModelDownloadState
}

internal interface ModelDownloadStateStore {
    fun publish(profileId: String, state: ModelDownloadState)
    fun current(profileId: String): ModelDownloadState?
}

internal class InMemoryModelDownloadStateStore : ModelDownloadStateStore {
    private val states = ConcurrentHashMap<String, ModelDownloadState>()

    override fun publish(profileId: String, state: ModelDownloadState) {
        states[profileId] = state
    }

    override fun current(profileId: String): ModelDownloadState? = states[profileId]
}

internal object ModelDownloadStateRegistry : ModelDownloadStateStore {
    private val delegate = InMemoryModelDownloadStateStore()
    private val flows = ConcurrentHashMap<String, MutableStateFlow<ModelDownloadState?>>()

    override fun publish(profileId: String, state: ModelDownloadState) {
        delegate.publish(profileId, state)
        flows.computeIfAbsent(profileId) { MutableStateFlow(null) }.value = state
    }

    override fun current(profileId: String): ModelDownloadState? = delegate.current(profileId)

    fun stateFlow(profileId: String): StateFlow<ModelDownloadState?> =
        flows.computeIfAbsent(profileId) { MutableStateFlow(delegate.current(profileId)) }
}

internal class ModelDownloadException(
    val reason: ModelDownloadFailureReason,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
