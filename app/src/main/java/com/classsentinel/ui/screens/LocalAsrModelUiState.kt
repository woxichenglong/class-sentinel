package com.classsentinel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.classsentinel.core.speech.ModelDownloadFailureReason
import com.classsentinel.core.speech.ModelDownloadState
import com.classsentinel.core.speech.ModelDownloadStateRegistry
import com.classsentinel.core.speech.ModelDistribution
import com.classsentinel.core.speech.ModelReadinessChecker
import com.classsentinel.core.speech.ModelProfile
import com.classsentinel.worker.ModelDownloadActions
import com.classsentinel.worker.ModelDownloadWorker
import java.io.File
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class LocalAsrModelWorkStatus {
    NONE,
    ENQUEUED,
    RUNNING,
    BLOCKED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

internal enum class LocalAsrModelUiStatus {
    BUILT_IN,
    NOT_INSTALLED,
    DOWNLOADING,
    VERIFYING,
    READY,
    FAILED,
}

internal data class LocalAsrModelCardState(
    val profile: ModelProfile,
    val status: LocalAsrModelUiStatus,
    val selected: Boolean,
    val canSelect: Boolean,
    val canDownload: Boolean,
    val canCancel: Boolean,
    val canContinue: Boolean,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val percent: Int? = null,
    val failureMessage: String? = null,
)

internal fun localAsrModelCardState(
    profile: ModelProfile,
    downloadState: ModelDownloadState?,
    ready: Boolean,
    preferredModelId: String?,
    workStatus: LocalAsrModelWorkStatus,
    hasPartial: Boolean,
): LocalAsrModelCardState {
    val selected = profile.id == preferredModelId
    if (profile.distribution is ModelDistribution.Bundled) {
        return LocalAsrModelCardState(
            profile = profile,
            status = LocalAsrModelUiStatus.BUILT_IN,
            selected = selected,
            canSelect = true,
            canDownload = false,
            canCancel = false,
            canContinue = false,
        )
    }

    val workActive = workStatus == LocalAsrModelWorkStatus.ENQUEUED ||
        workStatus == LocalAsrModelWorkStatus.RUNNING ||
        workStatus == LocalAsrModelWorkStatus.BLOCKED
    val status = when {
        ready -> LocalAsrModelUiStatus.READY
        downloadState is ModelDownloadState.Verifying -> LocalAsrModelUiStatus.VERIFYING
        downloadState is ModelDownloadState.Downloading && workActive -> LocalAsrModelUiStatus.DOWNLOADING
        workActive -> LocalAsrModelUiStatus.DOWNLOADING
        downloadState is ModelDownloadState.Failed -> LocalAsrModelUiStatus.FAILED
        else -> LocalAsrModelUiStatus.NOT_INSTALLED
    }
    val downloading = downloadState as? ModelDownloadState.Downloading
    return LocalAsrModelCardState(
        profile = profile,
        status = status,
        selected = selected,
        canSelect = status == LocalAsrModelUiStatus.READY,
        canDownload = status == LocalAsrModelUiStatus.NOT_INSTALLED,
        canCancel = status == LocalAsrModelUiStatus.DOWNLOADING ||
            status == LocalAsrModelUiStatus.VERIFYING,
        canContinue = status == LocalAsrModelUiStatus.FAILED ||
            (status == LocalAsrModelUiStatus.NOT_INSTALLED && hasPartial),
        downloadedBytes = downloading?.downloadedBytes,
        totalBytes = downloading?.totalBytes,
        percent = downloading?.let { modelDownloadPercent(it.downloadedBytes, it.totalBytes) },
        failureMessage = (downloadState as? ModelDownloadState.Failed)?.reason?.toUiMessage(),
    )
}

internal fun modelDownloadPercent(downloadedBytes: Long, totalBytes: Long?): Int? {
    if (totalBytes == null || totalBytes <= 0L) return null
    if (downloadedBytes <= 0L) return 0
    if (downloadedBytes >= totalBytes) return 100
    return ((downloadedBytes.toDouble() / totalBytes.toDouble()) * 100.0)
        .toInt()
        .coerceIn(0, 100)
}

internal fun ModelDownloadFailureReason.toUiMessage(): String = when (this) {
    ModelDownloadFailureReason.NETWORK -> "网络失败"
    ModelDownloadFailureReason.HTTP,
    ModelDownloadFailureReason.RANGE_INVALID,
    -> "下载响应异常"
    ModelDownloadFailureReason.INTEGRITY -> "完整性校验失败"
    ModelDownloadFailureReason.RESPONSE_TOO_LARGE -> "下载响应异常"
    ModelDownloadFailureReason.INSTALL -> "存储空间不足或写入失败"
    ModelDownloadFailureReason.REMOTE_SOURCE_MISSING -> "远程地址未配置"
    ModelDownloadFailureReason.INVALID_PROFILE,
    ModelDownloadFailureReason.BUNDLED_PROFILE,
    -> "模型配置不可用"
}

internal const val MODEL_DOWNLOAD_SAFETY_MARGIN_BYTES = 32L * 1024L * 1024L

internal fun modelDownloadRemainingBytes(filesDir: File, profile: ModelProfile): Long =
    profile.artifact.files.sumOf { spec ->
        val formal = File(File(filesDir, "asr/${profile.artifact.directory}"), spec.name)
        val part = File(File(filesDir, "asr/${profile.artifact.directory}"), "${spec.name}.part")
        when {
            formal.isFile && formal.length() >= spec.expectedSize -> 0L
            part.isFile -> (spec.expectedSize - part.length()).coerceAtLeast(0L)
            else -> spec.expectedSize
        }
    }

internal fun hasEnoughModelStorage(
    filesDir: File,
    profile: ModelProfile,
    availableBytes: Long = filesDir.usableSpace,
): Boolean {
    val remaining = modelDownloadRemainingBytes(filesDir, profile)
    val required = if (Long.MAX_VALUE - remaining < MODEL_DOWNLOAD_SAFETY_MARGIN_BYTES) {
        Long.MAX_VALUE
    } else {
        remaining + MODEL_DOWNLOAD_SAFETY_MARGIN_BYTES
    }
    return availableBytes >= required
}

internal class LocalAsrModelActionHandler(
    private val actions: ModelDownloadActions,
    private val hasEnoughStorage: (ModelProfile) -> Boolean,
    private val onMessage: (String) -> Unit,
) {
    fun download(profile: ModelProfile): Boolean {
        if (profile.distribution !is ModelDistribution.Remote) return false
        if (!hasEnoughStorage(profile)) {
            onMessage("存储空间不足")
            return false
        }
        actions.enqueue(profile.id)
        return true
    }

    fun continueDownload(profile: ModelProfile): Boolean = download(profile)

    fun cancel(profile: ModelProfile) {
        if (profile.distribution is ModelDistribution.Remote) actions.cancel(profile.id)
    }
}

internal fun hasPartialModel(filesDir: File, profile: ModelProfile): Boolean {
    val directory = File(filesDir, "asr/${profile.artifact.directory}")
    return profile.artifact.files.any { File(directory, "${it.name}.part").isFile }
}

@Composable
internal fun LocalAsrModelCards(
    profiles: List<ModelProfile>,
    filesDir: File,
    preferredModelId: String,
    readinessChecker: ModelReadinessChecker,
    workManager: WorkManager,
    actionHandler: LocalAsrModelActionHandler,
    onSelect: (ModelProfile) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        profiles.forEach { profile ->
            val downloadState by ModelDownloadStateRegistry
                .stateFlow(profile.id)
                .collectAsState(initial = ModelDownloadStateRegistry.current(profile.id))
            val workInfos by workManager
                .getWorkInfosForUniqueWorkFlow(ModelDownloadWorker.uniqueWorkName(profile.id))
                .collectAsState(initial = emptyList())
            val workStatus = localAsrWorkStatus(workInfos.firstOrNull()?.state)
            val phase = when (downloadState) {
                is ModelDownloadState.Downloading -> "downloading"
                ModelDownloadState.Verifying -> "verifying"
                ModelDownloadState.Ready -> "ready"
                is ModelDownloadState.Failed -> "failed"
                else -> "idle"
            }
            var ready by remember(profile.id) {
                mutableStateOf(profile.distribution is ModelDistribution.Bundled)
            }
            LaunchedEffect(profile.id, phase, workStatus) {
                ready = if (profile.distribution is ModelDistribution.Bundled) {
                    true
                } else {
                    withContext(Dispatchers.IO) { readinessChecker.isReady(profile) }
                }
            }
            val cardState = localAsrModelCardState(
                profile = profile,
                downloadState = downloadState,
                ready = ready,
                preferredModelId = preferredModelId,
                workStatus = workStatus,
                hasPartial = hasPartialModel(filesDir, profile),
            )
            LocalAsrModelCard(
                state = cardState,
                onDownload = { actionHandler.download(profile) },
                onCancel = { actionHandler.cancel(profile) },
                onSelect = { onSelect(profile) },
            )
        }
    }
}

