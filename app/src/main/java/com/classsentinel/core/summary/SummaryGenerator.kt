package com.classsentinel.core.summary

import com.classsentinel.core.llm.LlmClient
import com.classsentinel.core.llm.LlmConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList

/** 生成器的可替换流式客户端；生产默认走 [LlmClient.streamChat]。 */
typealias SummaryStreamChat = (List<Map<String, String>>, LlmConfig) -> Flow<String>

/** 总结生成的可持久化结果分类；失败只携带安全错误码，不携带 provider 原文。 */
sealed interface SummaryGenerationResult {
    data object NoContent : SummaryGenerationResult
    data class Success(val markdown: String) : SummaryGenerationResult
    data class Failed(val code: String) : SummaryGenerationResult
}

/**
 * 课后总结生成器：课堂转写全文 → 选定模板的 Markdown 总结。
 * 超长全文（>4000 字）两级压缩：逐块要点摘要 → 按选定模板汇总。
 */
class SummaryGenerator(
    private val client: LlmClient = LlmClient(),
    private val streamChat: SummaryStreamChat? = null,
) {

    companion object {
        const val CHUNK_SIZE = 4000
        /** 保留 Task 18 的公开常量形态；实际请求由 [SummaryTemplates] 生成。 */
        const val FOUR_SECTION_SYS = "你是课堂笔记助手。根据课堂转写内容，输出四段式总结（结构清晰的中文 Markdown）。\n" +
            "格式严格如下：\n## 知识点\n## 作业\n## 考试重点\n## 下节预告\n" +
            "未提及的段落写「（本节未提及）」；只根据转写内容总结，不要编造。"
    }

    fun generate(
        transcript: String,
        cfg: LlmConfig,
        template: SummaryTemplate = SummaryTemplates.DEFAULT,
    ): Flow<String> = flow {
        if (transcript.isBlank()) return@flow

        if (transcript.length <= CHUNK_SIZE) {
            emitAll(chat(template.messages(transcript), cfg))
        } else {
            // 两级压缩
            val partials = mutableListOf<String>()
            for (chunk in transcript.chunked(CHUNK_SIZE)) {
                currentCoroutineContext().ensureActive()
                val partial = StringBuilder()
                chat(template.partialMessages(chunk), cfg).collect {
                    currentCoroutineContext().ensureActive()
                    partial.append(it)
                }
                if (partial.isBlank()) throw EmptySummaryResponseException
                partials.add(partial.toString())
            }
            currentCoroutineContext().ensureActive()
            emitAll(
                chat(
                    template.messages("各段摘要：\n\n" + partials.joinToString("\n---\n")),
                    cfg,
                ),
            )
        }
    }

    /**
     * 收集完整总结并将 provider 异常转成安全结果。
     * 空转写不创建请求；取消原样传播，避免把取消伪装成失败总结。
     */
    suspend fun generateResult(
        transcript: String,
        cfg: LlmConfig,
        template: SummaryTemplate = SummaryTemplates.DEFAULT,
    ): SummaryGenerationResult {
        if (transcript.isBlank()) return SummaryGenerationResult.NoContent
        return try {
            val markdown = generate(transcript, cfg, template).toList().joinToString("").trim()
            if (markdown.isBlank()) {
                SummaryGenerationResult.Failed("EMPTY_RESPONSE")
            } else {
                SummaryGenerationResult.Success(markdown)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: EmptySummaryResponseException) {
            SummaryGenerationResult.Failed("EMPTY_RESPONSE")
        } catch (_: Exception) {
            SummaryGenerationResult.Failed("GENERATION_FAILED")
        }
    }

    private fun chat(messages: List<Map<String, String>>, cfg: LlmConfig): Flow<String> =
        streamChat?.invoke(messages, cfg) ?: client.streamChat(messages, cfg)

    private object EmptySummaryResponseException : Exception()
}
