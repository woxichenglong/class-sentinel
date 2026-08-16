package com.classsentinel.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.classsentinel.core.detect.NameEntry
import com.classsentinel.data.AiSettings
import com.classsentinel.data.SettingsRepositoryHolder
import kotlinx.coroutines.launch

/** 首启引导：①录名字 ②授权限 ③配 AI */
@Composable
fun OnboardingScreen() {
    var step by remember { mutableIntStateOf(0) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("课堂哨兵", style = MaterialTheme.typography.headlineLarge)
            Text("三步完成设置", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(32.dp))
            when (step) {
                0 -> StepName(onNext = { step = 1 })
                1 -> StepPermissions(onNext = { step = 2 }, onBack = { step = 0 })
                2 -> StepAi(onDone = { /* 完成, 由调用方导航 */ }, onBack = { step = 1 })
            }
        }
    }
}

@Composable
private fun StepName(onNext: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var display by remember { mutableStateOf("") }
    var variants by remember { mutableStateOf("") }

    Text("你的名字（老师点名用的）", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = display,
        onValueChange = { display = it },
        label = { Text("姓名") },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = variants,
        onValueChange = { variants = it },
        label = { Text("变体（逗号分隔：同音字/昵称/拼音）") },
        placeholder = { Text("例：张微, 张威, zhang wei") },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = {
            val v = variants.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
            scope.launch {
                SettingsRepositoryHolder.get(context)
                    .saveNameList(listOf(NameEntry(display.trim(), v)))
                onNext()
            }
        },
        enabled = display.trim().isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("下一步") }
}

@Composable
private fun StepPermissions(onNext: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var audioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED,
        )
    }
    var notifyGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED,
        )
    }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    val audioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { audioGranted = it }
    val notifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notifyGranted = it }

    Text("权限（课堂监听必需）", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(16.dp))
    PermissionRow("麦克风（听老师讲话）", audioGranted) {
        audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    if (Build.VERSION.SDK_INT >= 33) {
        PermissionRow("通知（点名提醒）", notifyGranted) {
            notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    PermissionRow("悬浮窗（显示答案）", overlayGranted) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onNext,
        enabled = audioGranted && notifyGranted,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("下一步") }
    Spacer(Modifier.height(8.dp))
    ElevatedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("上一步") }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onRequest: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        if (granted) {
            Text("✅ 已授权")
        } else {
            Button(onClick = onRequest) { Text("去授权") }
        }
    }
}

@Composable
private fun StepAi(onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var baseUrl by remember { mutableStateOf("https://api.siliconflow.cn/v1") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }

    Text("AI 回答配置（OpenAI 兼容）", style = MaterialTheme.typography.titleLarge)
    Text("填硅基流动的 key 即可；也可用任意 OpenAI 兼容服务", style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = baseUrl,
        onValueChange = { baseUrl = it },
        label = { Text("Base URL") },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = apiKey,
        onValueChange = { apiKey = it },
        label = { Text("API Key") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = model,
        onValueChange = { model = it },
        label = { Text("模型名") },
        placeholder = { Text("例：deepseek-ai/DeepSeek-V4-Flash") },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = {
            scope.launch {
                val repo = SettingsRepositoryHolder.get(context)
                repo.saveAiSettings(AiSettings(baseUrl.trim(), apiKey.trim(), model.trim()))
                onDone()
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("完成") }
    Spacer(Modifier.height(8.dp))
    ElevatedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("上一步") }
}
