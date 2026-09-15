package com.classsentinel.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.classsentinel.core.detect.NameEntry
import com.classsentinel.core.llm.AiConnectivityChecker
import com.classsentinel.core.llm.AiConnectionStatus
import com.classsentinel.core.llm.LlmNameVariantGenerator
import com.classsentinel.core.llm.LlmAiConnectivityChecker
import com.classsentinel.core.llm.NameVariantGenerator
import com.classsentinel.core.speech.NameVoiceCalibrator
import com.classsentinel.core.speech.NameVariantMergePolicy
import com.classsentinel.core.speech.X480NameVoiceCalibrator
import com.classsentinel.data.AiSettings
import com.classsentinel.data.SettingsRepository
import com.classsentinel.data.SettingsRepositoryHolder
import com.classsentinel.ui.AI_NAME_PRIVACY_NOTICE
import com.classsentinel.ui.AiSetupState
import com.classsentinel.ui.NameOnboardingResult
import com.classsentinel.ui.OnboardingStep
import com.classsentinel.ui.aiRetrySuggestion
import com.classsentinel.ui.aiSetupFailureMessage
import com.classsentinel.ui.canSkipAi
import com.classsentinel.ui.hasValidNameConfiguration
import com.classsentinel.ui.initialAiSetupState
import com.classsentinel.ui.initialOnboardingStep
import com.classsentinel.ui.prepareOnboardingName
import com.classsentinel.ui.saveAndCheckAi
import com.classsentinel.ui.toAiSetupState
import com.classsentinel.ui.AI_NAME_VOICE_PRIVACY_NOTICE
import com.classsentinel.ui.components.AuroraCard
import com.classsentinel.ui.components.ScreenHeader
import com.classsentinel.ui.components.StatusPill
import com.classsentinel.ui.theme.ClassSentinelSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 首启引导：AI 配置 → 姓名/识别配置 → 麦克风/通知授权；AI 不可用也不阻塞继续。 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    nameVariantGenerator: NameVariantGenerator? = null,
    aiConnectivityChecker: AiConnectivityChecker? = null,
    nameVoiceCalibrator: NameVoiceCalibrator? = null,
) {
    val context = LocalContext.current
    val settings = remember { SettingsRepositoryHolder.get(context) }
    val scope = rememberCoroutineScope()
    val generator = remember(settings, nameVariantGenerator) {
        nameVariantGenerator ?: LlmNameVariantGenerator(
            settingsProvider = { settings.aiSettingsFlow.first() },
        )
    }
    val checker = remember(aiConnectivityChecker) {
        aiConnectivityChecker ?: LlmAiConnectivityChecker()
    }
    val voiceCalibrator = remember(nameVoiceCalibrator, context) {
        nameVoiceCalibrator ?: X480NameVoiceCalibrator.create(context)
    }
    var step by remember { mutableStateOf<OnboardingStep?>(null) }
    var initialAiSettings by remember { mutableStateOf<AiSettings?>(null) }
    var initialAiStatus by remember { mutableStateOf<AiConnectionStatus?>(null) }
    var aiReady by remember { mutableStateOf(false) }
    var pendingNameEntry by remember { mutableStateOf<NameEntry?>(null) }
    var savingCalibratedName by remember { mutableStateOf(false) }
    var calibrationSaveError by remember { mutableStateOf<String?>(null) }
    var setupMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var audioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var notifyGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val audioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { audioGranted = it }
    val notifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { notifyGranted = it }

    LaunchedEffect(settings) {
        val aiSettings = settings.aiDraftSettingsFlow.first()
        val aiStatus = settings.aiConnectionStatusFlow.first()
        val names = settings.nameListFlow.first()
        val completed = settings.onboardingCompletedFlow.first()
        initialAiSettings = aiSettings
        initialAiStatus = aiStatus
        aiReady = aiStatus == AiConnectionStatus.READY
        step = initialOnboardingStep(
            aiConfigured = aiReady,
            hasName = hasValidNameConfiguration(names),
            onboardingCompleted = completed,
        )
        if (completed) onDone()
    }

    fun saveCalibratedName(variants: List<String>) {
        val pending = pendingNameEntry ?: return
        if (savingCalibratedName) return
        savingCalibratedName = true
        calibrationSaveError = null
        scope.launch {
            try {
                val finalEntry = pending.copy(
                    asrVariants = NameVariantMergePolicy.sanitizeMergedVariants(pending.display, variants),
                )
                settings.saveNameList(
                    listOf(finalEntry),
                    markOnboardingNameSaved = true,
                )
                step = OnboardingStep.Permissions
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                calibrationSaveError = "姓名识别配置保存失败，请重试"
            } finally {
                savingCalibratedName = false
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ClassSentinelSpacing.lg, vertical = ClassSentinelSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ScreenHeader(
                eyebrow = "FIRST RUN",
                title = "让课堂哨兵认识你",
                description = "先完成必要设置，之后每次上课都能更安静地开始。",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(ClassSentinelSpacing.xl))
            OnboardingProgress(step)
            Spacer(Modifier.height(ClassSentinelSpacing.lg))
            AuroraCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(ClassSentinelSpacing.xl),
                    verticalArrangement = Arrangement.spacedBy(ClassSentinelSpacing.md),
                ) {
                    when (step) {
                        null -> {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Text("正在读取已保存配置…", style = MaterialTheme.typography.bodySmall)
                        }
                        OnboardingStep.AiConfig -> {
                            StepAiConfig(
                                initialSettings = checkNotNull(initialAiSettings),
                                initialStatus = checkNotNull(initialAiStatus),
                                settings = settings,
                                checker = checker,
                                onReady = {
                                    aiReady = true
                                    step = OnboardingStep.NameConfig
                                },
                                onSkip = {
                                    aiReady = false
                                    step = OnboardingStep.NameConfig
                                },
                            )
                        }
                        OnboardingStep.NameConfig -> {
                            StepName(
                                settings = settings,
                                generator = generator,
                                aiReady = aiReady,
                                onNext = { result, message ->
                                    setupMessage = message
                                    when (result) {
                                        is NameOnboardingResult.Saved -> {
                                            pendingNameEntry = result.entry
                                            calibrationSaveError = null
                                            step = OnboardingStep.VoiceNameCalibration
                                        }
                                        is NameOnboardingResult.ExistingConfiguration -> {
                                            pendingNameEntry = result.entry
                                            step = OnboardingStep.Permissions
                                        }
                                    }
                                },
                            )
                        }
                        OnboardingStep.VoiceNameCalibration -> {
                            val pending = pendingNameEntry
                            if (pending == null) {
                                Text("正在恢复姓名配置…", style = MaterialTheme.typography.bodySmall)
                            } else {
                                VoiceNameCalibrationScreen(
                                    expectedDisplayName = pending.display,
                                    aiSeedVariants = pending.asrVariants,
                                    calibrator = voiceCalibrator,
                                    microphoneGranted = audioGranted,
                                    onRequestMicrophone = {
                                        audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    },
                                    onFinished = ::saveCalibratedName,
                                    saving = savingCalibratedName,
                                    saveError = calibrationSaveError,
                                )
                            }
                        }
                        OnboardingStep.Permissions -> {
                            StepPermissions(
                                setupMessage = setupMessage,
                                audioGranted = audioGranted,
                                notifyGranted = notifyGranted,
                                onRequestAudio = {
                                    audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                onRequestNotify = {
                                    notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                },
                                onDone = onDone,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingProgress(step: OnboardingStep?) {
    val steps = listOf(
        "AI 配置" to (step == OnboardingStep.AiConfig),
        "姓名识别" to (step == OnboardingStep.NameConfig),
        "声音校准" to (step == OnboardingStep.VoiceNameCalibration),
        "权限" to (step == OnboardingStep.Permissions),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ClassSentinelSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEach { (label, current) ->
            StatusPill(
                label = label,
                active = current,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StepAiConfig(
    initialSettings: AiSettings,
    initialStatus: AiConnectionStatus,
    settings: SettingsRepository,
    checker: AiConnectivityChecker,
    onReady: () -> Unit,
    onSkip: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var baseUrl by remember(initialSettings) { mutableStateOf(initialSettings.baseUrl) }
    var apiKey by remember(initialSettings) { mutableStateOf(initialSettings.apiKey) }
    var model by remember(initialSettings) { mutableStateOf(initialSettings.model) }
    var state by remember(initialSettings, initialStatus) {
        mutableStateOf(initialAiSetupState(initialSettings, initialStatus))
    }

    fun markEditing() {
        if (state !is AiSetupState.Checking &&
            state !is AiSetupState.Connected &&
            state !is AiSetupState.CapabilityChecking &&
            state !is AiSetupState.Retrying
        ) {
            state = AiSetupState.Editing
        }
    }

    Text("配置 AI 服务（可选）", style = MaterialTheme.typography.titleLarge)
    Text(
        "用于自动生成姓名识别容错。没有配置也可以继续使用课堂监听。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = baseUrl,
        onValueChange = { baseUrl = it; markEditing() },
        label = { Text("Base URL") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = apiKey,
        onValueChange = { apiKey = it; markEditing() },
        label = { Text("API Key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = model,
        onValueChange = { model = it; markEditing() },
        label = { Text("Model") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text("AI 服务预设", style = MaterialTheme.typography.labelLarge)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedButton(
            onClick = {
                baseUrl = com.classsentinel.core.llm.AiProviderPreset.DEEPSEEK_OFFICIAL.baseUrl
                model = com.classsentinel.core.llm.AiProviderPreset.DEEPSEEK_OFFICIAL.model
                markEditing()
            },
            modifier = Modifier.weight(1f),
        ) { Text("DeepSeek") }
        OutlinedButton(
            onClick = {
                baseUrl = com.classsentinel.core.llm.AiProviderPreset.SILICON_FLOW.baseUrl
                model = com.classsentinel.core.llm.AiProviderPreset.SILICON_FLOW.model
                markEditing()
            },
            modifier = Modifier.weight(1f),
        ) { Text("硅基") }
        OutlinedButton(
            onClick = {
                baseUrl = com.classsentinel.core.llm.AiProviderPreset.COMMAND_CODE.baseUrl
                model = com.classsentinel.core.llm.AiProviderPreset.COMMAND_CODE.model
                markEditing()
            },
            modifier = Modifier.weight(1f),
        ) { Text("Command") }
    }
    Spacer(Modifier.height(12.dp))
    when (val current = state) {
        AiSetupState.Unconfigured -> Text("尚未配置 AI，可暂时跳过")
        AiSetupState.Editing -> Text("保存后会检查当前 AI 服务")
        AiSetupState.Checking -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Spacer(Modifier.width(8.dp))
                Text("正在检查 AI 连接…")
            }
        }
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
        AiSetupState.Ready -> Text("AI 已准备好，将在姓名页启用自动生成")
        is AiSetupState.Failed -> Text(
            aiSetupFailureMessage(current.reason) +
                if (current.attempts > 1) "（已尝试 ${current.attempts} 次）" else "",
            color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = {
            state = AiSetupState.Checking
            scope.launch {
                val result = saveAndCheckAi(
                    draft = AiSettings(baseUrl = baseUrl, apiKey = apiKey, model = model),
                    save = settings::saveAiDraft,
                    checker = checker,
                    onConnectivityState = { progress -> state = progress.toAiSetupState() },
                    saveVerified = settings::saveAiVerified,
                    saveStatus = settings::saveAiConnectionStatus,
                )
                state = result
                if (result is AiSetupState.Ready) onReady()
            }
        },
        enabled = state !is AiSetupState.Checking &&
            state !is AiSetupState.Connected &&
            state !is AiSetupState.CapabilityChecking &&
            state !is AiSetupState.Retrying,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("保存并检查 AI") }
    TextButton(
        onClick = onSkip,
        enabled = canSkipAi(state),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("暂时跳过") }
}

@Composable
private fun StepName(
    settings: SettingsRepository,
    generator: NameVariantGenerator,
    aiReady: Boolean,
    onNext: (NameOnboardingResult, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var display by remember { mutableStateOf("") }
    var aliases by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Text("你的姓名", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = display,
        onValueChange = { display = it },
        label = { Text("姓名（必填）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text("别人平时也会这样叫你（可选）", style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = aliases,
        onValueChange = { aliases = it },
        label = { Text("昵称/别名（可选，多个用逗号分隔）") },
        placeholder = { Text("例：阿淦，淦哥") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "AI 只生成可能的 ASR 误识别文本，不会替你猜昵称或别名。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!aiReady) {
        Spacer(Modifier.height(8.dp))
        Text("AI 识别配置暂不可用", color = MaterialTheme.colorScheme.error)
        Text("你仍可以完成设置，之后可在设置中补充。")
    }
    Spacer(Modifier.height(24.dp))
    Text(AI_NAME_PRIVACY_NOTICE, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            scope.launch {
                saving = true
                errorMessage = null
                try {
                    val result = prepareOnboardingName(
                        displayName = display,
                        aliasesInput = aliases,
                        existingNames = settings.nameListFlow.first(),
                        generator = generator,
                        aiReady = aiReady,
                    )
                    when (result) {
                        is NameOnboardingResult.Saved -> {
                            onNext(
                                result,
                                if (result.aiGenerated) {
                                    "识别配置完成\n已自动生成 ${result.generatedVariantCount} 个姓名识别容错规则"
                                } else {
                                    "姓名已保存；AI 识别配置暂时未完成，可稍后在设置中补充识别变体"
                                },
                            )
                        }
                        is NameOnboardingResult.ExistingConfiguration -> {
                            onNext(result, "已保留现有姓名配置")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    errorMessage = "姓名保存失败，请重试"
                } finally {
                    saving = false
                }
            }
        },
        enabled = display.trim().isNotEmpty() && !saving,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (aiReady) "AI 帮我完成识别配置" else "继续保存姓名") }
    if (saving) {
        Spacer(Modifier.height(12.dp))
        CircularProgressIndicator()
        Text(if (aiReady) "正在生成识别配置…" else "正在保存姓名…", style = MaterialTheme.typography.bodySmall)
    }
    errorMessage?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun StepPermissions(
    setupMessage: String?,
    audioGranted: Boolean,
    notifyGranted: Boolean,
    onRequestAudio: () -> Unit,
    onRequestNotify: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Text("权限（课堂监听必需）", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(16.dp))
    setupMessage?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
    }
    PermissionRow("麦克风（听老师讲话）", audioGranted) {
        onRequestAudio()
    }
    if (Build.VERSION.SDK_INT >= 33) {
        PermissionRow("通知（点名提醒）", notifyGranted) {
            onRequestNotify()
        }
    }
    Text(
        "AI 答题配置可在完成引导后到「设置 → AI 答题」填写；本地 ASR 模型会在第一次开始监听时安装。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = {
            scope.launch {
                SettingsRepositoryHolder.get(context).saveOnboardingCompleted()
                onDone()
            }
        },
        enabled = audioGranted && notifyGranted,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("完成设置") }
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
