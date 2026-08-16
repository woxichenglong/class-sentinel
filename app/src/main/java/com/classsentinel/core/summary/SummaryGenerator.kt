package com.classsentinel.core.summary

import com.classsentinel.core.llm.LlmClient
import com.classsentinel.core.llm.LlmConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * 课后总结生成器：课堂转写全文 → 四段式 Markdown 总结。
 * 超长全文（>4000 字）两级压缩：逐块要点摘要 → 汇总成四段式。
 */
class SummaryGenerator(private val client: LlmClient = LlmClient()) {

    companion object {
        const val CHUNK_SIZE = 4000
        const val FOUR_SECTION_SYS = "你是课堂笔记助手。根据课堂转写内容，输出四段式总结，格式严格如下：\n" +
            "## 知识点\n## 作业\n## 考试重点\n## 下节预告\n" +
            "未提及的段落写「（本节未提及）」。用中文。"
    }

    fun generate(transcript: String, cfg: LlmConfig): Flow<String> = flow {
        if (transcript.length <= CHUNK_SIZE) {
            emitAll(client.streamChat(fourSectionMessages(transcript), cfg))
        } else {
            // 两级压缩
            val partials = mutableListOf<String>()
            transcript.chunked(CHUNK_SIZE).forEach { chunk ->
                val partial = StringBuilder()
                client.streamChat(partialMessages(chunk), cfg).collect { partial.append(it) }
                partials.add(partial.toString())
            }
            emitAll(
                client.streamChat(
                    fourSectionMessages("各段摘要：\n\n" + partials.joinToString("\n---\n")),
                    cfg,
                ),
            )
        }
    }

    private fun fourSectionMessages(content: String) = listOf(
        mapOf("role" to "system", "content" to FOUR_SECTION_SYS),
        mapOf("role" to "user", "content" to "课堂转写：\n$content"),
    )

    private fun partialMessages(chunk: String) = listOf(
        mapOf(
            "role" to "system",
            "content" to "你是课堂笔记助手。把这段课堂转写压缩成要点摘要（300字内），" +
                "保留知识点、作业、考试重点、下节预告相关信息。用中文。",
        ),
        mapOf("role" to "user", "content" to chunk),
    )
}
