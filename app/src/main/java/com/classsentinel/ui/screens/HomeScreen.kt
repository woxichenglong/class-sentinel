package com.classsentinel.ui.screens

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.classsentinel.core.config.AppConfig
import com.classsentinel.core.pipeline.PipelineState
import com.classsentinel.core.speech.ModelProfiles
import com.classsentinel.core.speech.SherpaModelInstaller
import com.classsentinel.service.ListenService
import com.classsentinel.service.LiveStreamBus
import com.classsentinel.ui.components.AuroraCard
import com.classsentinel.ui.components.ScreenHeader
import com.classsentinel.ui.components.StatusPill
import com.classsentinel.ui.isSessionActive
import com.classsentinel.ui.theme.ClassSentinelSpacing
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
    is PipelineState.Error -> if (state.retryableStop) "停止失败，请再次停止" else "监听出错：${state.message}"
}

internal fun homeStatusPillLabel(state: PipelineState, hasActiveCourse: Boolean): String = when {
    state is PipelineState.Error && state.retryableStop -> "停止待重试"
    state.isSessionActive() || hasActiveCourse -> "正在监听"
    state is PipelineState.Error -> "需要处理"
    else -> "未在监听"
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
    val controls = rememberListeningControls()
    val modelReady = controls.modelReady
    val preparingModel = controls.preparing
    val listening = pipelineState.isSessionActive() || activeCourseId != null
    val stopRecovery = (pipelineState as? PipelineState.Error)?.retryableStop == true

    fun toggleListening() {
        when (livePrimaryActionUi(pipelineState).action) {
            LivePrimaryAction.START -> controls.requestStart()
            LivePrimaryAction.STOP -> ListenService.stop(context)
            LivePrimaryAction.DISABLED -> Unit
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
                        label = homeStatusPillLabel(pipelineState, activeCourseId != null),
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
                    enabled = !preparingModel && pipelineState != PipelineState.Stopping,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(
                        when {
                            pipelineState == PipelineState.Stopping -> "正在停止…"
                            stopRecovery -> "再次停止"
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
