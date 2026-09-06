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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.classsentinel.core.alert.QuestionAlertMode
import com.classsentinel.core.detect.NameEntry
import com.classsentinel.core.llm.AiProviderPreset
import com.classsentinel.core.llm.AnswerTriggerMode
import com.classsentinel.core.speech.ModelReadinessChecker
import com.classsentinel.core.speech.ModelProfiles
import com.classsentinel.data.AiSettings
import com.classsentinel.data.AnswerHistoryRepository
import com.classsentinel.data.AppDatabase
import com.classsentinel.data.Channels
import com.classsentinel.data.SettingsRepository
import com.classsentinel.data.SettingsRepositoryHolder
import com.classsentinel.worker.AsrSettingsActionCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import android.Manifest
import android.content.pm.PackageManager

/** Student settings: identity, answer provider, safe reminders, local model, and cleanup. */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val repo = remember { SettingsRepositoryHolder.get(context) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { repo.load() }

    val database = remember { AppDatabase.get(context) }
    val answerHistory = remember(database) { AnswerHistoryRepository(database.eventDao()) }
    val names by repo.nameListFlow.collectAsState(initial = emptyList())
    val preset by repo.sensitivityPresetFlow.collectAsState(initial = "standard")
    val rollcallSec by repo.rollcallSuppressMsFlow.collectAsState(initial = 60_000L)
    val questionSec by repo.questionSuppressMsFlow.collectAsState(initial = 120_000L)
    val qLevel by repo.questionWordLevelFlow.collectAsState(initial = 2)
    val chVibrate by repo.channelFlow(Channels.VIBRATE).collectAsState(initial = true)
    val chNotify by repo.channelFlow(Channels.NOTIFY).collectAsState(initial = true)
    val questionAlertMode by repo.questionAlertModeFlow.collectAsState(initial = QuestionAlertMode.DEFAULT)
    val vibrateMode by repo.vibrationModeFlow.collectAsState(initial = "normal")
    val ai by repo.aiSettingsFlow.collectAsState(initial = defaultAiSettingsForUi())
    val answerLength by repo.answerLengthFlow.collectAsState(initial = "mid")
    val answerStyle by repo.answerStyleFlow.collectAsState(initial = "terseness")
    val streamOutput by repo.streamOutputFlow.collectAsState(initial = true)
    val answerTriggerMode by repo.answerTriggerModeFlow.collectAsState(initial = AnswerTriggerMode.DEFAULT)
    val darkMode by repo.darkModeFlow.collectAsState(initial = "system")
    val localAsrModelId by repo.localAsrModelIdFlow.collectAsState(initial = ModelProfiles.ZIPFORMER_ZH_14M.id)
    val asrEngine by repo.asrEngineFlow.collectAsState(initial = "telespeech")
    val localAsrProfile = ModelProfiles.resolveDaily(localAsrModelId)
    val asrActions = remember(context, repo) { AsrSettingsActionCoordinator.create(context, repo) }
    val readinessChecker = remember(context.filesDir) { ModelReadinessChecker(context.filesDir) }
    var localModelReady by remember(localAsrProfile.id) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(localAsrProfile.id) {
        localModelReady = readinessChecker.isReady(localAsrProfile)
    }

    var draftName by rememberSaveable { mutableStateOf("") }
    var draftAliases by rememberSaveable { mutableStateOf("") }
    var draftAsrVariants by rememberSaveable { mutableStateOf("") }
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    var clearMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var asrSiliconKeyDraft by rememberSaveable { mutableStateOf("") }
    var asrConfigMessage by rememberSaveable { mutableStateOf<String?>(null) }

    fun saveSnap(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                clearMessage = "设置保存失败，请重试"
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("设置", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 16.dp)) }

        item {
            SectionCard("姓名与识别") {
                names.forEach { entry ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.display, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "可称呼昵称：${entry.aliases.joinToString("、").ifBlank { "无" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "ASR 变体：${entry.asrVariants.joinToString("、").ifBlank { "无" }}",
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
                        value = draftAliases,
                        onValueChange = { draftAliases = it },
                        label = { Text("可称呼昵称") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = draftAsrVariants,
                        onValueChange = { draftAsrVariants = it },
                        label = { Text("ASR 变体") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        val display = draftName.trim()
                        if (display.isNotBlank()) {
                            val aliases = draftAliases.split(',', '，').map(String::trim).filter(String::isNotBlank)
                            val asrVariants = draftAsrVariants.split(',', '，').map(String::trim).filter(String::isNotBlank)
                            saveSnap {
                                repo.saveNameList(
                                    names + NameEntry(
                                        display = display,
                                        aliases = aliases,
                                        asrVariants = asrVariants,
                                    ),
                                )
                            }
                            draftName = ""
                            draftAliases = ""
                            draftAsrVariants = ""
                        }
                    }) { Icon(Icons.Filled.Add, contentDescription = "添加姓名") }
                }
                Spacer(Modifier.height(8.dp))
                Text("识别灵敏度", style = MaterialTheme.typography.titleSmall)
                RadioRow(
                    options = listOf("strict" to "严格", "standard" to "标准", "loose" to "宽松"),
                    selected = preset,
                    onSelect = { saveSnap { repo.saveSensitivityPreset(it) } },
                )
                Spacer(Modifier.height(8.dp))
                var roll by remember(rollcallSec) { mutableFloatStateOf(rollcallSec.toFloat() / 1000f) }
                SliderRow(
                    title = "点名抑制窗口",
                    valueLabel = "${roll.toInt()} 秒",
                    value = roll,
                    range = 10f..300f,
                    onValueChange = { roll = it },
                    onValueChangeFinished = { saveSnap { repo.saveRollcallSuppressMs(roll.toInt() * 1000L) } },
                )
                var question by remember(questionSec) { mutableFloatStateOf(questionSec.toFloat() / 1000f) }
                SliderRow(
                    title = "提问抑制窗口",
                    valueLabel = "${question.toInt()} 秒",
                    value = question,
                    range = 30f..600f,
                    onValueChange = { question = it },
                    onValueChangeFinished = { saveSnap { repo.saveQuestionSuppressMs(question.toInt() * 1000L) } },
                )
                Text("问题触发词：${levelLabel(qLevel)}", style = MaterialTheme.typography.bodyMedium)
                RadioRow(
                    options = listOf("1" to "少", "2" to "中", "3" to "多"),
                    selected = qLevel.toString(),
                    onSelect = { saveSnap { repo.saveQuestionWordLevel(it.toInt()) } },
                )
            }
        }

        item {
            SectionCard("提醒") {
                SwitchRow("振动提醒", checked = chVibrate) { saveSnap { repo.setChannelEnabled(Channels.VIBRATE, it) } }
                SwitchRow("系统通知", checked = chNotify) { saveSnap { repo.setChannelEnabled(Channels.NOTIFY, it) } }
                Text(
                    "答案通过系统通知显示；锁屏内容固定隐藏，不提供会改变隐私契约的开关。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("震动模式", style = MaterialTheme.typography.titleSmall)
                RadioRow(
                    options = listOf("gentle" to "轻柔", "normal" to "标准", "strong" to "强震"),
                    selected = vibrateMode,
                    onSelect = { saveSnap { repo.saveVibrationMode(it) } },
                )
                val notificationGranted = android.os.Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                Text(
                    if (notificationGranted) "通知权限：已授权" else "通知权限：请在系统设置中授权",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("问题提醒", style = MaterialTheme.typography.titleSmall)
                QuestionAlertModeOption(
                    mode = QuestionAlertMode.ALL_QUESTIONS,
                    selected = questionAlertMode,
                    title = "所有问题",
                    subtitle = "检测到课堂提问就提醒",
                    onSelect = { saveSnap { repo.saveQuestionAlertMode(it) } },
                )
                QuestionAlertModeOption(
                    mode = QuestionAlertMode.TARGETED_ONLY,
                    selected = questionAlertMode,
                    title = "只提醒点到我的问题",
                    subtitle = "只有老师明确点到我时提醒",
                    onSelect = { saveSnap { repo.saveQuestionAlertMode(it) } },
                )
                QuestionAlertModeOption(
                    mode = QuestionAlertMode.OFF,
                    selected = questionAlertMode,
                    title = "关闭问题提醒",
                    subtitle = "仍记录问题，但不震动/通知",
                    onSelect = { saveSnap { repo.saveQuestionAlertMode(it) } },
                )
            }
        }

        item {
            SectionCard("AI 答题") {
                var baseUrl by remember(ai.baseUrl) { mutableStateOf(ai.baseUrl) }
                var apiKey by remember(ai.apiKey) { mutableStateOf(ai.apiKey) }
                var model by remember(ai.model) { mutableStateOf(ai.model) }
                var visible by remember { mutableStateOf(false) }
                var message by rememberSaveable { mutableStateOf<String?>(null) }
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
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (visible) "隐藏 API Key" else "显示 API Key",
                            )
                        }
                    },
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        baseUrl = AiProviderPreset.DEEPSEEK_OFFICIAL.baseUrl
                        model = AiProviderPreset.DEEPSEEK_OFFICIAL.model
                    }) { Text("DeepSeek") }
                    OutlinedButton(onClick = {
                        baseUrl = AiProviderPreset.SILICON_FLOW.baseUrl
                        model = AiProviderPreset.SILICON_FLOW.model
                    }) { Text("硅基流动") }
                    OutlinedButton(onClick = {
                        baseUrl = AiProviderPreset.COMMAND_CODE.baseUrl
                        model = AiProviderPreset.COMMAND_CODE.model
                    }) { Text("Command Code") }
                }
                Button(
                    onClick = {
                        val error = AiProviderPreset.validationError(baseUrl, model)
                        if (error == null) {
                            saveSnap {
                                repo.saveAiSettings(AiProviderPreset.normalizeSettings(AiSettings(baseUrl, apiKey, model)))
                                message = "AI 配置已保存"
                            }
                        } else {
                            message = aiSettingsValidationMessage(error)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存 AI 配置") }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
                Text("回答长度", style = MaterialTheme.typography.titleSmall)
                RadioRow(
                    options = listOf("short" to "简短", "mid" to "适中", "long" to "详细"),
                    selected = answerLength,
                    onSelect = { saveSnap { repo.saveAnswerLength(it) } },
                )
                Text("答案风格", style = MaterialTheme.typography.titleSmall)
                RadioRow(
                    options = listOf("terseness" to "口语化", "academic" to "要点化"),
                    selected = answerStyle,
                    onSelect = { saveSnap { repo.saveAnswerStyle(it) } },
                )
                SwitchRow("流式输出", "逐段显示答案", checked = streamOutput) { saveSnap { repo.saveStreamOutput(it) } }
                Spacer(Modifier.height(8.dp))
                Text("自动回答", style = MaterialTheme.typography.titleSmall)
                AnswerTriggerModeOption(
                    mode = AnswerTriggerMode.ALL_QUESTIONS,
                    selected = answerTriggerMode,
                    title = "所有问题",
                    subtitle = "检测到课堂提问时自动生成答案",
                    onSelect = { saveSnap { repo.saveAnswerTriggerMode(it) } },
                )
                AnswerTriggerModeOption(
                    mode = AnswerTriggerMode.TARGETED_ONLY,
                    selected = answerTriggerMode,
                    title = "只回答点到我的问题",
                    subtitle = "只有老师明确点名并提问时自动生成答案",
                    onSelect = { saveSnap { repo.saveAnswerTriggerMode(it) } },
                )
                AnswerTriggerModeOption(
                    mode = AnswerTriggerMode.OFF,
                    selected = answerTriggerMode,
                    title = "关闭自动回答",
                    subtitle = "仍记录提问，但不自动调用 AI",
                    onSelect = { saveSnap { repo.saveAnswerTriggerMode(it) } },
                )
            }
        }

        item {
            SectionCard("失败音频恢复") {
                Text(
                    "修复离线 ASR 配置后，系统会自动恢复仍在 PENDING 的失败音频；不会上传实时本地 ASR 音频。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("恢复引擎", style = MaterialTheme.typography.titleSmall)
                RadioRow(
                    options = listOf(
                        "telespeech" to "SiliconFlow XingChen",
                        "sensevoice" to "SiliconFlow SenseVoice",
                        "xunfei" to "讯飞（需已有凭证）",
                    ),
                    selected = asrEngine,
                    onSelect = { engine ->
                        saveSnap {
                            val resumed = asrActions.saveEngine(engine)
                            asrConfigMessage = if (resumed) {
                                "引擎已保存，已恢复失败音频队列"
                            } else {
                                "引擎已保存，当前凭证未就绪或没有待恢复音频"
                            }
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = asrSiliconKeyDraft,
                    onValueChange = { asrSiliconKeyDraft = it },
                    label = { Text("SiliconFlow ASR API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        saveSnap {
                            val candidate = asrSiliconKeyDraft.trim()
                            val resumed = asrActions.saveSiliconKey(candidate)
                            asrSiliconKeyDraft = ""
                            asrConfigMessage = when {
                                candidate.isBlank() -> "空 credential 已保存，不会恢复失败音频"
                                resumed -> "ASR credential 已保存，已恢复失败音频队列"
                                else -> "ASR credential 已保存，当前没有可恢复队列或引擎未就绪"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存 ASR credential") }
                asrConfigMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        item {
            SectionCard("本地转写") {
                Text("选择日常监听模型", style = MaterialTheme.typography.titleSmall)
                DropdownRow(
                    options = ModelProfiles.DAILY_SELECTABLE.map { it.id to it.displayName },
                    selected = localAsrProfile.id,
                    onSelect = { saveSnap { repo.saveLocalAsrModel(it) } },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when (localModelReady) {
                        true -> "模型已就绪，实时转写不会上传音频"
                        false -> if (localAsrProfile == ModelProfiles.X_ASR_480 || localAsrProfile == ModelProfiles.X_ASR_960) {
                            "该 X-ASR 模型尚未导入；开始监听前需先准备模型文件"
                        } else {
                            "模型尚未准备；开始监听前会先在后台准备"
                        }
                        null -> "正在后台检查模型文件…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "切换只影响下一次开始监听；当前会话不会热切换模型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard("数据与通用") {
                OutlinedButton(onClick = { showClearDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("清空问答历史")
                }
                clearMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Text("深色模式", style = MaterialTheme.typography.bodyMedium)
                DropdownRow(
                    options = listOf("system" to "跟随系统", "on" to "深色", "off" to "浅色"),
                    selected = darkMode,
                    onSelect = { saveSnap { repo.saveDarkMode(it) } },
                )
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("确认清空问答历史？") },
            text = { Text("只删除问答卡，不删除姓名设置或本地模型。此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    scope.launch {
                        try {
                            clearMessage = "已清空 ${answerHistory.clearHistory()} 条问答"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            clearMessage = "清空问答历史失败，请重试"
                        }
                    }
                }) { Text("确认清空") }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("取消") } },
        )
    }
}

/** The UI must start from the AI repository default, never from an ASR setting. */
internal fun defaultAiSettingsForUi(): AiSettings = SettingsRepository.DEFAULT_AI_SETTINGS

/** Only execute the destructive action after an explicit confirmation. */
internal suspend fun <T> clearHistoryIfConfirmed(confirmed: Boolean, clear: suspend () -> T): T? =
    if (confirmed) clear() else null

private fun levelLabel(level: Int): String = when (level) {
    1 -> "少（1级）"
    2 -> "中（2级）"
    else -> "多（3级）"
}

private fun aiSettingsValidationMessage(code: String): String = when (code) {
    "BASE_URL_HTTPS_REQUIRED" -> "Base URL 必须使用 https://"
    "MODEL_BLANK" -> "模型不能为空"
    else -> "请检查 Base URL 和模型"
}

@Composable
private fun AnswerTriggerModeOption(
    mode: AnswerTriggerMode,
    selected: AnswerTriggerMode,
    title: String,
    subtitle: String,
    onSelect: (AnswerTriggerMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onSelect(mode) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = mode == selected, onClick = { onSelect(mode) })
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QuestionAlertModeOption(
    mode: QuestionAlertMode,
    selected: QuestionAlertMode,
    title: String,
    subtitle: String,
    onSelect: (QuestionAlertMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onSelect(mode) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = mode == selected, onClick = { onSelect(mode) })
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

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
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
    onValueChangeFinished: () -> Unit,
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
            Row(Modifier.weight(1f).clickable { onSelect(value) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = value == selected, onClick = { onSelect(value) })
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DropdownRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(options.firstOrNull { it.first == selected }?.second ?: selected, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, display) ->
                DropdownMenuItem(text = { Text(display) }, onClick = { expanded = false; onSelect(value) })
            }
        }
    }
}