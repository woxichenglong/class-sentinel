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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.classsentinel.core.detect.EventType
import com.classsentinel.core.pipeline.PipelineState
import com.classsentinel.data.SettingsRepositoryHolder
import com.classsentinel.service.ListenService
import com.classsentinel.service.LiveStreamBus

/**
 * 首页：开始/停止听讲 + 当前管线状态 + 今日统计占位卡。
 * 听讲通过前台服务 ListenService 控制（需 RECORD_AUDIO 权限，未授权先请求）。
 */
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val repo = remember { SettingsRepositoryHolder.get(context) }

    // 冷启动把 DataStore 设置灌入 AppConfig（ListenService 启动时热读）
    LaunchedEffect(Unit) { repo.load() }

    var listening by rememberSaveable { mutableStateOf(false) }
    val pipelineState by LiveStreamBus.pipelineState.collectAsState()
    val events by LiveStreamBus.events.collectAsState()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            ListenService.start(context)
            listening = true
        } else {
            Toast.makeText(context, "未授予录音权限，无法开始听讲", Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleListening() {
        if (listening) {
            ListenService.stop(context)
            listening = false
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            ListenService.start(context)
            listening = true
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val stateText = when (val s = pipelineState) {
        is PipelineState.Idle -> "未在监听"
        is PipelineState.Listening -> "正在听讲 · 已转写 ${s.sentences} 句"
        is PipelineState.Error -> "监听出错：${s.message}"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { toggleListening() },
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (listening) "停止听讲" else "开始听讲",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 管线状态卡
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("监听状态", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (listening) "● " else "○ ",
                        color = if (listening) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(stateText, style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    "提示：状态实时跟踪稍后接入管线；模拟验证请到「设置 → 自检」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 今日统计占位卡
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("今日统计", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("点名提醒")
                    Text("${events.count { it.type == EventType.ROLLCALL }} 次", style = MaterialTheme.typography.titleMedium)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("提问提醒")
                    Text("${events.count { it.type == EventType.QUESTION }} 次", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "占位：完整统计待历史库接入后按天聚合",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}