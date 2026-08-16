package com.classsentinel.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.classsentinel.core.alert.AlertCoordinator
import com.classsentinel.core.alert.EarSoundChannel
import com.classsentinel.core.alert.FlashScreenChannel
import com.classsentinel.core.alert.NotifyChannel
import com.classsentinel.core.alert.RingtoneChannel
import com.classsentinel.core.alert.VibratorChannel
import com.classsentinel.core.config.AppConfig
import com.classsentinel.core.detect.ClassEvent
import com.classsentinel.core.detect.EventType
import com.classsentinel.core.llm.AnswerService
import com.classsentinel.core.llm.AnswerStyle
import com.classsentinel.core.llm.LlmClient
import com.classsentinel.core.llm.LlmConfig
import com.classsentinel.core.pipeline.PipelineState
import com.classsentinel.core.speech.TeleSpeechEngine
import com.classsentinel.data.SettingsRepositoryHolder
import com.classsentinel.service.FloatAnswerWindow
import com.classsentinel.service.LiveStreamBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 自检调试页：七项自检。
 * ①权限矩阵 ②麦克风电平 ③ASR 转写 ④LLM 延迟 ⑤模拟事件全链路 ⑥运行日志 ⑦崩溃摘要。
 */
@Composable
fun SelfTestScreen() {
    val context = LocalContext.current
    val repo = remember { SettingsRepositoryHolder.get(context) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { repo.load() }

    // ① 权限矩阵状态（每次重组重读，授权页返回后自动刷新）
    val micGranted = hasPermission(context, Manifest.permission.RECORD_AUDIO)
    val notifGranted = if (Build.VERSION.SDK_INT < 33) true else hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
    val overlayGranted = Settings.canDrawOverlays(context)

    var micLevel by remember { mutableStateOf("未测量") }
    var micBusy by remember { mutableStateOf(false) }
    var asrResult by remember { mutableStateOf("未测试") }
    var asrBusy by remember { mutableStateOf(false) }
    var llmResult by remember { mutableStateOf("未测试") }
    var llmBusy by remember { mutableStateOf(false) }
    val logEntries by SelftestLog.entries.collectAsState()

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        SelftestLog.record("录音权限 ${if (granted) "已授予" else "被拒绝"}")
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        SelftestLog.record("通知权限 ${if (granted) "已授予" else "被拒绝"}")
    }

    // ⑤ 模拟事件复用同一协调器（事件全链路分发）
    val coordinator = remember {
        AlertCoordinator(
            channels = listOf(
                VibratorChannel(),
                RingtoneChannel(),
                NotifyChannel(),
                FlashScreenChannel(),
                EarSoundChannel(),
            ),
            enabledFlow = AppConfig.enabledChannels,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("自检调试", style = MaterialTheme.typography.headlineSmall)

        // ---------- ① 权限矩阵 ----------
        SectionCard("① 权限矩阵") {
            PermissionRow("录音（RECORD_AUDIO）", micGranted, label = if (micGranted) "已授权" else "未授权") {
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            PermissionRow("通知（POST_NOTIFICATIONS）", notifGranted, label = if (notifGranted) "已授权" else "未授权") {
                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else SelftestLog.record("API < 33，通知权限默认授予")
            }
            PermissionRow("悬浮窗（SYSTEM_ALERT_WINDOW）", overlayGranted, label = if (overlayGranted) "已开通" else "未开通") {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
        }

        // ---------- ② 麦克风电平表 ----------
        SectionCard("② 麦克风电平表") {
            Text("环境电平（1.5s 采样 RMS dB）：", style = MaterialTheme.typography.bodyMedium)
            Text(micLevel, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                enabled = !micBusy && micGranted,
                onClick = {
                    micBusy = true
                    micLevel = "测量中…"
                    SelftestLog.record("开始测量环境电平")
                    scope.launch {
                        val res = runCatching { measureMicLevelDb(1500) }
                        micBusy = false
                        res.onSuccess { dB ->
                            val verdict = when {
                                dB >= -30 -> "（较吵，适合点名检测）"
                                dB >= -45 -> "（正常课堂环境）"
                                else -> "（安静，建议调高 VAD 阈值）"
                            }
                            micLevel = "%.1f dB".format(dB) + verdict
                            SelftestLog.record("电平测量完成: %.1f dB".format(dB))
                        }.onFailure { e ->
                            micLevel = "测量失败：${e.message}"
                            SelftestLog.record("电平测量失败: ${e.message}")
                        }
                    }
                },
            ) { Text(if (micBusy) "测量中…" else "开始测麦") }
            if (!micGranted) {
                Text(
                    "未授权录音，请先在①中授权",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // ---------- ③ ASR 测试 ----------
        SectionCard("③ ASR 转写测试") {
            Text("录音 3 秒 → TeleSpeechEngine（硅基流动）转写 → 显示结果与耗时", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                enabled = !asrBusy && micGranted,
                onClick = {
                    asrBusy = true
                    asrResult = "录音转写中…"
                    SelftestLog.record("开始 ASR 测试（3s 录音）")
                    scope.launch {
                        val res = runCatching {
                            withContext(Dispatchers.Default) {
                                val chunks = mutableListOf<ShortArray>()
                                MicRecorder.record(3000) { chunks += it }
                                val engine = TeleSpeechEngine(AppConfig.siliconApiKey)
                                val start = SystemClock.elapsedRealtime()
                                var text: String? = null
                                withTimeoutOrNull(20_000) {
                                    engine.transcribe(flow { chunks.forEach { emit(it) } })
                                        .collect { if (text == null) text = it }
                                } ?: run { text = null }
                                (text ?: "(超时无结果)") to (SystemClock.elapsedRealtime() - start)
                            }
                        }
                        asrBusy = false
                        res.onSuccess { (text, costMs) ->
                            asrResult = "「${text.ifBlank { "(未检测到有效语音)" }}」 ${costMs}ms"
                            SelftestLog.record("ASR 测试完成（${costMs}ms）: ${text.take(60)}")
                        }.onFailure { e ->
                            asrResult = "转写失败：${e.message}"
                            SelftestLog.record("ASR 测试失败: ${e.message}")
                        }
                    }
                },
            ) { Text(if (asrBusy) "转写中…" else "录音 3 秒转写") }
        }

        // ---------- ④ LLM 测试 ----------
        SectionCard("④ LLM 延迟测试") {
            Text("向配置的 LLM 发送固定问题，统计首字延迟与完整回答", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                enabled = !llmBusy,
                onClick = {
                    llmBusy = true
                    llmResult = "请求中…"
                    SelftestLog.record("开始 LLM 测试")
                    scope.launch {
                        val res = runCatching {
                            withContext(Dispatchers.Default) {
                                val ai = repo.aiSettingsFlow.first()
                                val cfg = LlmConfig(ai.baseUrl, ai.apiKey, ai.model)
                                val client = LlmClient()
                                val start = SystemClock.elapsedRealtime()
                                var firstMs = -1L
                                val sb = StringBuilder()
                                withTimeoutOrNull(30_000) {
                                    client.streamChat(
                                        messages = listOf(
                                            mapOf("role" to "system", "content" to "你是测试助手，请简短回答。"),
                                            mapOf("role" to "user", "content" to "用一句话说明你是谁"),
                                        ),
                                        cfg = cfg,
                                    ).collect { piece ->
                                        if (firstMs < 0) firstMs = SystemClock.elapsedRealtime() - start
                                        sb.append(piece)
                                    }
                                } ?: sb.append("（30s 超时截断）")
                                val total = SystemClock.elapsedRealtime() - start
                                Triple(firstMs, sb.toString().ifBlank { "(空响应)" }, total)
                            }
                        }
                        llmBusy = false
                        res.onSuccess { (firstMs, full, totalMs) ->
                            llmResult = "首字 ${if (firstMs < 0) "无输出" else "${firstMs}ms"} · 全文 ${full.take(80)}…（${totalMs}ms）"
                            SelftestLog.record("LLM 测试完成（${totalMs}ms）, 首字 ${firstMs}ms")
                        }.onFailure { e ->
                            llmResult = "LLM 失败：${e.message}"
                            SelftestLog.record("LLM 测试失败: ${e.message}")
                        }
                    }
                },
            ) { Text(if (llmBusy) "请求中…" else "测试 LLM") }
        }

        // ---------- ⑤ 模拟事件 ----------
        SectionCard("⑤ 模拟事件（全链路）") {
            Text("触发 → 五通道提醒 → 悬浮窗 → 实时总线，走完整分发链路", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    val name = AppConfig.names.value.firstOrNull()?.display ?: "张三"
                    val event = ClassEvent(EventType.ROLLCALL, "老师点到 $name", "模拟触发（自检）", System.currentTimeMillis())
                    runCatching { coordinator.fire(event, context) }
                        .onFailure { e -> SelftestLog.record("点名链路失败: ${e.message}") }
                    LiveStreamBus.pushEvent(event)
                    LiveStreamBus.pushSegment("（模拟）老师点到 $name")
                    LiveStreamBus.pushState(PipelineState.Listening(1))
                    FloatAnswerWindow.showRollcall(context, event.triggerText)
                    SelftestLog.record("模拟点名: $name（已分发提醒通道）")
                }) { Text("模拟点名") }
                Button(onClick = {
                    val question = "请简述光合作用的基本原理"
                    val event = ClassEvent(EventType.QUESTION, question, "模拟触发（自检）", System.currentTimeMillis())
                    runCatching { coordinator.fire(event, context) }
                        .onFailure { e -> SelftestLog.record("提问链路失败: ${e.message}") }
                    LiveStreamBus.pushEvent(event)
                    LiveStreamBus.pushSegment("（模拟）$question")
                    LiveStreamBus.pushState(PipelineState.Listening(1))
                    FloatAnswerWindow.show(context, question)
                    SelftestLog.record("模拟提问: $question（未弹窗请检查悬浮窗权限）")
                    scope.launch {
                        val res = runCatching {
                            val ai = repo.aiSettingsFlow.first()
                            val cfg = LlmConfig(ai.baseUrl, ai.apiKey, ai.model)
                            val style = if (repo.answerStyleFlow.first() == "academic") AnswerStyle.ACADEMIC else AnswerStyle.TERSENESS
                            AnswerService().answer(question, "模拟课堂（自检）", style, cfg).collect { piece ->
                                FloatAnswerWindow.appendAnswer(piece)
                            }
                        }
                        res.onFailure { e -> SelftestLog.record("模拟提问答案生成失败: ${e.message}") }
                    }
                }) { Text("模拟提问") }
            }
        }

        // ---------- ⑥ 运行日志 ----------
        SectionCard("⑥ 运行日志") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("内存环形缓冲（最近 200 条）", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { SelftestLog.clear() }) { Text("清空") }
            }
            if (logEntries.isEmpty()) {
                Text("暂无日志", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                logEntries.takeLast(60).asReversed().forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // ---------- ⑦ 崩溃摘要 ----------
        SectionCard("⑦ 崩溃摘要") {
            Text("未检测到崩溃记录", style = MaterialTheme.typography.titleMedium)
            Text(
                "Phase 6 占位：正式版将接入 DropBoxManager/Crashlytics 摘要",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ----------------------------------------------------------------------
// 工具
// ----------------------------------------------------------------------

@Composable
private fun PermissionRow(
    name: String,
    granted: Boolean,
    label: String,
    onRequest: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row {
            Text(
                "● ",
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Text(name, style = MaterialTheme.typography.bodyMedium)
        }
        if (granted) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            TextButton(onClick = onRequest) { Text("去授权") }
        }
    }
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** 短录音工具：麦克风 16k 单声道 PCM → 回调分块 */
private object MicRecorder {
    suspend fun record(durationMs: Int, onChunk: (ShortArray) -> Unit) = withContext(Dispatchers.IO) {
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 1600) * 2,
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }
        rec.startRecording()
        try {
            val buf = ShortArray(maxOf(minBuf, 1600))
            var recorded = 0L
            val target = sampleRate * durationMs / 1000L
            while (recorded < target) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                recorded += n
                onChunk(buf.copyOf(n))
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }
}

private suspend fun measureMicLevelDb(durationMs: Int): Double {
    var sumSq = 0.0
    var count = 0L
    MicRecorder.record(durationMs) { chunk ->
        for (s in chunk) {
            sumSq += s.toDouble() * s
            count++
        }
    }
    if (count == 0L) return -100.0
    return 20 * log10(sqrt(sumSq / count) / 32768.0)
}

/** 自检运行日志（内存环形缓冲，UI 持有 StateFlow） */
object SelftestLog {
    private val _entries = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    val entries: kotlinx.coroutines.flow.StateFlow<List<String>> = _entries

    fun record(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _entries.value = (_entries.value + "[$ts] $msg").takeLast(200)
    }

    fun clear() {
        _entries.value = emptyList()
    }
}