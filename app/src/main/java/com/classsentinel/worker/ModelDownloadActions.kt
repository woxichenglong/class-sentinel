package com.classsentinel.worker

import androidx.work.WorkManager

internal interface ModelDownloadActions {
    fun enqueue(profileId: String)
    fun cancel(profileId: String)
}

internal class WorkManagerModelDownloadActions(
    private val workManager: WorkManager,
) : ModelDownloadActions {
    override fun enqueue(profileId: String) {
        ModelDownloadWorker.enqueueUnique(workManager, profileId)
    }

    override fun cancel(profileId: String) {
        workManager.cancelUniqueWork(ModelDownloadWorker.uniqueWorkName(profileId))
    }
}
