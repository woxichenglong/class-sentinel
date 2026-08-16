package com.classsentinel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import com.classsentinel.data.AppDatabase
import com.classsentinel.data.entities.EventEntity
import com.classsentinel.data.entities.TranscriptChunkEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 时间线条目：转写块 / 事件混排 */
private data class TimelineItem(
    val isEvent: Boolean,
    val ts: Long,
    val text: String,
    val subText: String?,
    val eventType: String?,
    val seq: Int,
)

/**
 * 课程详情：标题 + 总结（无则「暂无总结」）+ 时间线（转写块与事件卡混排）。
 * 事件卡：点名红 / 提问蓝，显示 triggerText 与 answerText；全文可复制。
 *
 * @param courseId 课程 id；为 null 时尝试从导航参数 "course/{id}" 读取
 */
@Composable
fun CourseDetailScreen(courseId: Long? = null) {
    val resolvedId: Long = courseId ?: rememberNavCourseId() ?: -1L
    val context = LocalContext.current
    val database = remember { AppDatabase.get(context) }
    val course by remember(resolvedId) { database.courseDao().observeById(resolvedId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val events by remember(resolvedId) { database.eventDao().observeForCourse(resolvedId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val chunks by remember(resolvedId) { database.transcriptDao().observeForCourse(resolvedId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val timeline = remember(events, chunks) { mergeTimeline(chunks, events) }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部：标题 + 总结
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = course?.title ?: "课程详情",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = course?.summaryMd ?: "暂无总结",
                style = MaterialTheme.typography.bodyMedium,
                color = if (course?.summaryMd == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        HorizontalDivider()

        // 时间线（全文可复制）
        SelectionContainer(modifier = Modifier.weight(1f)) {
            if (timeline.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("本课暂无转写与事件记录", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(timeline) { _, item ->
                        TimelineRow(item = item, timeFormat = timeFormat)
                    }
                }
            }
        }
    }
}

/** 从 navigation-compose NavBackStackEntry 读取 "course/{id}" 参数（未声明类型时为 String） */
@Composable
private fun rememberNavCourseId(): Long? {
    val entry = LocalLifecycleOwner.current as? NavBackStackEntry
    return entry?.arguments?.getString("id")?.toLongOrNull()
}

@Composable
private fun TimelineRow(item: TimelineItem, timeFormat: SimpleDateFormat) {
    if (item.isEvent) {
        // 事件卡：点名红 / 提问蓝
        val rollcall = item.eventType == "ROLLCALL"
        val container = if (rollcall) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        }
        val content = if (rollcall) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = container),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (rollcall) "点名" else "提问",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = content,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = timeFormat.format(Date(item.ts)),
                        style = MaterialTheme.typography.bodySmall,
                        color = content,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = content,
                )
                item.subText?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "回答：$it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = content,
                    )
                }
            }
        }
    } else {
        // 转写块：时间 + 文本
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(
                text = timeFormat.format(Date(item.ts)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(64.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 转写块与事件按时间升序混排；同时间转写块在前 */
private fun mergeTimeline(
    chunks: List<TranscriptChunkEntity>,
    events: List<EventEntity>,
): List<TimelineItem> {
    val chunkItems = chunks.map {
        TimelineItem(
            isEvent = false,
            ts = it.ts,
            text = it.text,
            subText = null,
            eventType = null,
            seq = it.seq,
        )
    }
    val eventItems = events.map {
        TimelineItem(
            isEvent = true,
            ts = it.ts,
            text = it.triggerText,
            subText = it.answerText,
            eventType = it.type,
            seq = -1,
        )
    }
    return (chunkItems + eventItems).sortedWith(compareBy<TimelineItem> { it.ts }.thenBy { !it.isEvent })
}