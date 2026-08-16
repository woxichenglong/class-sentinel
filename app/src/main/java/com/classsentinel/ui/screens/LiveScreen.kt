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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.classsentinel.core.detect.EventType
import com.classsentinel.core.pipeline.PipelineState
import com.classsentinel.service.LiveStreamBus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 实时监听屏：最近事件高亮卡 + 实时转写流。
 * 数据源：LiveStreamBus（ListenService 接入前，由自检页模拟事件驱动）。
 */
@Composable
fun LiveScreen() {
    val segments by LiveStreamBus.segmentList.collectAsState()
    val events by LiveStreamBus.events.collectAsState()
    val pipelineState by LiveStreamBus.pipelineState.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("实时监听", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { LiveStreamBus.clear() }) { Text("清空") }
        }

        val stateText = when (val s = pipelineState) {
            is PipelineState.Idle -> "未在监听"
            is PipelineState.Listening -> "监听中 · 已转写 ${s.sentences} 句"
            is PipelineState.Error -> "出错：${s.message}"
        }
        Text(
            text = stateText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        // 最近事件高亮卡
        val lastEvent = events.lastOrNull()
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("最近事件", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = lastEvent?.let { e ->
                        val type = if (e.type == EventType.ROLLCALL) "点名" else "提问"
                        "$type · ${e.triggerText}\n${timeOf(e.ts)}"
                    } ?: "暂无事件（可到自检页模拟）",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Text("实时转写", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))

        if (segments.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "暂无实时转写数据。\n点击首页「开始听讲」启动监听，或在自检页「模拟提问/点名」生成事件。",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(segments.asReversed()) { segment ->
                    Text(
                        text = "· $segment",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

private fun timeOf(ts: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))