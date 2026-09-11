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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.classsentinel.core.speech.ASR_MODEL_STORAGE_INSUFFICIENT
import com.classsentinel.core.speech.LocalListenStartPreflight
import com.classsentinel.core.speech.ModelProfiles
import com.classsentinel.core.speech.ModelReadinessChecker
import com.classsentinel.core.speech.SherpaModelInstaller
import com.classsentinel.service.ListenService
import com.classsentinel.service.LiveStreamBus
import com.classsentinel.ui.components.AuroraCard
import com.classsentinel.ui.components.ScreenHeader
import com.classsentinel.ui.components.StatusPill
import com.classsentinel.ui.isSessionActive
import com.classsentinel.ui.theme.ClassSentinelSpacing
import kotlinx.coroutines.CancellationException
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
): Boolean {
    return SherpaModelInstaller.isInstalled(filesDir, ModelProfiles.PRODUCTION)
}

/** Home keeps the one-tap listening contract, but gives the listening decision visual priority. */
@Composable
fun HomeScreen(onOpenLive: () -> Unit = {}) {
    val context = LocalContext.current
    val pipelineState by LiveStreamBus.pipelineState.collectAsState()
    val activeCourseId by LiveStreamBus.activeCourseId.collectAsState()
    val historyDegraded by LiveStreamBus.historyDegraded.collectAsState()
    val names by AppConfig.names.collectAsState()
    val localListenPreflight = remember(context.filesDir) {
        LocalListenStartPreflight(
            readinessChecker = ModelReadinessChecker(context.filesDir),
            assetOpener = { path -> context.applicationContext.assets.open(path) },
        )
    }
    val preparationScope = rememberCoroutineScope()
    var modelReady by remember { mutableStateOf<Boolean?>(null) }
    var preparingModel by remember { mutableStateOf(false) }
    LaunchedEffect(pipelineState) {
        modelReady = localListenPreflight.isReady(ModelProfiles.PRODUCTION)
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
                try {
                    val prepared = localListenPreflight.ensureReady(profile = ModelProfiles.PRODUCTION)
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
                } catch (error: CancellationException) {
                    throw error
                } catch (error: IllegalStateException) {
                    modelReady = false
                    preparingModel = false
                    val message = if (error.message == ASR_MODEL_STORAGE_INSUFFICIENT) {
                        "存储空间不足，无法安装 X-ASR 480 模型"
                    } else {
                        "模型未准备，无法开始监听"
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ClassSentinelSpacing.lg, vertical = ClassSentinelSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(ClassSentinelSpacing.md),
    ) {
        ScreenHeader(
            eyebrow = "CLASS SENTINEL",
            title = "课堂哨兵",
            description = "安静地听，清楚地回看。先确认监听状态，再把注意力交还给课堂。",
        )

        AuroraCard(
            containerColor = if (listening) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ) {
            Column(
                modifier = Modifier.padding(ClassSentinelSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(ClassSentinelSpacing.md),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("监听状态", style = MaterialTheme.typography.titleMedium)
                    StatusPill(
                        label = when {
                            listening -> "正在监听"
                            pipelineState is PipelineState.Error -> "需要处理"
                            else -> "未在监听"
                        },
                        active = listening,
                    )
                }
                Text(
                    homeStateText(pipelineState),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (listening) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                historyPersistenceWarning(historyDegraded)?.let { warning ->
                    Text(
                        warning,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = ::toggleListening,
                    enabled = !preparingModel,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(
                        when {
                            listening -> "停止监听"
                            preparingModel -> "准备模型…"
                            else -> "开始监听"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }

        AuroraCard {
            Column(
                modifier = Modifier.padding(ClassSentinelSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(ClassSentinelSpacing.md),
            ) {
                Text("本节准备情况", style = MaterialTheme.typography.titleMedium)
                HomeInfoRow("姓名 / 称呼", names.firstOrNull()?.display ?: "未设置")
                HomeInfoRow(
                    "本地转写模型",
                    when (modelReady) {
                        true -> "已就绪"
                        false -> "未准备"
                        null -> "检查中…"
                    },
                )
                Text(
                    ModelProfiles.PRODUCTION.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        OutlinedButton(
            onClick = onOpenLive,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = MaterialTheme.shapes.medium,
        ) {
            Text("查看实时转写")
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}

@Composable
private fun HomeInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}
