package com.classsentinel.ui

import com.classsentinel.worker.SummaryStatus
import com.classsentinel.worker.SummaryWorker
import java.util.Locale

/**
 * Summary presentation state shared by the course detail and history surfaces.
 *
 * The UI never renders the raw worker error. [SummaryFailure] is deliberately a closed set so
 * provider response bodies, exception messages, and credentials cannot leak into the screen.
 */
sealed interface SummaryUiState {
    val actionLabel: String?
    val actionEnabled: Boolean
    val progressLabel: String?

    data object NotGenerated : SummaryUiState {
        override val actionLabel: String = "生成总结"
        override val actionEnabled: Boolean = true
        override val progressLabel: String? = null
    }

    data object Queued : SummaryUiState {
        override val actionLabel: String = "生成中…"
        override val actionEnabled: Boolean = false
        override val progressLabel: String = "等待生成…"
    }

    data object Running : SummaryUiState {
        override val actionLabel: String = "生成中…"
        override val actionEnabled: Boolean = false
        override val progressLabel: String = "正在生成…"
    }

    data class Failed(val reason: SummaryFailure) : SummaryUiState {
        override val actionLabel: String = "重试"
        override val actionEnabled: Boolean = true
        override val progressLabel: String? = null
    }

    data class Succeeded(
        val markdown: String,
        val sections: List<SummarySection>,
    ) : SummaryUiState {
        override val actionLabel: String? = null
        override val actionEnabled: Boolean = false
        override val progressLabel: String? = null
    }
}

/** Closed set of user-facing summary failures. Never expose the provider's raw error text. */
enum class SummaryFailure(val userMessage: String) {
    CONFIG("AI 配置不完整，请先到设置中完成配置"),
    EMPTY_RESPONSE("模型未返回有效内容，请重试"),
    QUEUE("任务排队失败，请稍后重试"),
    GENERATION("生成失败，请重试"),
    UNKNOWN("总结生成失败，请重试"),
    ;

    companion object {
        fun fromErrorCode(raw: String?): SummaryFailure {
            val code = raw
                ?.substringBefore(':')
                ?.trim()
                ?.uppercase(Locale.ROOT)
            return when (code) {
                SummaryWorker.ERROR_CODE_CONFIG -> CONFIG
                SummaryWorker.ERROR_CODE_EMPTY -> EMPTY_RESPONSE
                SummaryWorker.ERROR_CODE_QUEUE -> QUEUE
                SummaryWorker.ERROR_CODE_GENERATION -> GENERATION
                else -> UNKNOWN
            }
        }
    }
}

data class SummarySection(
    val heading: String,
    val body: String,
)

/**
 * Convert persisted worker fields into a safe, renderable state.
 *
 * A successful state without content is treated as a recoverable failure rather than showing an
 * empty success card. Unknown future states remain safe and retryable.
 */
fun summaryUiState(
    status: String,
    markdown: String?,
    errorCode: String?,
): SummaryUiState {
    val cleanMarkdown = markdown?.trim()?.takeIf { it.isNotEmpty() }
    return when (status.trim().uppercase(Locale.ROOT)) {
        SummaryStatus.QUEUED -> SummaryUiState.Queued
        SummaryStatus.RUNNING -> SummaryUiState.Running
        SummaryStatus.SUCCEEDED -> cleanMarkdown?.let(::successState)
            ?: SummaryUiState.Failed(SummaryFailure.EMPTY_RESPONSE)
        SummaryStatus.FAILED -> SummaryUiState.Failed(SummaryFailure.fromErrorCode(errorCode))
        SummaryStatus.NONE -> cleanMarkdown?.let(::successState) ?: SummaryUiState.NotGenerated
        else -> cleanMarkdown?.let(::successState)
            ?: SummaryUiState.Failed(SummaryFailure.fromErrorCode(errorCode))
    }
}

/** Stable status text used by the history badge and detail header. */
fun summaryStatusLabel(status: String): String = when (status.trim().uppercase(Locale.ROOT)) {
    SummaryStatus.QUEUED -> "排队中"
    SummaryStatus.RUNNING -> "生成中"
    SummaryStatus.SUCCEEDED -> "已生成"
    SummaryStatus.FAILED -> "生成失败"
    else -> "未生成总结"
}

private fun successState(markdown: String): SummaryUiState.Succeeded =
    SummaryUiState.Succeeded(markdown = markdown, sections = parseSummarySections(markdown))

/**
 * Split level-two Markdown headings into cards without adding a Markdown dependency.
 * The known four-section template is therefore structured in the UI; arbitrary level-two
 * headings are also kept as separate cards, while level-three headings remain body text.
 */
fun parseSummarySections(markdown: String): List<SummarySection> {
    val clean = markdown.trim()
    if (clean.isEmpty()) return emptyList()

    val headingPattern = Regex("^##\\s+(.+?)\\s*$")
    val sections = mutableListOf<SummarySection>()
    var currentHeading: String? = null
    val currentBody = StringBuilder()

    fun flush() {
        val heading = currentHeading
        if (heading != null) {
            sections += SummarySection(heading, currentBody.toString().trim())
        } else if (currentBody.toString().isNotBlank()) {
            sections += SummarySection("总结", currentBody.toString().trim())
        }
        currentBody.clear()
    }

    clean.lineSequence().forEach { line ->
        val match = headingPattern.matchEntire(line)
        if (match != null) {
            flush()
            currentHeading = match.groupValues[1].trim()
        } else {
            if (currentBody.isNotEmpty()) currentBody.append('\n')
            currentBody.append(line)
        }
    }
    flush()

    return sections.ifEmpty { listOf(SummarySection("总结", clean)) }
}
