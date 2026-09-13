package com.classsentinel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.classsentinel.core.detect.PersonalizedNameTargetEvent
import com.classsentinel.core.llm.AnswerResult
import com.classsentinel.core.llm.answerFailureMessage
import com.classsentinel.core.pipeline.PipelineState
import com.classsentinel.service.ListenService
import com.classsentinel.service.LiveAnswerState
import com.classsentinel.service.LiveStreamBus
import com.classsentinel.service.LiveTranscriptLine
import com.classsentinel.ui.components.AuroraCard
import com.classsentinel.ui.components.ScreenHeader
import com.classsentinel.ui.components.StatusPill
import com.classsentinel.ui.isSessionActive
import com.classsentinel.ui.theme.ClassSentinelSpacing

internal fun liveStateText(state: PipelineState): String = when (state) {
    PipelineState.Idle -> "未在监听"
    PipelineState.Starting -> "正在启动监听…"
    is PipelineState.Listening -> "监听中 · 已转写 ${state.sentences} 句"
    is PipelineState.Recovering -> "正在恢复监听：${state.message}"
    PipelineState.Stopping -> "正在停止监听…"
    is PipelineState.Error -> "出错：${state.message}"
}

internal const val HISTORY_PERSISTENCE_DEGRADED_MESSAGE = "本节部分历史保存失败"

internal fun historyPersistenceWarning(degraded: Boolean): String? =
    HISTORY_PERSISTENCE_DEGRADED_MESSAGE.takeIf { degraded }

internal fun liveTranscriptDisplay(lines: List<LiveTranscriptLine>): List<String> =
    lines.asReversed().map { line ->
        when (line) {
            is LiveTranscriptLine.Partial -> "${line.text}（正在识别）"
            is LiveTranscriptLine.Final -> line.text
        }
    }

internal fun liveTranscriptKey(line: LiveTranscriptLine): Int = line.utteranceId

internal fun suspectedNameTargetWarning(event: PersonalizedNameTargetEvent): String =
    "可能叫到「${event.targetName}」，请留意"

internal fun liveAnswerLabel(answer: LiveAnswerState): String = when (val result = answer.result) {
    AnswerResult.Generating -> "正在生成答案…"
    is AnswerResult.Streaming -> result.text
    is AnswerResult.Succeeded -> result.answer
    is AnswerResult.Insufficient -> "依据不足"
    is AnswerResult.Failed -> answerFailureMessage(result.safeCode)
}

/** Live prioritizes the authoritative state, the newest answer, and the replaceable transcript. */
@Composable
fun LiveScreen() {
    val context = LocalContext.current
    val transcript by LiveStreamBus.transcript.collectAsState()
    val latestAnswer by LiveStreamBus.latestAnswer.collectAsState()
    val pipelineState by LiveStreamBus.pipelineState.collectAsState()
    val historyDegraded by LiveStreamBus.historyDegraded.collectAsState()
    val suspectedNameTarget by LiveStreamBus.suspectedNameTarget.collectAsState()
    val primaryAction = livePrimaryActionUi(pipelineState)
    val controls = rememberListeningControls()
    val sessionActive = pipelineState.isSessionActive()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = ClassSentinelSpacing.lg,
            vertical = ClassSentinelSpacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(ClassSentinelSpacing.md),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                ScreenHeader(
                    eyebrow = "LIVE SESSION",
                    title = "实时监听",
                    description = "状态、回答和转写都在这里保持最新。",
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { LiveStreamBus.clear() }) {
                    Text("清空显示")
                }
            }
        }

        item {
            AuroraCard(
                containerColor = if (sessionActive) {
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
                            label = if (sessionActive) "运行中" else "已暂停",
                            active = sessionActive,
                        )
                    }
                    Text(
                        liveStateText(pipelineState),
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (sessionActive) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Button(
                        onClick = { performLivePrimaryAction(context, primaryAction.action, controls.requestStart) },
                        enabled = primaryAction.enabled && !controls.preparing,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text(if (controls.preparing) "准备监听…" else primaryAction.label, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        historyPersistenceWarning(historyDegraded)?.let { warning ->
            item {
                AuroraCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                    Text(
                        warning,
                        modifier = Modifier.padding(ClassSentinelSpacing.md),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        suspectedNameTarget?.let { target ->
            item {
                AuroraCard(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(ClassSentinelSpacing.md),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(ClassSentinelSpacing.xs),
                        ) {
                            Text("疑似点名", style = MaterialTheme.typography.labelLarge)
                            Text(
                                suspectedNameTargetWarning(target),
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        TextButton(onClick = LiveStreamBus::clearSuspectedNameTarget) {
                            Text("知道了")
                        }
                    }
                }
            }
        }

        latestAnswer?.let { answer ->
            item {
                AuroraCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                    Column(
                        modifier = Modifier.padding(ClassSentinelSpacing.xl),
                        verticalArrangement = Arrangement.spacedBy(ClassSentinelSpacing.sm),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("AI 最新回答", style = MaterialTheme.typography.titleMedium)
                            StatusPill(
                                label = when (answer.result) {
                                    AnswerResult.Generating -> "生成中"
                                    is AnswerResult.Streaming -> "实时输出"
                                    is AnswerResult.Succeeded -> "已完成"
                                    is AnswerResult.Insufficient -> "依据不足"
                                    is AnswerResult.Failed -> "未完成"
                                },
                                active = answer.result is AnswerResult.Succeeded ||
                                    answer.result is AnswerResult.Streaming,
                            )
                        }
                        Text(answer.question, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            liveAnswerLabel(answer),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        if (answer.eventId != null &&
                            (answer.result is AnswerResult.Failed || answer.result is AnswerResult.Insufficient)
                        ) {
                            TextButton(onClick = { ListenService.retryAnswer(context, answer.eventId) }) {
                                Text("重试回答")
                            }
                        }
                    }
                }
            }
        }

        item {
            Text("实时转写", style = MaterialTheme.typography.titleLarge)
        }

        if (transcript.isEmpty()) {
            item {
                AuroraCard {
                    Text(
                        "点击首页「开始监听」后，final 转写会出现在这里。",
                        modifier = Modifier.padding(ClassSentinelSpacing.xl),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(
                items = transcript.asReversed(),
                key = ::liveTranscriptKey,
            ) { line ->
                AuroraCard(
                    containerColor = if (line is LiveTranscriptLine.Partial) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                ) {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = ClassSentinelSpacing.md,
                            vertical = ClassSentinelSpacing.sm,
                        ),
                        verticalArrangement = Arrangement.spacedBy(ClassSentinelSpacing.xs),
                    ) {
                        if (line is LiveTranscriptLine.Partial) {
                            Text(
                                "正在识别 · 可替换",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            text = when (line) {
                                is LiveTranscriptLine.Partial -> line.text
                                is LiveTranscriptLine.Final -> line.text
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (line is LiveTranscriptLine.Partial) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Compatibility predicate for the retained transcript-marker data tests; no marker UI uses it. */
internal fun canMarkLatest(activeCourseId: Long?, latestChunkId: Long?): Boolean =
    activeCourseId != null && latestChunkId != null
