package com.classsentinel.core.summary

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.classsentinel.core.llm.LlmConfig
import com.classsentinel.data.InMemorySecretStore
import com.classsentinel.data.SettingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SummaryTemplateTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var dataStoreFile: File
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var secretStore: InMemorySecretStore

    @Before
    fun setUp() {
        dataStoreFile = File.createTempFile("summary-template-test", ".preferences_pb")
        dataStoreFile.deleteOnExit()
        dataStoreScope = CoroutineScope(Dispatchers.IO + Job())
        secretStore = InMemorySecretStore()
        dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = dataStoreScope,
            produceFile = { dataStoreFile },
        )
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        dataStoreFile.delete()
    }

    @Test
    fun `built in templates expose the four planned section sets`() {
        assertEquals(
            listOf("知识点", "作业", "考试重点", "下节预告"),
            SummaryTemplates.DEFAULT.sections,
        )
        assertEquals(
            listOf("概念", "公式", "易错点", "可能考题"),
            SummaryTemplates.EXAM_REVIEW.sections,
        )
        assertEquals(
            listOf("立场", "证据", "分歧", "后续跟进"),
            SummaryTemplates.SEMINAR.sections,
        )
        assertEquals(
            listOf("目标", "材料", "步骤", "结果"),
            SummaryTemplates.LAB.sections,
        )
    }

    @Test
    fun `transcript is placed in user message rather than system instructions`() {
        val transcript = "课堂原文标记-不要进入系统提示词"
        val messages = SummaryTemplates.EXAM_REVIEW.messages(transcript)

        assertEquals(listOf("system", "user"), messages.map { it["role"] })
        assertFalse(messages.first()["content"].orEmpty().contains(transcript))
        assertTrue(messages.last()["content"].orEmpty().contains(transcript))
    }

    @Test
    fun `custom prompt is rendered as template instruction and transcript stays separate`() {
        val prompt = "请优先列出定义，再列出一个课堂例子。"
        val transcript = "老师讲了傅里叶变换"
        val template = SummaryTemplates.custom(prompt)
        val messages = template.messages(transcript)

        assertEquals(SummaryTemplates.CUSTOM_ID, template.id)
        assertFalse(messages.first()["content"].orEmpty().contains(prompt))
        assertTrue(messages.last()["content"].orEmpty().contains(prompt))
        assertFalse(messages.first()["content"].orEmpty().contains(transcript))
        assertTrue(messages.last()["content"].orEmpty().contains(transcript))
    }

    @Test
    fun `generator forwards the selected template to the llm request`() = runBlocking {
        var systemPrompt = ""
        val generator = SummaryGenerator(
            streamChat = { messages, _ ->
                systemPrompt = messages.first()["content"].orEmpty()
                flowOf("## 目标\n实验")
            },
        )

        generator.generate(
            transcript = "实验课讲了回归分析",
            cfg = LlmConfig("https://llm.invalid", "test-key", "test-model"),
            template = SummaryTemplates.LAB,
        ).toList()

        assertTrue(systemPrompt.contains("## 目标"))
        assertTrue(systemPrompt.contains("## 材料"))
        assertFalse(systemPrompt.contains("实验课讲了回归分析"))
    }

    @Test
    fun `blank and oversized custom prompts are rejected`() {
        assertInvalidCustomPrompt("	 \n")
        assertInvalidCustomPrompt("x".repeat(SummaryTemplates.MAX_CUSTOM_PROMPT_LENGTH + 1))
    }

    @Test
    fun `repository persists only selected template id and optional custom text`() = runBlocking {
        val repo = SettingsRepository(dataStore, secretStore = secretStore, syncEnabled = false)

        repo.saveSummaryTemplate(SummaryTemplates.CUSTOM_ID, "先列出结论，再列证据")

        assertEquals(SummaryTemplates.CUSTOM_ID, repo.summaryTemplateIdFlow.first())
        assertEquals("先列出结论，再列证据", repo.summaryCustomPromptFlow.first())
        assertEquals(
            SummaryTemplates.CUSTOM_ID,
            repo.summaryTemplateFlow.first().id,
        )

        repo.saveSummaryTemplate(SummaryTemplates.DEFAULT_ID)
        assertEquals(SummaryTemplates.DEFAULT_ID, repo.summaryTemplateIdFlow.first())
        assertEquals("", repo.summaryCustomPromptFlow.first())
    }

    @Test
    fun `repository rejects blank custom selection without writing it`() = runBlocking {
        val repo = SettingsRepository(dataStore, secretStore = secretStore, syncEnabled = false)
        repo.saveSummaryTemplate(SummaryTemplates.DEFAULT_ID)

        assertInvalidCustomPrompt {
            repo.saveSummaryTemplate(SummaryTemplates.CUSTOM_ID, "  \n")
        }

        assertEquals(SummaryTemplates.DEFAULT_ID, repo.summaryTemplateIdFlow.first())
        assertEquals("", repo.summaryCustomPromptFlow.first())
    }

    private fun assertInvalidCustomPrompt(prompt: String) {
        assertInvalidCustomPrompt {
            SummaryTemplates.custom(prompt)
        }
    }

    private fun assertInvalidCustomPrompt(block: suspend () -> Unit) = runBlocking {
        try {
            block()
            throw AssertionError("invalid custom prompt was accepted")
        } catch (_: IllegalArgumentException) {
            // expected validation failure
        }
    }
}
