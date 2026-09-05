package com.classsentinel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.classsentinel.core.llm.AnswerResult
import com.classsentinel.core.llm.answerFailureMessage
import com.classsentinel.core.pipeline.PipelineState
import com.classsentinel.service.ListenService
import com.classsentinel.service.LiveAnswerState
import com.classsentinel.service.LiveStreamBus
import com.classsentinel.service.LiveTranscriptLine

internal fun liveStateText(state: PipelineState): String = when (state) {
    PipelineState.Idle -> "未在监听"
    PipelineState.Starting -> "正在启动监听…"
    is PipelineState.Listening -> "监听中 · 已转写 ${state.sentences} 句"
    is PipelineState.Recovering -> "正在恢复监听：${state.message}"
    PipelineState.Stopping -> "正在停止监听…"
    is PipelineState.Error -> "出错：${state.message}"
}

internal fun liveTranscriptDisplay(lines: List<LiveTranscriptLine>): List<String> =
    lines.asReversed().map { line ->
        when (line) {
            is LiveTranscriptLine.Partial -> "${line.text}（正在识别）"
            is LiveTranscriptLine.Final -> line.text
        }
    }

internal fun liveAnswerLabel(answer: LiveAnswerState): String = when (val result = answer.result) {
    AnswerResult.Generating -> "正在生成答案…"
    is AnswerResult.Streaming -> result.text
    is AnswerResult.Succeeded -> result.answer
    is AnswerResult.Insufficient -> "依据不足"
    is AnswerResult.Failed -> answerFailureMessage(result.safeCode)
}

/** Live transcript view: authoritative final lines, replaceable partial, and latest answer. */
@Composable
fun LiveScreen() {
    val context = LocalContext.current
    val transcript by LiveStreamBus.transcript.collectAsState()
    val latestAnswer by LiveStreamBus.latestAnswer.collectAsState()
    val pipelineState by LiveStreamBus.pipelineState.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("实时监听", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { LiveStreamBus.clear() }) { Text("清空当前显示") }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                liveStateText(pipelineState),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { ListenService.stop(context) }) { Text("停止") }
        }

        latestAnswer?.let { answer ->
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("最新答案", style = MaterialTheme.typography.titleMedium)
                    Text("问题：${answer.question}", style = MaterialTheme.typography.bodyMedium)
                    Text(liveAnswerLabel(answer), style = MaterialTheme.typography.bodyLarge)
                    if (answer.result is AnswerResult.Failed || answer.result is AnswerResult.Insufficient) {
                        TextButton(onClick = { ListenService.retryAnswer(context, answer.eventId) }) {
                            Text("重试")
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("实时转写", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        if (transcript.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "点击首页「开始监听」后，final 转写会出现在这里。",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(
                    items = transcript.asReversed(),
                    key = { line -> "${line.utteranceId}-${line.text}-${line.hashCode()}" },
                ) { line ->
                    Text(
                        text = when (line) {
                            is LiveTranscriptLine.Partial -> "${line.text}（正在识别）"
                            is LiveTranscriptLine.Final -> line.text
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (line is LiveTranscriptLine.Partial) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** Compatibility predicate for the retained transcript-marker data tests; no marker UI uses it. */
internal fun canMarkLatest(activeCourseId: Long?, latestChunkId: Long?): Boolean =
    activeCourseId != null && latestChunkId != null