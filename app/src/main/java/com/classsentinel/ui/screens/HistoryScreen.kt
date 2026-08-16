package com.classsentinel.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.classsentinel.data.AppDatabase
import com.classsentinel.data.CourseSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史记录：课程列表（按 startTs 倒序），每项显示 日期时间、时长、事件数。
 * 从 CourseDao 的 Flow 收集，无数据时显示空态。
 */
@Composable
fun HistoryScreen(onCourseClick: (Long) -> Unit = {}) {
    val context = LocalContext.current
    val database = remember { AppDatabase.get(context) }
    val summaries by remember {
        database.courseDao().observeSummaries()
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    if (summaries.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("暂无课程记录", style = MaterialTheme.typography.titleMedium)
            Text(
                "开始一次听讲后，课程与课堂事件会出现在这里",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(summaries, key = { it.course.id }) { summary ->
            CourseHistoryCard(
                summary = summary,
                timeFormat = timeFormat,
                onClick = { onCourseClick(summary.course.id) },
            )
        }
    }
}

@Composable
private fun CourseHistoryCard(
    summary: CourseSummary,
    timeFormat: SimpleDateFormat,
    onClick: () -> Unit,
) {
    val course = summary.course
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = course.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = timeFormat.format(Date(course.startTs)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatDuration(course.startTs, course.endTs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "事件 ${summary.eventCount}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 时长文案：未结束显示「进行中」，否则按 小时/分钟 展示 */
private fun formatDuration(startTs: Long, endTs: Long?): String {
    if (endTs == null) return "进行中"
    val minutes = (endTs - startTs) / 60_000L
    return if (minutes < 60) "${minutes}分钟" else "${minutes / 60}小时${minutes % 60}分"
}