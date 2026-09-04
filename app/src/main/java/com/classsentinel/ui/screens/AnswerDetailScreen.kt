package com.classsentinel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.classsentinel.data.AnswerCard
import com.classsentinel.data.AnswerHistoryRepository
import com.classsentinel.data.AppDatabase

/** Detail view addressed only by the persisted question event ID. */
@Composable
fun AnswerDetailScreen(
    eventId: Long?,
    onRetry: (Long) -> Unit = {},
    onIgnore: () -> Unit = {},
) {
    val context = LocalContext.current
    val database = remember { AppDatabase.get(context) }
    val repository = remember(database) { AnswerHistoryRepository(database.eventDao()) }
    var card by remember(eventId) { mutableStateOf<AnswerCard?>(null) }
    var loading by remember(eventId) { mutableStateOf(true) }
    LaunchedEffect(eventId, repository) {
        loading = true
        card = null
        if (eventId != null && eventId > 0L) {
            card = repository.getCardById(eventId)
        }
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("问答详情", style = MaterialTheme.typography.headlineSmall)
        val loadedCard = card
        if (loading) {
            Text("正在读取…", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        if (loadedCard == null) {
            Text("问答不存在", style = MaterialTheme.typography.titleMedium)
            Text(
                "这条问答可能已被清除，或通知中的链接已失效。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        val presentation = answerCardPresentation(loadedCard, expanded = true)
        Text("问题", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(presentation.question, style = MaterialTheme.typography.bodyLarge)
        Text("答案", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(presentation.answer, style = MaterialTheme.typography.bodyLarge)
        Text("课堂依据", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            presentation.context.ifBlank { "暂无可用依据" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("时间：${presentation.time}", style = MaterialTheme.typography.labelMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (loadedCard.answer.isNullOrBlank()) {
                Button(onClick = { onRetry(loadedCard.eventId) }) { Text("重试") }
            }
            TextButton(onClick = onIgnore) { Text("忽略") }
        }
    }
}
