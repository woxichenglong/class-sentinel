package com.classsentinel.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.classsentinel.core.pipeline.PipelineState
import com.classsentinel.core.speech.ASR_MODEL_STORAGE_INSUFFICIENT
import com.classsentinel.core.speech.LocalListenStartPreflight
import com.classsentinel.core.speech.ModelProfiles
import com.classsentinel.core.speech.ModelReadinessChecker
import com.classsentinel.service.ListenService
import com.classsentinel.service.LiveStreamBus
import com.classsentinel.ui.isSessionActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal enum class ListeningStartOutcome { STARTED, PERMISSION_REQUESTED, MODEL_NOT_READY, BUSY }

/** Both screens use this gate, including another state check after the asynchronous preparation. */
internal suspend fun requestListeningStart(
    currentState: () -> PipelineState,
    ensureReady: suspend () -> Boolean,
    microphoneGranted: () -> Boolean,
    requestPermission: () -> Unit,
    start: () -> Unit,
): ListeningStartOutcome {
    if (currentState().isSessionActive()) return ListeningStartOutcome.BUSY
    if (!ensureReady()) return ListeningStartOutcome.MODEL_NOT_READY
    if (currentState().isSessionActive()) return ListeningStartOutcome.BUSY
    if (!microphoneGranted()) {
        requestPermission()
        return ListeningStartOutcome.PERMISSION_REQUESTED
    }
    start()
    return ListeningStartOutcome.STARTED
}

internal data class ListeningControls(
    val modelReady: Boolean?,
    val preparing: Boolean,
    val requestStart: () -> Unit,
)

/** Screen-scoped permission launcher; repository/service state remains authoritative. */
@Composable
internal fun rememberListeningControls(): ListeningControls {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preflight = remember(context.applicationContext) {
        LocalListenStartPreflight(
            ModelReadinessChecker(context.applicationContext.filesDir),
            context.applicationContext.assets::open,
        )
    }
    var modelReady by remember { mutableStateOf<Boolean?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var permissionPending by remember { mutableStateOf(false) }
    var permissionResult by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionPending = false
        if (granted) permissionResult++
        else Toast.makeText(context, "未授予录音权限，无法开始监听", Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(preflight) {
        modelReady = preflight.isReady(ModelProfiles.PRODUCTION)
    }
    fun requestStart() {
        if (preparing || permissionPending) return
        preparing = true
        scope.launch {
            try {
                val outcome = requestListeningStart(
                    currentState = { LiveStreamBus.pipelineState.value },
                    ensureReady = {
                        preflight.ensureReady(ModelProfiles.PRODUCTION).also { modelReady = it }
                    },
                    microphoneGranted = {
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    },
                    requestPermission = {
                        permissionPending = true
                        launcher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    start = { ListenService.start(context) },
                )
                if (outcome == ListeningStartOutcome.MODEL_NOT_READY) {
                    Toast.makeText(context, "模型未准备，无法开始监听", Toast.LENGTH_SHORT).show()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                permissionPending = false
                val message = if (error.message == ASR_MODEL_STORAGE_INSUFFICIENT) {
                    "存储空间不足，无法安装 X-ASR 480 模型"
                } else {
                    "无法开始监听，请检查权限和模型后重试"
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            } finally {
                preparing = false
            }
        }
    }
    LaunchedEffect(permissionResult) {
        if (permissionResult > 0) requestStart()
    }
    return ListeningControls(modelReady, preparing || permissionPending, ::requestStart)
}
