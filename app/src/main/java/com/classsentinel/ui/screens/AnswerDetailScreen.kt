package com.classsentinel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.classsentinel.data.AnswerCard
import com.classsentinel.data.AnswerHistoryRepository
import com.classsentinel.data.AppDatabase
import com.classsentinel.ui.components.AuroraCard
import com.classsentinel.ui.components.ScreenHeader
import com.classsentinel.ui.components.StatusPill
import com.classsentinel.ui.theme.ClassSentinelSpacing

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
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ClassSentinelSpacing.lg, vertical = ClassSentinelSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(ClassSentinelSpacing.md),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.Top) {
            IconButton(onClick = onIgnore) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回历史")
            }
            ScreenHeader(
                eyebrow = "ANSWER DETAIL",
                title = "问答详情",
                description = "查看完整问题、回答和课堂依据。",
                modifier = Modifier.weight(1f).padding(top = ClassSentinelSpacing.xs),
            )
        }
        val loadedCard = card
        if (loading) {
            AuroraCard {
                Text(
                    "正在读取…",
                    modifier = Modifier.padding(ClassSentinelSpacing.xl),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Column
        }
        if (loadedCard == null) {
            AuroraCard(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                Column(
                    modifier = Modifier.padding(ClassSentinelSpacing.xl),
                    verticalArrangement = Arrangement.spacedBy(ClassSentinelSpacing.sm),
                ) {
                    Text("问答不存在", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "这条问答可能已被清除，或通知中的链接已失效。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Column
        }

        val presentation = answerCardPresentation(loadedCard, expanded = true)
        AuroraCard {
            Column(
                modifier = Modifier.padding(ClassSentinelSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(ClassSentinelSpacing.md),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("回答状态", style = MaterialTheme.typography.titleMedium)
                    StatusPill(
                        label = if (loadedCard.answer.isNullOrBlank()) "待重试" else "已完成",
                        active = !loadedCard.answer.isNullOrBlank(),
                    )
                }
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
                    TextButton(onClick = onIgnore) { Text("返回历史") }
                }
            }
        }
    }
}
