package com.classsentinel.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import com.classsentinel.core.llm.AiConnectivityChecker
import com.classsentinel.core.llm.AiConnectionStatus
import com.classsentinel.core.llm.AiProviderPreset
import com.classsentinel.core.llm.LlmAiConnectivityChecker
import com.classsentinel.core.llm.AnswerTriggerMode
import com.classsentinel.data.AiSettings
import com.classsentinel.data.AnswerHistoryRepository
import com.classsentinel.data.AppDatabase
import com.classsentinel.data.Channels
import com.classsentinel.data.SettingsRepository
import com.classsentinel.data.SettingsRepositoryHolder
import com.classsentinel.ui.components.AuroraCard
import com.classsentinel.ui.components.ScreenHeader
import com.classsentinel.ui.components.SettingsRow
import com.classsentinel.ui.AiSetupState
import com.classsentinel.ui.aiRetrySuggestion
import com.classsentinel.ui.aiSetupFailureMessage
import com.classsentinel.ui.saveAndCheckAi
import com.classsentinel.ui.toAiSetupState
import com.classsentinel.ui.theme.ClassSentinelSpacing
import com.classsentinel.worker.AsrSettingsActionCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class SettingsHubPage {
    OVERVIEW,
    IDENTITY,
    ALERTS,
    AI,
    RECOVERY,
    GENERAL,
}

/**
 * Settings is intentionally a hub plus focused sub-pages. Every control still calls the
 * existing repository/coordinator entry point; this file only changes how those controls are
 * grouped and presented.
 */
