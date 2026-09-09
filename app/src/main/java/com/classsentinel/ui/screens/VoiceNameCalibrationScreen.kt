package com.classsentinel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.classsentinel.core.speech.NameCalibrationController
import com.classsentinel.core.speech.NameCalibrationFailure
import com.classsentinel.core.speech.NameCalibrationPreparation
import com.classsentinel.core.speech.NameVoiceCalibrator
import com.classsentinel.ui.AI_NAME_VOICE_PRIVACY_NOTICE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 三次短姓名采样的 Compose 投影；不展示 transcript，也不持久化音频。 */
@Composable
internal fun VoiceNameCalibrationScreen(
    expectedDisplayName: String,
    aiSeedVariants: List<String>,
    calibrator: NameVoiceCalibrator,
    microphoneGranted: Boolean,
    onRequestMicrophone: () -> Unit,
    onFinished: (List<String>) -> Unit,
    saving: Boolean = false,
    saveError: String? = null,
) {
    val controller = remember(expectedDisplayName, aiSeedVariants, calibrator) {
        NameCalibrationController(expectedDisplayName, aiSeedVariants, calibrator)
    }
    var state by remember(controller) { mutableStateOf(controller.state) }
    var permissionRequested by remember(controller) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(microphoneGranted, controller) {
        if (!microphoneGranted && !permissionRequested) {
            permissionRequested = true
            onRequestMicrophone()
        } else if (microphoneGranted && controller.state.preparation != NameCalibrationPreparation.READY) {
            state = state.copy(
                preparation = NameCalibrationPreparation.PREPARING,
                prepareFailure = null,
                lastFailure = null,
            )
            state = controller.prepare()
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Text("让课堂哨兵听听你的名字", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text("请像老师点名时一样，自然说出自己的姓名 3 次。")
        Text(
            AI_NAME_VOICE_PRIVACY_NOTICE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        CalibrationAttemptRow("第 1 次", state.completedSlots >= 1)
        CalibrationAttemptRow("第 2 次", state.completedSlots >= 2)
        CalibrationAttemptRow("第 3 次", state.completedSlots >= 3)

        if (!microphoneGranted) {
            Spacer(Modifier.height(16.dp))
            Text("需要麦克风权限才能进行本地姓名校准")
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    permissionRequested = true
                    onRequestMicrophone()
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("请求麦克风权限") }
            TextButton(
                onClick = {
                    controller.skipAll()
                    onFinished(controller.mergedVariants())
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("暂时跳过") }
        } else if (state.preparation == NameCalibrationPreparation.PREPARING ||
            state.preparation == NameCalibrationPreparation.NOT_STARTED
        ) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
            Text("正在准备 X-ASR 模型…", style = MaterialTheme.typography.bodySmall)
        } else if (state.preparation == NameCalibrationPreparation.FAILED) {
            Spacer(Modifier.height(12.dp))
            state.prepareFailure?.let { failure ->
                Text(
                    nameCalibrationFailureMessage(failure),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    state = state.copy(
                        preparation = NameCalibrationPreparation.PREPARING,
                        prepareFailure = null,
                        lastFailure = null,
                    )
                    scope.launch { state = controller.prepare() }
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("重试准备 X-ASR") }
            TextButton(
                onClick = {
                    controller.skipAll()
                    state = controller.state
                    onFinished(controller.mergedVariants())
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("暂时跳过校准") }
        } else if (saving) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator()
            Text("正在保存姓名识别配置…", style = MaterialTheme.typography.bodySmall)
        } else {
            state.lastFailure?.let { failure ->
                Spacer(Modifier.height(12.dp))
                Text(
                    nameCalibrationFailureMessage(failure),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.isListening) {
                Spacer(Modifier.height(12.dp))
                CircularProgressIndicator()
                Text("正在用 X-ASR 识别这一遍…", style = MaterialTheme.typography.bodySmall)
            } else if (state.finished && saveError != null) {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onFinished(controller.mergedVariants()) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("重试保存") }
            } else if (!state.finished) {
                val nextSlot = (state.completedSlots + 1).coerceAtMost(NameCalibrationController.MAX_SAMPLES)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        state = state.copy(isListening = true, lastFailure = null)
                        scope.launch {
                            try {
                                val next = controller.captureNext()
                                state = next
                                if (next.finished) onFinished(controller.mergedVariants())
                            } catch (error: CancellationException) {
                                throw error
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.lastFailure == null) "开始第 $nextSlot 次" else "重试第 $nextSlot 次")
                }
                if (state.lastFailure != null) {
                    OutlinedButton(
                        onClick = {
                            val next = controller.skipCurrent()
                            state = next
                            if (next.finished) onFinished(controller.mergedVariants())
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("跳过本次") }
                }
                TextButton(
                    onClick = {
                        controller.skipAll()
                        onFinished(controller.mergedVariants())
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("暂时跳过") }
            }
        }
        saveError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun CalibrationAttemptRow(label: String, completed: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Text(if (completed) "✓" else "○", style = MaterialTheme.typography.titleMedium)
    }
}

private fun nameCalibrationFailureMessage(failure: NameCalibrationFailure): String = when (failure) {
    NameCalibrationFailure.INVALID_INPUT -> "姓名无效，请返回姓名页检查"
    NameCalibrationFailure.PREPARE_REQUIRED -> "X-ASR 尚未准备好，请稍后重试"
    NameCalibrationFailure.MICROPHONE -> "麦克风暂不可用，可以重试或跳过"
    NameCalibrationFailure.MODEL_UNAVAILABLE -> "X-ASR 暂不可用，可以重试或跳过"
    NameCalibrationFailure.TIMEOUT -> "本次识别超时，可以重试"
    NameCalibrationFailure.EMPTY_TRANSCRIPT -> "没有得到有效姓名识别，可以重试"
    NameCalibrationFailure.ASR_RUNTIME -> "X-ASR 识别失败，可以重试或跳过"
    NameCalibrationFailure.UNKNOWN -> "姓名校准失败，可以重试或跳过"
}
