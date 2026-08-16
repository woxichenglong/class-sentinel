package com.classsentinel.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.classsentinel.core.config.AppConfig
import com.classsentinel.core.detect.NameEntry
import com.classsentinel.data.AiSettings
import com.classsentinel.data.Channels
import com.classsentinel.data.SettingsRepositoryHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 设置页：九组设置（点名/提问/语音/提醒/AI/总结/数据/隐私/通用）。
 * 全部经 SettingsRepository 持久化到 DataStore，并即时回写 AppConfig。
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val repo = remember { SettingsRepositoryHolder.get(context) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { repo.load() }

    var showSelfTest by rememberSaveable { mutableStateOf(false) }
    if (showSelfTest) {
        SelfTestScreen()
        return
    }

    val names by repo.nameListFlow.collectAsState(initial = AppConfig.names.value)
    val sensitivity by repo.sensitivityFlow.collectAsState(initial = AppConfig.sensitivity.value)
    val preset by repo.sensitivityPresetFlow.collectAsState(initial = "standard")
    val rollcallSec by repo.rollcallSuppressMsFlow.collectAsState(initial = AppConfig.sensitivity.value.rollcallSuppressMs / 1000)
    val questionSec by repo.questionSuppressMsFlow.collectAsState(initial = AppConfig.sensitivity.value.questionSuppressMs / 1000)
    val vadDb by repo.vadDbFlow.collectAsState(initial = AppConfig.sensitivity.value.vadDb)
    val qLevel by repo.questionWordLevelFlow.collectAsState(initial = AppConfig.sensitivity.value.questionWordLevel)
    val segmentMax by repo.segmentMaxSecFlow.collectAsState(initial = 4)
    val asrEngine by repo.asrEngineFlow.collectAsState(initial = "telespeech")
    val chVibrate by repo.channelFlow(Channels.VIBRATE).collectAsState(initial = Channels.DEFAULT.contains(Channels.VIBRATE))
    val chRingtone by repo.channelFlow(Channels.RINGTONE).collectAsState(initial = Channels.DEFAULT.contains(Channels.RINGTONE))
    val chNotify by repo.channelFlow(Channels.NOTIFY).collectAsState(initial = Channels.DEFAULT.contains(Channels.NOTIFY))
    val chFlash by repo.channelFlow(Channels.FLASH).collectAsState(initial = Channels.DEFAULT.contains(Channels.FLASH))
    val chEar by repo.channelFlow(Channels.EAR).collectAsState(initial = Channels.DEFAULT.contains(Channels.EAR))
    val lockscreenNotify by repo.lockscreenNotifyFlow.collectAsState(initial = true)
    val vibrateMode by repo.vibrationModeFlow.collectAsState(initial = "normal")
    val ringtoneVolume by repo.ringtoneVolumeFlow.collectAsState(initial = 80)
    val ai by repo.aiSettingsFlow.collectAsState(initial = AiSettings("", AppConfig.siliconApiKey, ""))
    val answerLength by repo.answerLengthFlow.collectAsState(initial = "mid")
    val answerStyle by repo.answerStyleFlow.collectAsState(initial = "terseness")
    val streamOutput by repo.streamOutputFlow.collectAsState(initial = true)
    val autoSummary by repo.autoSummaryFlow.collectAsState(initial = false)
    val retentionDays by repo.retentionDaysFlow.collectAsState(initial = "30")
    val darkMode by repo.darkModeFlow.collectAsState(initial = "system")

    // ---- 名字表编辑草稿 ----
    var draftName by remember { mutableStateOf("") }
    var draftVariants by remember { mutableStateOf("") }

    fun saveSnap(fn: suspend () -> Unit) { scope.launch { runCatching { fn() }.onFailure { println("[Settings] 保存失败: ${it.message}") } } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("设置", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 16.dp)) }

        // ================= 1. 点名 =================
        item {
            SectionCard("点名") {
                Text("名字表", style = MaterialTheme.typography.titleSmall)
                names.forEach { entry ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.display, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "变体：${entry.variants.take(4).joinToString("、").ifEmpty { "无" }}" +
                                    if (entry.variants.size > 4) " 等${entry.variants.size}个" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { saveSnap { repo.saveNameList(names - entry) } }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除${entry.display}")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draftName,
                        onValueChange = { draftName = it },
                        label = { Text("姓名") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = draftVariants,
                        onValueChange = { draftVariants = it },
                        label = { Text("变体(逗号分隔)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = {
                        val display = draftName.trim()
                        if (display.isNotEmpty()) {
                            val variants = draftVariants.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
                            saveSnap { repo.saveNameList(names + NameEntry(display, variants)) }
                            draftName = ""
                            draftVariants = ""
                        }
                    }) { Icon(Icons.Filled.Add, contentDescription = "添加") }
                }

                Spacer(Modifier.height(8.dp))
                Text("灵敏度档位", style = MaterialTheme.typography.titleSmall)
                RadioRow(
                    options = listOf(
                        "strict" to "严格",
                        "standard" to "标准",
                        "loose" to "宽松",
                    ),
                    selected = preset,
                ) { saveSnap { repo.saveSensitivityPreset(it) } }

                Spacer(Modifier.height(8.dp))
                var roll by remember { mutableFloatStateOf(rollcallSec.toFloat()) }
                SliderRow(
                    title = "点名抑制窗口",
                    valueLabel = "${roll.roundToInt()} 秒",
                    value = roll,
                    range = 10f..300f,
                    onValueChange = { roll = it },
                    onValueChangeFinished = { saveSnap { repo.saveRollcallSuppressMs(roll.roundToInt() * 1000L) } },
                )
            }
        }

        // ================= 2. 提问 =================
        item {
            SectionCard("提问") {
                Text("触发词等级：${levelLabel(qLevel)}", style = MaterialTheme.typography.titleSmall)
                RadioRow(
                    options = listOf("1" to "少", "2" to "中", "3" to "多"),
                    selected = qLevel.toString(),
                ) { saveSnap { repo.saveQuestionWordLevel(it.toInt()) } }

                Spacer(Modifier.height(8.dp))
                var qSec by remember { mutableFloatStateOf(questionSec.toFloat()) }
                SliderRow(
                    title = "提问抑制窗口",
                    valueLabel = "${qSec.roundToInt()} 秒",
                    value = qSec,
                    range = 30f..600f,
                    onValueChange = { qSec = it },
                    onValueChangeFinished = { saveSnap { repo.saveQuestionSuppressMs(qSec.roundToInt() * 1000L) } },
                )
            }
        }

        // ================= 3. 语音 =================
        item {
            SectionCard("语音") {
                var vad by remember { mutableFloatStateOf(vadDb.toFloat()) }
                SliderRow(
                    title = "VAD 静音阈值",
                    valueLabel = "${vad.roundToInt()} dB",
                    value = vad,
                    range = -55f..-20f,
                    onValueChange = { vad = it },
                    onValueChangeFinished = { saveSnap { repo.saveVadDb(vad.roundToInt()) } },
                )
                Spacer(Modifier.height(4.dp))
                var seg by remember { mutableFloatStateOf(segmentMax.toFloat()) }
                SliderRow(
                    title = "分段最长时长",
                    valueLabel = "${seg.roundToInt()} 秒",
                    value = seg,
                    range = 1f..10f,
                    onValueChange = { seg = it },
                    onValueChangeFinished = { saveSnap { repo.saveSegmentMaxSec(seg.roundToInt()) } },
                )
                Spacer(Modifier.height(4.dp))
                Text("ASR 引擎", style = MaterialTheme.typography.bodyMedium)
                DropdownRow(
                    options = listOf(
                        "telespeech" to "TeleSpeech（电信，推荐）",
                        "xunfei" to "讯飞 RTASR（流式）",
                        "sensevoice" to "SenseVoice（兜底）",
                    ),
                    selected = asrEngine,
                ) { saveSnap { repo.saveAsrEngine(it) } }
                Spacer(Modifier.height(8.dp))
                var asrKey by remember { mutableStateOf("") }
                // P0 修复(2026-08-16 CC审查): 冷启动空快照不得覆盖已存 ASR key
                var asrTouched by remember { mutableStateOf(false) }
                LaunchedEffect(asrKey) {
                    if (!asrTouched) {
                        asrTouched = true
                        return@LaunchedEffect
                    }
                    delay(400)
                    runCatching { repo.saveAsrSiliconKey(asrKey.trim()) }
                        .onFailure { e -> println("[Settings] ASR key 保存失败: ${e.message}") }
                }
                LaunchedEffect(Unit) {
                    delay(800) // 等 MainActivity 的 load() 完成
                    val current = AppConfig.siliconApiKey
                    if (current.isNotEmpty()) asrKey = current
                }
                OutlinedTextField(
                    value = asrKey,
                    onValueChange = { asrKey = it },
                    label = { Text("硅基流动 ASR Key（转写用）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ================= 4. 提醒 =================
        item {
            SectionCard("提醒") {
                SwitchRow("震动提醒", checked = chVibrate) { saveSnap { repo.setChannelEnabled(Channels.VIBRATE, it) } }
                SwitchRow("铃声提醒", checked = chRingtone) { saveSnap { repo.setChannelEnabled(Channels.RINGTONE, it) } }
                SwitchRow("系统通知", checked = chNotify) { saveSnap { repo.setChannelEnabled(Channels.NOTIFY, it) } }
                SwitchRow("全屏闪屏", "锁屏亮屏大字提醒", checked = chFlash) { saveSnap { repo.setChannelEnabled(Channels.FLASH, it) } }
                SwitchRow("耳机提示音", checked = chEar) { saveSnap { repo.setChannelEnabled(Channels.EAR, it) } }
                SwitchRow("锁屏通知", "锁屏界面显示提醒", checked = lockscreenNotify) { saveSnap { repo.saveLockscreenNotify(it) } }

                Spacer(Modifier.height(8.dp))
                Text("震动模式", style = MaterialTheme.typography.titleSmall)
                RadioRow(
                    options = listOf("gentle" to "轻柔", "normal" to "标准", "strong" to "强震"),
                    selected = vibrateMode,
                ) { saveSnap { repo.saveVibrationMode(it) } }

                Spacer(Modifier.height(8.dp))
                var vol by remember { mutableFloatStateOf(ringtoneVolume.toFloat()) }
                SliderRow(
                    title = "铃声音量",
                    valueLabel = "${vol.roundToInt()}%",
                    value = vol,
                    range = 0f..100f,
                    onValueChange = { vol = it },
                    onValueChangeFinished = { saveSnap { repo.saveRingtoneVolume(vol.roundToInt()) } },
                )
            }
        }

        // ================= 5. AI =================
        item {
            SectionCard("AI") {
                var baseUrl by remember { mutableStateOf(ai.baseUrl) }
                var apiKey by remember { mutableStateOf(ai.apiKey) }
                var model by remember { mutableStateOf(ai.model) }
                // P0 修复(2026-08-16 CC审查): 冷启动快照不得覆盖已存配置
                // ①首帧(快照值)不触发保存 ②flow 发射真实值后同步字段
                var aiTouched by remember { mutableStateOf(false) }
                LaunchedEffect(baseUrl, apiKey, model) {
                    if (!aiTouched) {
                        aiTouched = true
                        return@LaunchedEffect
                    }
                    delay(400)
                    runCatching { repo.saveAiSettings(AiSettings(baseUrl.trim(), apiKey.trim(), model.trim())) }
                        .onFailure { e -> println("[Settings] AI 保存失败: ${e.message}") }
                }
                var aiSynced by remember { mutableStateOf(false) }
                LaunchedEffect(ai) {
                    if (!aiSynced && (ai.baseUrl.isNotEmpty() || ai.apiKey.isNotEmpty() || ai.model.isNotEmpty())) {
                        baseUrl = ai.baseUrl
                        apiKey = ai.apiKey
                        model = ai.model
                        aiSynced = true
                    }
                }
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        baseUrl = "https://api.deepseek.com"
                        model = "deepseek-v4-flash"
                    }) { Text("DeepSeek 官方预设") }
                    OutlinedButton(onClick = {
                        baseUrl = "https://api.siliconflow.cn/v1"
                        model = "deepseek-ai/DeepSeek-V4-Flash"
                    }) { Text("硅基流动预设") }
                }

                Spacer(Modifier.height(8.dp))
                Text("回答长度", style = MaterialTheme.typography.titleSmall)
                RadioRow(
                    options = listOf("short" to "简短", "mid" to "适中", "long" to "详细"),
                    selected = answerLength,
                ) { saveSnap { repo.saveAnswerLength(it) } }

                Spacer(Modifier.height(8.dp))
                Text("答案风格", style = MaterialTheme.typography.titleSmall)
                RadioRow(
                    options = listOf("terseness" to "口语化", "academic" to "要点化"),
                    selected = answerStyle,
                ) { saveSnap { repo.saveAnswerStyle(it) } }

                Spacer(Modifier.height(4.dp))
                SwitchRow("流式输出", "逐字显示答案", checked = streamOutput) { saveSnap { repo.saveStreamOutput(it) } }
            }
        }

        // ================= 6. 总结 =================
        item {
            SectionCard("总结") {
                SwitchRow("自动总结", "课后自动生成课堂摘要", checked = autoSummary) { saveSnap { repo.saveAutoSummary(it) } }
            }
        }

        // ================= 7. 数据 =================
        item {
            SectionCard("数据") {
                Text("历史保留", style = MaterialTheme.typography.bodyMedium)
                DropdownRow(
                    options = listOf(
                        "7" to "7 天",
                        "30" to "30 天",
                        "90" to "90 天",
                        "forever" to "永久保留",
                    ),
                    selected = retentionDays,
                ) { saveSnap { repo.saveRetentionDays(it) } }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { println("[Settings] 清空历史（占位：待历史库接入）") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("清空历史记录（占位）") }
            }
        }

        // ================= 8. 隐私 =================
        item {
            SectionCard("隐私") {
                Text(
                    "数据流向说明：\n" +
                        "· 名字表、灵敏度、AI Key 等全部设置仅保存在本机 DataStore，不上传任何服务器；\n" +
                        "· 转写文本仅发往你配置的 ASR 引擎用于识别；\n" +
                        "· 提问答案仅发往你配置的 LLM 服务；\n" +
                        "· 通知/悬浮窗/麦克风权限仅在本应用内使用，可随时在系统设置中撤销。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ================= 9. 通用 =================
        item {
            SectionCard("通用") {
                Text("深色模式", style = MaterialTheme.typography.bodyMedium)
                DropdownRow(
                    options = listOf("system" to "跟随系统", "on" to "深色", "off" to "浅色"),
                    selected = darkMode,
                ) { saveSnap { repo.saveDarkMode(it) } }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { showSelfTest = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("进入自检调试页")
                }
            }
        }
    }
}

private fun levelLabel(level: Int): String = when (level) {
    1 -> "少（1级）"
    2 -> "中（2级）"
    else -> "多（3级）"
}

// ----------------------------------------------------------------------
// 通用小组件
// ----------------------------------------------------------------------

@Composable
internal fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(valueLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range, onValueChangeFinished = onValueChangeFinished)
    }
}

@Composable
private fun RadioRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            Row(
                Modifier.weight(1f).clickable { onSelect(value) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = value == selected, onClick = { onSelect(value) })
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DropdownRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    options.firstOrNull { it.first == selected }?.second ?: selected,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, display) ->
                    DropdownMenuItem(
                        text = { Text(display) },
                        onClick = {
                            expanded = false
                            onSelect(value)
                        },
                    )
                }
            }
        }
    }
}