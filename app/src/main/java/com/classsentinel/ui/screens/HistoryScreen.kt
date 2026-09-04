package com.classsentinel.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.classsentinel.data.AnswerCard
import com.classsentinel.data.AnswerHistoryRepository
import com.classsentinel.data.AppDatabase

/** Student-facing history: answer cards grouped by local calendar date. */
@Composable
fun HistoryScreen(
    onAnswerClick: (Long) -> Unit = {},
    onRetry: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val database = remember { AppDatabase.get(context) }
    val repository = remember(database) { AnswerHistoryRepository(database.eventDao()) }
    val cards by remember(repository) {
        repository.observeCards()
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val groups = remember(cards) { repository.groupByDate(cards) }

    if (groups.isEmpty()) {
        EmptyAnswerHistory()
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text("问答历史", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "按日期回看课堂中真正触发的问答，依据可展开查看。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        groups.forEach { group ->
            item(key = "date-${group.date}") {
                Text(
                    text = group.date,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(group.cards, key = { it.eventId }) { card ->
                AnswerHistoryCard(
                    card = card,
                    onClick = { onAnswerClick(card.eventId) },
                    onRetry = { onRetry(card.eventId) },
                )
            }
        }
    }
}

@Composable
private fun EmptyAnswerHistory() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("暂无问答历史", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "开始一次课堂监听并识别到可回答的问题后，问答会按日期显示在这里。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AnswerHistoryCard(
    card: AnswerCard,
    onClick: () -> Unit,
    onRetry: () -> Unit,
) {
    var expanded by rememberSaveable(card.eventId) { mutableStateOf(false) }
    val presentation = answerCardPresentation(card, expanded = expanded)

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("问题", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(
                    presentation.time,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(presentation.question, style = MaterialTheme.typography.bodyLarge)
            Text("答案", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(presentation.answer, style = MaterialTheme.typography.bodyLarge)
            Text(
                "依据：${presentation.context.ifBlank { "无" }}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起依据" else "展开依据")
                }
                if (card.answer.isNullOrBlank()) {
                    TextButton(onClick = onRetry) { Text("重试") }
                }
            }
        }
    }
}