@Composable
fun SettingsHubScreen() {
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
    val ai by repo.aiDraftSettingsFlow.collectAsState(initial = defaultAiSettingsForUi())
    val aiStatus by repo.aiConnectionStatusFlow.collectAsState(initial = AiConnectionStatus.UNVERIFIED)
    val answerLength by repo.answerLengthFlow.collectAsState(initial = "mid")
    val answerStyle by repo.answerStyleFlow.collectAsState(initial = "terseness")
    val streamOutput by repo.streamOutputFlow.collectAsState(initial = true)
    val answerTriggerMode by repo.answerTriggerModeFlow.collectAsState(initial = AnswerTriggerMode.DEFAULT)
    val darkMode by repo.darkModeFlow.collectAsState(initial = "system")
    val asrEngine by repo.asrEngineFlow.collectAsState(initial = "telespeech")
    val asrActions = remember(context, repo) { AsrSettingsActionCoordinator.create(context, repo) }
    val aiConnectivityChecker = remember { LlmAiConnectivityChecker() }

    var pageName by rememberSaveable { mutableStateOf(SettingsHubPage.OVERVIEW.name) }
    val page = runCatching { SettingsHubPage.valueOf(pageName) }.getOrDefault(SettingsHubPage.OVERVIEW)
    if (page != SettingsHubPage.OVERVIEW) {
        BackHandler { pageName = SettingsHubPage.OVERVIEW.name }
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

    Box(Modifier.fillMaxSize()) {
        when (page) {
            SettingsHubPage.OVERVIEW -> SettingsOverview(
                names = names,
                chVibrate = chVibrate,
                chNotify = chNotify,
                ai = ai,
                asrEngine = asrEngine,
                darkMode = darkMode,
                onOpen = { pageName = it.name },
            )
            SettingsHubPage.IDENTITY -> SettingsIdentityPage(
                names = names,
                preset = preset,
                rollcallSec = rollcallSec,
                questionSec = questionSec,
                qLevel = qLevel,
                draftName = draftName,
                draftAliases = draftAliases,
                draftAsrVariants = draftAsrVariants,
                onDraftNameChange = { draftName = it },
                onDraftAliasesChange = { draftAliases = it },
                onDraftAsrVariantsChange = { draftAsrVariants = it },
                save = ::saveSnap,
                onBack = { pageName = SettingsHubPage.OVERVIEW.name },
                onAddName = {
                    val display = draftName.trim()
                    if (display.isNotBlank()) {
                        val aliases = draftAliases.split(',', '，').map(String::trim).filter(String::isNotBlank)
                        val asrVariants = draftAsrVariants.split(',', '，').map(String::trim).filter(String::isNotBlank)
                        saveSnap {
                            repo.saveNameList(names + NameEntry(display, aliases, asrVariants))
                        }
                        draftName = ""
                        draftAliases = ""
                        draftAsrVariants = ""
                    }
                },
                onDeleteName = { entry -> saveSnap { repo.saveNameList(names - entry) } },
                onPreset = { saveSnap { repo.saveSensitivityPreset(it) } },
                onRollcall = { saveSnap { repo.saveRollcallSuppressMs(it) } },
                onQuestion = { saveSnap { repo.saveQuestionSuppressMs(it) } },
                onLevel = { saveSnap { repo.saveQuestionWordLevel(it) } },
            )
            SettingsHubPage.ALERTS -> SettingsAlertsPage(
                context = context,
                chVibrate = chVibrate,
                chNotify = chNotify,
                vibrateMode = vibrateMode,
                questionAlertMode = questionAlertMode,
                save = ::saveSnap,
                onBack = { pageName = SettingsHubPage.OVERVIEW.name },
            )
            SettingsHubPage.AI -> SettingsAiPage(
                ai = ai,
                answerLength = answerLength,
                answerStyle = answerStyle,
                streamOutput = streamOutput,
                answerTriggerMode = answerTriggerMode,
                checker = aiConnectivityChecker,
                persistedStatus = aiStatus,
                save = ::saveSnap,
                onBack = { pageName = SettingsHubPage.OVERVIEW.name },
            )
            SettingsHubPage.RECOVERY -> SettingsRecoveryPage(
                asrEngine = asrEngine,
                asrSiliconKeyDraft = asrSiliconKeyDraft,
                asrConfigMessage = asrConfigMessage,
                onKeyChange = { asrSiliconKeyDraft = it },
                save = ::saveSnap,
                onBack = { pageName = SettingsHubPage.OVERVIEW.name },
                onEngine = { engine ->
                    saveSnap {
                        val resumed = asrActions.saveEngine(engine)
                        asrConfigMessage = if (resumed) {
                            "引擎已保存，已恢复失败音频队列"
                        } else {
                            "引擎已保存，当前凭证未就绪或没有待恢复音频"
                        }
                    }
                },
                onSaveKey = {
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
            )
            SettingsHubPage.GENERAL -> SettingsGeneralPage(
                darkMode = darkMode,
                showClearDialog = { showClearDialog = true },
                onDarkMode = { saveSnap { repo.saveDarkMode(it) } },
                onBack = { pageName = SettingsHubPage.OVERVIEW.name },
            )
        }
        clearMessage?.let { message ->
            AuroraCard(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(ClassSentinelSpacing.md),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    message,
                    modifier = Modifier.padding(horizontal = ClassSentinelSpacing.md, vertical = ClassSentinelSpacing.sm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
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
                            val cleared = clearHistoryIfConfirmed(confirmed = true) {
                                answerHistory.clearHistory()
                            }
                            clearMessage = "已清空 ${cleared ?: 0} 条问答"
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

@Composable
private fun SettingsOverview(
    names: List<NameEntry>,
    chVibrate: Boolean,
    chNotify: Boolean,
    ai: AiSettings,
    asrEngine: String,
    darkMode: String,
    onOpen: (SettingsHubPage) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = ClassSentinelSpacing.lg, vertical = ClassSentinelSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(ClassSentinelSpacing.sm),
    ) {
        item {
            ScreenHeader(
                eyebrow = "PREFERENCES",
                title = "设置",
                description = "把常用设置分开管理，课堂中只保留必要的注意力。",
                modifier = Modifier.padding(bottom = ClassSentinelSpacing.sm),
            )
        }
        item {
            SettingsRow(
                icon = Icons.Filled.Person,
                title = "姓名与识别",
                summary = names.firstOrNull()?.display?.let { "$it · 识别灵敏度与抑制窗口" } ?: "设置姓名、称呼与识别规则",
                onClick = { onOpen(SettingsHubPage.IDENTITY) },
            )
        }
        item {
            SettingsRow(
                icon = Icons.Filled.Notifications,
                title = "提醒",
                summary = buildString {
                    append(if (chVibrate) "振动" else "不振动")
                    append(" · ")
                    append(if (chNotify) "通知已开" else "通知已关")
                },
                onClick = { onOpen(SettingsHubPage.ALERTS) },
            )
        }
        item {
            SettingsRow(
                icon = Icons.Filled.AutoAwesome,
                title = "AI 答题",
                summary = ai.model.ifBlank { "尚未配置回答服务" },
                onClick = { onOpen(SettingsHubPage.AI) },
            )
        }
        item {
            SettingsRow(
                icon = Icons.Filled.CloudSync,
                title = "失败音频恢复",
                summary = recoveryEngineLabel(asrEngine),
                onClick = { onOpen(SettingsHubPage.RECOVERY) },
            )
        }
        item {
            AuroraCard {
                Column(
                    modifier = Modifier.padding(ClassSentinelSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(ClassSentinelSpacing.xs),
                ) {
                    Text("语音识别模型", style = MaterialTheme.typography.titleMedium)
                    Text("X-ASR 中英增强模型", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "已内置 · 离线可用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            SettingsRow(
                icon = Icons.Filled.Palette,
                title = "数据与通用",
                summary = when (darkMode) {
                    "on" -> "深色模式"
                    "off" -> "浅色模式"
                    else -> "跟随系统"
                },
                onClick = { onOpen(SettingsHubPage.GENERAL) },
            )
        }
    }
}

@Composable
private fun SettingsSubpageHeader(
    title: String,
    description: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设置")
        }
        ScreenHeader(
            eyebrow = "SETTINGS",
            title = title,
            description = description,
            modifier = Modifier.weight(1f).padding(top = ClassSentinelSpacing.xs),
        )
    }
}

@Composable
private fun SettingsIdentityPage(
    names: List<NameEntry>,
    preset: String,
    rollcallSec: Long,
    questionSec: Long,
    qLevel: Int,
    draftName: String,
    draftAliases: String,
    draftAsrVariants: String,
    onDraftNameChange: (String) -> Unit,
    onDraftAliasesChange: (String) -> Unit,
    onDraftAsrVariantsChange: (String) -> Unit,
    save: (((suspend () -> Unit)) -> Unit),
    onBack: () -> Unit,
    onAddName: () -> Unit,
    onDeleteName: (NameEntry) -> Unit,
    onPreset: (String) -> Unit,
    onRollcall: (Long) -> Unit,
    onQuestion: (Long) -> Unit,
    onLevel: (Int) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = ClassSentinelSpacing.lg, vertical = ClassSentinelSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(ClassSentinelSpacing.md),
    ) {
        item {
            SettingsSubpageHeader("姓名与识别", "姓名是定向提问和点名识别的依据。", onBack)
        }
        item {
            SettingsCard("已保存姓名") {
                names.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = ClassSentinelSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(entry.display, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "称呼：${entry.aliases.joinToString("、").ifBlank { "无" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "ASR 变体：${entry.asrVariants.joinToString("、").ifBlank { "无" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onDeleteName(entry) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除${entry.display}")
                        }
                    }
                }
                OutlinedTextField(
                    value = draftName,
                    onValueChange = onDraftNameChange,
                    label = { Text("姓名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draftAliases,
                    onValueChange = onDraftAliasesChange,
                    label = { Text("可称呼昵称，可选") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draftAsrVariants,
                    onValueChange = onDraftAsrVariantsChange,
                    label = { Text("ASR 变体，可选") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = onAddName, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(ClassSentinelSpacing.xs))
                    Text("添加姓名")
                }
            }
        }
        item {
            SettingsCard("识别规则") {
                Text("识别灵敏度", style = MaterialTheme.typography.titleMedium)
                HubRadioRow(
                    options = listOf("strict" to "严格", "standard" to "标准", "loose" to "宽松"),
                    selected = preset,
                    onSelect = onPreset,
                )
                var roll by remember(rollcallSec) { mutableFloatStateOf(rollcallSec.toFloat() / 1000f) }
                HubSliderRow(
                    title = "点名抑制窗口",
                    valueLabel = "${roll.toInt()} 秒",
                    value = roll,
                    range = 10f..300f,
                    onValueChange = { roll = it },
                    onValueChangeFinished = { onRollcall(roll.toInt() * 1000L) },
                )
                var question by remember(questionSec) { mutableFloatStateOf(questionSec.toFloat() / 1000f) }
                HubSliderRow(
                    title = "提问抑制窗口",
                    valueLabel = "${question.toInt()} 秒",
                    value = question,
                    range = 30f..600f,
                    onValueChange = { question = it },
                    onValueChangeFinished = { onQuestion(question.toInt() * 1000L) },
                )
                Text("问题触发词：${hubLevelLabel(qLevel)}", style = MaterialTheme.typography.bodyMedium)
                HubRadioRow(
                    options = listOf("1" to "少", "2" to "中", "3" to "多"),
                    selected = qLevel.toString(),
                    onSelect = { onLevel(it.toInt()) },
                )
            }
        }
    }
}

@Composable
private fun SettingsAlertsPage(
    context: android.content.Context,
    chVibrate: Boolean,
    chNotify: Boolean,
    vibrateMode: String,
    questionAlertMode: QuestionAlertMode,
    save: (((suspend () -> Unit)) -> Unit),
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = ClassSentinelSpacing.lg, vertical = ClassSentinelSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(ClassSentinelSpacing.md),
    ) {
        item { SettingsSubpageHeader("提醒", "把需要你抬头的时刻交给轻量反馈。", onBack) }
        item {
            SettingsCard("提醒通道") {
                HubSwitchRow("振动提醒", checked = chVibrate) { save { SettingsRepositoryHolder.get(context).setChannelEnabled(Channels.VIBRATE, it) } }
                HubSwitchRow("系统通知", checked = chNotify) { save { SettingsRepositoryHolder.get(context).setChannelEnabled(Channels.NOTIFY, it) } }
                Text(
                    "答案通知的锁屏内容固定隐藏，不提供会改变隐私契约的开关。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsCard("振动") {
                HubRadioRow(
                    options = listOf("gentle" to "轻柔", "normal" to "标准", "strong" to "强震"),
                    selected = vibrateMode,
                    onSelect = { save { SettingsRepositoryHolder.get(context).saveVibrationMode(it) } },
                )
                val notificationGranted = android.os.Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                Text(
                    if (notificationGranted) "通知权限：已授权" else "通知权限：请在系统设置中授权",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsCard("问题提醒") {
                HubQuestionAlertModeOption(QuestionAlertMode.ALL_QUESTIONS, questionAlertMode, "所有问题", "检测到课堂提问就提醒", context, save)
                HubQuestionAlertModeOption(QuestionAlertMode.TARGETED_ONLY, questionAlertMode, "只提醒点到我的问题", "只有老师明确点到我时提醒", context, save)
                HubQuestionAlertModeOption(QuestionAlertMode.OFF, questionAlertMode, "关闭问题提醒", "仍记录问题，但不震动/通知", context, save)
            }
        }
    }
}

@Composable
private fun SettingsAiPage(
    ai: AiSettings,
    answerLength: String,
    answerStyle: String,
    streamOutput: Boolean,
    answerTriggerMode: AnswerTriggerMode,
    checker: AiConnectivityChecker,
    persistedStatus: AiConnectionStatus,
    save: (((suspend () -> Unit)) -> Unit),
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { SettingsRepositoryHolder.get(context) }
    val scope = rememberCoroutineScope()
    var baseUrl by remember(ai.baseUrl) { mutableStateOf(ai.baseUrl) }
    var apiKey by remember(ai.apiKey) { mutableStateOf(ai.apiKey) }
    var model by remember(ai.model) { mutableStateOf(ai.model) }
    var visible by remember { mutableStateOf(false) }
    var connectionState by remember(persistedStatus) {
        mutableStateOf<AiSetupState>(persistedStatus.toAiSetupState())
    }

    fun markEditing() {
        if (connectionState !is AiSetupState.Checking &&
            connectionState !is AiSetupState.Connected &&
            connectionState !is AiSetupState.CapabilityChecking &&
            connectionState !is AiSetupState.Retrying
        ) {
            connectionState = AiSetupState.Editing
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = ClassSentinelSpacing.lg, vertical = ClassSentinelSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(ClassSentinelSpacing.md),
    ) {
        item { SettingsSubpageHeader("AI 答题", "AI 只在触发条件满足后生成回答；设置本身不会改变监听。", onBack) }
        item {
            SettingsCard("服务连接") {
                OutlinedTextField(
                    baseUrl,
                    { baseUrl = it; markEditing() },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; markEditing() },
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
                OutlinedTextField(
                    model,
                    { model = it; markEditing() },
                    label = { Text("模型") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(ClassSentinelSpacing.xs)) {
                    OutlinedButton(
                        onClick = {
                            baseUrl = AiProviderPreset.DEEPSEEK_OFFICIAL.baseUrl
                            model = AiProviderPreset.DEEPSEEK_OFFICIAL.model
                            markEditing()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("DeepSeek") }
                    OutlinedButton(
                        onClick = {
                            baseUrl = AiProviderPreset.SILICON_FLOW.baseUrl
                            model = AiProviderPreset.SILICON_FLOW.model
                            markEditing()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("硅基") }
                    OutlinedButton(
                        onClick = {
                            baseUrl = AiProviderPreset.COMMAND_CODE.baseUrl
                            model = AiProviderPreset.COMMAND_CODE.model
                            markEditing()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Command") }
                }
                when (val current = connectionState) {
                    AiSetupState.Unconfigured -> Text("尚未验证 AI 配置")
                    AiSetupState.Editing -> Text("保存时会发送一次最小真实推理请求，确认模型可用")
                    AiSetupState.Checking -> Text("正在检查 AI 模型…")
                    AiSetupState.Connected,
                    AiSetupState.CapabilityChecking,
                    -> Text("服务已连接，正在检查 ClassSentinel 正式能力…")
                    is AiSetupState.Retrying -> Text(
                        "正在重试（第 ${current.attempt}/${current.maxAttempts} 次）：" +
                            aiSetupFailureMessage(current.reason) +
                            (aiRetrySuggestion(current.retryAfterMs)?.let { "；$it" } ?: ""),
                    )
                    is AiSetupState.Unverified -> Text(
                        buildString {
                            append("配置已保存，但当前仍未验证")
                            current.reason?.let { append("：${aiSetupFailureMessage(it)}") }
                            aiRetrySuggestion(current.retryAfterMs)?.let { append("；$it") }
                        },
                    )
                    is AiSetupState.Incompatible -> Text(
                        current.reason?.let(::aiSetupFailureMessage)
                            ?: "当前配置与 ClassSentinel 正式能力不兼容，请更换模型或服务",
                        color = MaterialTheme.colorScheme.error,
                    )
                    AiSetupState.Ready -> Text("AI 模型已验证可用")
                    is AiSetupState.Failed -> Text(
                        aiSetupFailureMessage(current.reason) +
                            if (current.attempts > 1) "（已尝试 ${current.attempts} 次）" else "",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = {
                        connectionState = AiSetupState.Checking
                        scope.launch {
                            val result = saveAndCheckAi(
                                draft = AiSettings(baseUrl, apiKey, model),
                                save = settings::saveAiDraft,
                                checker = checker,
                                onConnectivityState = { progress ->
                                    connectionState = progress.toAiSetupState()
                                },
                                saveVerified = settings::saveAiVerified,
                                saveStatus = settings::saveAiConnectionStatus,
                            )
                            connectionState = result
                        }
                    },
                    enabled = connectionState !is AiSetupState.Checking &&
                        connectionState !is AiSetupState.Connected &&
                        connectionState !is AiSetupState.CapabilityChecking &&
                        connectionState !is AiSetupState.Retrying,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存并检查 AI") }
            }
        }
        item {
            SettingsCard("回答呈现") {
                Text("回答长度", style = MaterialTheme.typography.titleMedium)
                HubRadioRow(listOf("short" to "简短", "mid" to "适中", "long" to "详细"), answerLength) { save { SettingsRepositoryHolder.get(context).saveAnswerLength(it) } }
                Text("答案风格", style = MaterialTheme.typography.titleMedium)
                HubRadioRow(listOf("terseness" to "口语化", "academic" to "要点化"), answerStyle) { save { SettingsRepositoryHolder.get(context).saveAnswerStyle(it) } }
                HubSwitchRow("流式输出", "逐段显示答案", streamOutput) { save { SettingsRepositoryHolder.get(context).saveStreamOutput(it) } }
            }
        }
        item {
            SettingsCard("自动回答") {
                HubAnswerTriggerModeOption(AnswerTriggerMode.ALL_QUESTIONS, answerTriggerMode, "所有问题", "检测到课堂提问时自动生成答案", save)
                HubAnswerTriggerModeOption(AnswerTriggerMode.TARGETED_ONLY, answerTriggerMode, "只回答点到我的问题", "只有老师明确点名并提问时自动生成答案", save)
                HubAnswerTriggerModeOption(AnswerTriggerMode.OFF, answerTriggerMode, "关闭自动回答", "仍记录提问，但不自动调用 AI", save)
            }
        }
    }
}

@Composable
private fun SettingsRecoveryPage(
    asrEngine: String,
    asrSiliconKeyDraft: String,
    asrConfigMessage: String?,
    onKeyChange: (String) -> Unit,
    save: (((suspend () -> Unit)) -> Unit),
    onBack: () -> Unit,
    onEngine: (String) -> Unit,
    onSaveKey: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = ClassSentinelSpacing.lg, vertical = ClassSentinelSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(ClassSentinelSpacing.md),
    ) {
        item { SettingsSubpageHeader("失败音频恢复", "只处理已经进入 PENDING 的失败音频，不上传实时本地 ASR 音频。", onBack) }
        item {
            SettingsCard("恢复引擎") {
                HubRadioRow(
                    options = listOf("telespeech" to "SiliconFlow XingChen", "sensevoice" to "SiliconFlow SenseVoice", "xunfei" to "讯飞（需已有凭证）"),
                    selected = asrEngine,
                    onSelect = onEngine,
                )
                OutlinedTextField(
                    value = asrSiliconKeyDraft,
                    onValueChange = onKeyChange,
                    label = { Text("SiliconFlow ASR API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = onSaveKey, modifier = Modifier.fillMaxWidth()) { Text("保存 ASR credential") }
                asrConfigMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun SettingsGeneralPage(
    darkMode: String,
    showClearDialog: () -> Unit,
    onDarkMode: (String) -> Unit,
    onBack: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = ClassSentinelSpacing.lg, vertical = ClassSentinelSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(ClassSentinelSpacing.md),
    ) {
        item { SettingsSubpageHeader("数据与通用", "管理主题和问答记录；监听数据与姓名配置不会被误删。", onBack) }
        item {
            SettingsCard("语音识别模型") {
                Text("X-ASR 中英增强模型", style = MaterialTheme.typography.titleMedium)
                Text("已内置 · 离线可用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SettingsCard("主题") {
                DropdownRowHub(
                    options = listOf("system" to "跟随系统", "on" to "深色", "off" to "浅色"),
                    selected = darkMode,
                    onSelect = onDarkMode,
                )
            }
        }
        item {
            SettingsCard("历史") {
                OutlinedButton(onClick = showClearDialog, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(ClassSentinelSpacing.xs))
                    Text("清空问答历史")
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    AuroraCard {
        Column(
            modifier = Modifier.padding(ClassSentinelSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(ClassSentinelSpacing.sm),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun HubSwitchRow(title: String, subtitle: String? = null, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = ClassSentinelSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun HubSliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value, onValueChange, valueRange = range, onValueChangeFinished = onValueChangeFinished)
    }
}

@Composable
private fun HubRadioRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        options.forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(value) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = value == selected, onClick = { onSelect(value) })
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun HubQuestionAlertModeOption(
    mode: QuestionAlertMode,
    selected: QuestionAlertMode,
    title: String,
    subtitle: String,
    context: android.content.Context,
    save: (((suspend () -> Unit)) -> Unit),
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable {
            save { SettingsRepositoryHolder.get(context).saveQuestionAlertMode(mode) }
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = mode == selected,
            onClick = { save { SettingsRepositoryHolder.get(context).saveQuestionAlertMode(mode) } },
        )
        Column(Modifier.padding(vertical = ClassSentinelSpacing.xs)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HubAnswerTriggerModeOption(
    mode: AnswerTriggerMode,
    selected: AnswerTriggerMode,
    title: String,
    subtitle: String,
    save: (((suspend () -> Unit)) -> Unit),
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable { save { SettingsRepositoryHolder.get(context).saveAnswerTriggerMode(mode) } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = mode == selected,
            onClick = { save { SettingsRepositoryHolder.get(context).saveAnswerTriggerMode(mode) } },
        )
        Column(Modifier.padding(vertical = ClassSentinelSpacing.xs)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DropdownRowHub(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
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

private fun recoveryEngineLabel(engine: String): String = when (engine) {
    "sensevoice" -> "SiliconFlow SenseVoice"
    "xunfei" -> "讯飞（需已有凭证）"
    else -> "SiliconFlow XingChen"
}

private fun hubLevelLabel(level: Int): String = when (level) {
    1 -> "少（1级）"
    2 -> "中（2级）"
    else -> "多（3级）"
}


internal fun defaultAiSettingsForUi(): AiSettings = SettingsRepository.DEFAULT_AI_SETTINGS

/** Only execute the destructive action after an explicit confirmation. */
internal suspend fun <T> clearHistoryIfConfirmed(
    confirmed: Boolean,
    clear: suspend () -> T,
): T? = if (confirmed) clear() else null
