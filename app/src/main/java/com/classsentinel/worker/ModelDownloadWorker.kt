package com.classsentinel.worker

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.classsentinel.core.speech.HttpRangeModelSource
import com.classsentinel.core.speech.ModelDownloadException
import com.classsentinel.core.speech.ModelDownloadFailureReason
import com.classsentinel.core.speech.ModelDownloadState
import com.classsentinel.core.speech.ModelDownloadStateStore
import com.classsentinel.core.speech.ModelDownloadStateRegistry
import com.classsentinel.core.speech.ModelDistribution
import com.classsentinel.core.speech.ModelIntegrityVerifier
import com.classsentinel.core.speech.ModelProfile
import com.classsentinel.core.speech.ModelProfiles
import com.classsentinel.core.speech.RemoteModelInstaller
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

internal class ModelDownloadWorkerDependencies(
    val filesDir: java.io.File,
    val profileResolver: (String) -> ModelProfile?,
    val installerFactory: (ModelProfile) -> RemoteModelInstaller,
    val stateStore: ModelDownloadStateStore,
)

internal object ModelDownloadWorkerRuntime {
    fun create(context: Context): ModelDownloadWorkerDependencies {
        val appContext = context.applicationContext
        val client = OkHttpClient()
        return ModelDownloadWorkerDependencies(
            filesDir = appContext.filesDir,
            profileResolver = { id ->
                ModelProfiles.EVALUATION_CATALOG.firstOrNull { it.id == id }
            },
            installerFactory = { profile ->
                RemoteModelInstaller(
                    filesDir = appContext.filesDir,
                    profile = profile,
                    source = HttpRangeModelSource(client),
                )
            },
            stateStore = ModelDownloadStateRegistry,
        )
    }
}

internal class ModelDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    @VisibleForTesting
    var dependencies: ModelDownloadWorkerDependencies? = null

    override suspend fun doWork(): Result {
        val deps = dependencies ?: ModelDownloadWorkerRuntime.create(applicationContext)
        val profileId = inputData.getString(KEY_PROFILE_ID)
        if (profileId.isNullOrBlank()) {
            return fail(deps, profileId.orEmpty(), ModelDownloadFailureReason.INVALID_PROFILE)
        }
        val profile = deps.profileResolver(profileId)
            ?: return fail(deps, profileId, ModelDownloadFailureReason.INVALID_PROFILE)
        if (profile.distribution !is ModelDistribution.Remote) {
            return fail(deps, profileId, ModelDownloadFailureReason.BUNDLED_PROFILE)
        }

        if (ModelIntegrityVerifier.isInstalled(deps.filesDir, profile)) {
            deps.stateStore.publish(profileId, ModelDownloadState.Ready)
            return Result.success()
        }
        deps.stateStore.publish(profileId, ModelDownloadState.NotInstalled)

        return try {
            val target = withContext(Dispatchers.IO) {
                deps.installerFactory(profile).install(
                    onProgress = { downloadedBytes, totalBytes ->
                        deps.stateStore.publish(
                            profileId,
                            ModelDownloadState.Downloading(downloadedBytes, totalBytes),
                        )
                    },
                    onVerifying = {
                        deps.stateStore.publish(profileId, ModelDownloadState.Verifying)
                    },
                )
            }
            if (!ModelIntegrityVerifier.verify(profile, target)) {
                fail(deps, profileId, ModelDownloadFailureReason.INTEGRITY)
            } else {
                deps.stateStore.publish(profileId, ModelDownloadState.Ready)
                Result.success()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ModelDownloadException) {
            deps.stateStore.publish(profileId, ModelDownloadState.Failed(error.reason))
            if (error.retryable) Result.retry() else failure(error.reason)
        } catch (_: Exception) {
            fail(deps, profileId, ModelDownloadFailureReason.INSTALL)
        }
    }

    private fun fail(
        dependencies: ModelDownloadWorkerDependencies,
        profileId: String,
        reason: ModelDownloadFailureReason,
    ): Result {
        dependencies.stateStore.publish(profileId, ModelDownloadState.Failed(reason))
        return failure(reason)
    }

    private fun failure(reason: ModelDownloadFailureReason): Result =
        Result.failure(Data.Builder().putString(KEY_FAILURE_REASON, reason.name).build())

    companion object {
        internal const val KEY_PROFILE_ID = "profileId"
        internal const val KEY_FAILURE_REASON = "failureReason"
        private const val UNIQUE_WORK_PREFIX = "model-download:"
        private const val BACKOFF_DELAY_MILLIS = 30_000L

        internal fun uniqueWorkName(profileId: String): String = "$UNIQUE_WORK_PREFIX$profileId"

        internal fun buildRequest(profileId: String): OneTimeWorkRequest =
            OneTimeWorkRequest.Builder(ModelDownloadWorker::class.java)
                .setInputData(Data.Builder().putString(KEY_PROFILE_ID, profileId).build())
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    BACKOFF_DELAY_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                .build()

        internal fun enqueueUnique(workManager: WorkManager, profileId: String) {
            workManager.enqueueUniqueWork(
                uniqueWorkName(profileId),
                ExistingWorkPolicy.KEEP,
                buildRequest(profileId),
            )
        }
    }
}