internal fun localAsrWorkStatus(state: WorkInfo.State?): LocalAsrModelWorkStatus = when (state) {
    WorkInfo.State.ENQUEUED -> LocalAsrModelWorkStatus.ENQUEUED
    WorkInfo.State.RUNNING -> LocalAsrModelWorkStatus.RUNNING
    WorkInfo.State.BLOCKED -> LocalAsrModelWorkStatus.BLOCKED
    WorkInfo.State.SUCCEEDED -> LocalAsrModelWorkStatus.SUCCEEDED
    WorkInfo.State.FAILED -> LocalAsrModelWorkStatus.FAILED
    WorkInfo.State.CANCELLED -> LocalAsrModelWorkStatus.CANCELLED
    null -> LocalAsrModelWorkStatus.NONE
}

@Composable
internal fun LocalAsrModelCard(
    state: LocalAsrModelCardState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onSelect: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(state.profile.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (state.profile.distribution is ModelDistribution.Bundled) "Bundled · 已内置"
                        else "Remote · 远程模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.selected) {
                    Text("当前偏好", style = MaterialTheme.typography.labelMedium)
                }
            }
            when (state.status) {
                LocalAsrModelUiStatus.BUILT_IN -> {
                    Text("✓ 已内置 · 默认可用", style = MaterialTheme.typography.bodyMedium)
                }
                LocalAsrModelUiStatus.NOT_INSTALLED -> {
                    Text(
                        if (state.canContinue) "未完成下载" else "未下载",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                LocalAsrModelUiStatus.DOWNLOADING -> {
                    val progress = state.percent?.let { "正在下载 $it%" } ?: "正在下载 ${state.downloadedBytes ?: 0L} bytes"
                    Text(progress, style = MaterialTheme.typography.bodyMedium)
                }
                LocalAsrModelUiStatus.VERIFYING -> Text("正在校验模型…", style = MaterialTheme.typography.bodyMedium)
                LocalAsrModelUiStatus.READY -> Text("✓ 已安装", style = MaterialTheme.typography.bodyMedium)
                LocalAsrModelUiStatus.FAILED -> {
                    Text(
                        "下载失败：${state.failureMessage ?: "未知错误"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    state.canCancel -> TextButton(onClick = onCancel) { Text("取消") }
                    state.canDownload || state.canContinue -> Button(onClick = onDownload) {
                        Text(if (state.canContinue) "继续下载" else "下载")
                    }
                }
                if (state.canSelect) {
                    OutlinedButton(onClick = onSelect, enabled = !state.selected) {
                        Text(if (state.selected) "当前模型" else "使用此模型")
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}
