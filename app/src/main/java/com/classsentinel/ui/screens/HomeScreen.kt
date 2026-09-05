package com.classsentinel.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.classsentinel.core.config.AppConfig
import com.classsentinel.core.pipeline.PipelineState
import com.classsentinel.core.speech.ModelProfile
import com.classsentinel.core.speech.ModelReadinessChecker
import com.classsentinel.core.speech.ModelProfiles
import com.classsentinel.core.speech.SherpaModelInstaller
import com.classsentinel.service.ListenService
import com.classsentinel.service.LiveStreamBus
import com.classsentinel.ui.isSessionActive
import kotlinx.coroutines.launch
import java.io.File

internal enum class LocalListeningStartGate {
    READY,
    MODEL_NOT_READY,
}

internal fun localListeningStartGate(modelReady: Boolean?): LocalListeningStartGate =
    if (modelReady == true) LocalListeningStartGate.READY else LocalListeningStartGate.MODEL_NOT_READY

internal fun homeStateText(state: PipelineState): String = when (state) {
    PipelineState.Idle -> "未在监听"
    PipelineState.Starting -> "正在启动监听…"
    is PipelineState.Listening -> "正在监听 · 已转写 ${state.sentences} 句"
    is PipelineState.Recovering -> "正在恢复监听：${state.message}"
    PipelineState.Stopping -> "正在停止监听…"
    is PipelineState.Error -> "监听出错：${state.message}"
}

internal fun localAsrModelReady(
    filesDir: File,
    profile: ModelProfile = ModelProfiles.ZIPFORMER_ZH_14M,
): Boolean {
    return SherpaModelInstaller.isInstalled(filesDir, profile)
}

/** Student home: one-tap listening, identity, and local model readiness. */
@Composable
fun HomeScreen(onOpenLive: () -> Unit = {}) {
    val context = LocalContext.current
    val pipelineState by LiveStreamBus.pipelineState.collectAsState()
    val activeCourseId by LiveStreamBus.activeCourseId.collectAsState()
    val names by AppConfig.names.collectAsState()
    val settings = remember { com.classsentinel.data.SettingsRepositoryHolder.get(context) }
    val localAsrModelId by settings.localAsrModelIdFlow.collectAsState(initial = ModelProfiles.ZIPFORMER_ZH_14M.id)
    val localAsrProfile = ModelProfiles.resolveDaily(localAsrModelId)
    val readinessChecker = remember(context.filesDir) { ModelReadinessChecker(context.filesDir) }
    val preparationScope = rememberCoroutineScope()
    var modelReady by remember(localAsrProfile.id) { mutableStateOf<Boolean?>(null) }
    var preparingModel by remember(localAsrProfile.id) { mutableStateOf(false) }
    LaunchedEffect(pipelineState, localAsrProfile.id) {
        modelReady = readinessChecker.isReady(localAsrProfile)
    }

    val listening = pipelineState.isSessionActive() || activeCourseId != null
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) ListenService.start(context)
        else Toast.makeText(context, "未授予录音权限，无法开始监听", Toast.LENGTH_SHORT).show()
    }

    fun toggleListening() {
        if (listening) {
            ListenService.stop(context)
        } else if (localListeningStartGate(modelReady) == LocalListeningStartGate.MODEL_NOT_READY) {
            if (preparingModel) return
            preparingModel = true
            preparationScope.launch {
                val prepared = readinessChecker.ensureReady(
                    profile = localAsrProfile,
                    assetOpener = { path -> context.applicationContext.assets.open(path) },
                )
                modelReady = prepared
                preparingModel = false
                if (prepared) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        ListenService.start(context)
                    } else {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                } else {
                    Toast.makeText(context, "模型未准备，无法开始监听", Toast.LENGTH_SHORT).show()
                }
            }
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            ListenService.start(context)
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("课堂哨兵", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "上课时点一次开始监听，停止后可按日期回看问答。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = ::toggleListening,
            enabled = !preparingModel,
            modifier = Modifier.fillMaxWidth().height(104.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Mic, contentDescription = null)
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        listening -> "停止监听"
                        preparingModel -> "准备模型…"
                        else -> "开始监听"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("当前状态", style = MaterialTheme.typography.titleMedium)
                Text(homeStateText(pipelineState), style = MaterialTheme.typography.bodyLarge)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("姓名/称呼", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(names.firstOrNull()?.display ?: "未设置")
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("本地转写模型", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        when (modelReady) {
                            true -> "已就绪"
                            false -> "未准备"
                            null -> "检查中…"
                        },
                    )
                }
                Text(localAsrProfile.displayName, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onOpenLive, modifier = Modifier.fillMaxWidth()) {
            Text("查看实时转写")
        }
    }
}