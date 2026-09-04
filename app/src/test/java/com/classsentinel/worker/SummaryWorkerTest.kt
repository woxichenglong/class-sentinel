package com.classsentinel.worker

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.testing.TestListenableWorkerBuilder
import com.classsentinel.core.llm.LlmConfig
import com.classsentinel.core.summary.SummaryGenerator
import com.classsentinel.core.summary.SummaryTemplate
import com.classsentinel.core.summary.SummaryTemplates
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 18：SummaryWorker 的持久化契约 RED 测试。
 *
 * 测试用依赖 seam 记录状态写回，不把课堂正文或 provider key 放入 Worker 输入/输出。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SummaryWorkerTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val config = LlmConfig(
        baseUrl = "https://llm.invalid/v1",
        apiKey = "test-key",
        model = "test-model",
    )

    @Test
    fun `empty transcript returns success NO_CONTENT without config or llm call`() = runBlocking {
        var configReads = 0
        var generatorCalls = 0
        val deps = FakeDependencies(
            transcript = "  \n",
            configProvider = {
                configReads++
                config
            },
            generator = SummaryGenerator(
                streamChat = { _, _ ->
                    generatorCalls++
                    flowOf("must not be called")
                },
            ),
        )

        val result = worker(deps).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(listOf(Update(7L, "NONE", null, null)), deps.updates)
        assertEquals(0, configReads)
        assertEquals(0, generatorCalls)
    }

    @Test
    fun `failed partial chunk persists FAILED without invented summary`() = runBlocking {
        val transcript = "傅里叶变换".repeat(900)
        val deps = FakeDependencies(
            transcript = transcript,
            configProvider = { config },
            generator = SummaryGenerator(
                streamChat = { _, _ ->
                    flow { throw IllegalStateException("provider body must not escape") }
                },
            ),
        )

        val result = worker(deps).doWork()

        assertTerminalFailure(result, "GENERATION_FAILED")
        assertEquals(
            listOf(
                Update(7L, "RUNNING", null, null),
                Update(7L, "FAILED", null, "GENERATION_FAILED"),
            ),
            deps.updates,
        )
        assertFalse(deps.updates.any { it.markdown != null })
        assertFalse(result.toString().contains(transcript))
        assertFalse(result.toString().contains("provider body"))
    }

    @Test
    fun `successful generation persists complete markdown only after RUNNING`() = runBlocking {
        val deps = FakeDependencies(
            transcript = "今天讲了傅里叶变换",
            configProvider = { config },
            generator = SummaryGenerator(streamChat = { _, _ -> flowOf("## 知识点\n傅里叶") }),
        )

        val result = worker(deps).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(
            listOf(
                Update(7L, "RUNNING", null, null),
                Update(7L, "SUCCEEDED", "## 知识点\n傅里叶", null),
            ),
            deps.updates,
        )
    }

    @Test
    fun `worker passes the selected template to the generator`() = runBlocking {
        var systemPrompt = ""
        val deps = FakeDependencies(
            transcript = "实验课讲了回归分析",
            configProvider = { config },
            selectedTemplate = SummaryTemplates.LAB,
            generator = SummaryGenerator(
                streamChat = { messages, _ ->
                    systemPrompt = messages.first()["content"].orEmpty()
                    flowOf("## 目标\n实验")
                },
            ),
        )

        val result = worker(deps).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(systemPrompt.contains("## 目标"))
        assertFalse(systemPrompt.contains("实验课讲了回归分析"))
    }

    @Test
    fun `missing config becomes terminal safe FAILED without calling generator`() = runBlocking {
        var generatorCalls = 0
        val deps = FakeDependencies(
            transcript = "有内容",
            configProvider = { null },
            generator = SummaryGenerator(
                streamChat = { _, _ ->
                    generatorCalls++
                    flowOf("must not be called")
                },
            ),
        )

        val result = worker(deps).doWork()

        assertTerminalFailure(result, "CONFIG")
        assertEquals(listOf(Update(7L, "FAILED", null, "CONFIG")), deps.updates)
        assertEquals(0, generatorCalls)
    }

    @Test
    fun `request contains only course id and requires connected network`() {
        val request = SummaryWorker.buildRequest(42L)

        assertEquals(setOf(SummaryWorker.KEY_COURSE_ID), request.workSpec.input.keyValueMap.keys)
        assertEquals(42L, request.workSpec.input.getLong(SummaryWorker.KEY_COURSE_ID, -1L))
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun `disabled auto summary does not read transcript or enqueue`() = runBlocking {
        val deps = FakeScheduleDependencies(
            enabled = false,
            transcript = "有内容",
            config = config,
        )

        assertFalse(SummaryWorker.enqueueIfEligible(7L, deps))
        assertEquals(listOf("enabled"), deps.calls)
    }

    @Test
    fun `empty transcript does not read config or enqueue`() = runBlocking {
        val deps = FakeScheduleDependencies(
            enabled = true,
            transcript = " \n",
            config = config,
        )

        assertFalse(SummaryWorker.enqueueIfEligible(7L, deps))
        assertEquals(listOf("enabled", "transcript"), deps.calls)
    }

    @Test
    fun `missing ai config does not mark queued or enqueue`() = runBlocking {
        val deps = FakeScheduleDependencies(
            enabled = true,
            transcript = "有内容",
            config = null,
        )

        assertFalse(SummaryWorker.enqueueIfEligible(7L, deps))
        assertEquals(listOf("enabled", "transcript", "config"), deps.calls)
    }

    @Test
    fun `eligible course is marked queued before unique work enqueue`() = runBlocking {
        val deps = FakeScheduleDependencies(
            enabled = true,
            transcript = "有内容",
            config = config,
        )

        assertTrue(SummaryWorker.enqueueIfEligible(7L, deps))
        assertEquals(
            listOf("enabled", "transcript", "config", "queued", "enqueue"),
            deps.calls,
        )
    }

    private fun worker(deps: SummaryWorkerDependencies): SummaryWorker =
        TestListenableWorkerBuilder.from(context, SummaryWorker::class.java)
            .setInputData(Data.Builder().putLong(SummaryWorker.KEY_COURSE_ID, 7L).build())
            .build()
            .apply { dependencies = deps }

    private fun assertTerminalFailure(result: ListenableWorker.Result, code: String) {
        assertTrue("expected terminal failure, got $result", result is ListenableWorker.Result.Failure)
        val output = (result as ListenableWorker.Result.Failure).outputData
        assertEquals(code, output.getString(SummaryWorker.KEY_ERROR_CODE))
        assertEquals(setOf(SummaryWorker.KEY_ERROR_CODE), output.keyValueMap.keys)
    }

    private data class Update(
        val courseId: Long,
        val status: String,
        val markdown: String?,
        val error: String?,
    )

    private class FakeDependencies(
        private val transcript: String,
        private val configProvider: suspend () -> LlmConfig?,
        private val selectedTemplate: SummaryTemplate = SummaryTemplates.DEFAULT,
        override val generator: SummaryGenerator,
    ) : SummaryWorkerDependencies {
        val updates = mutableListOf<Update>()

        override suspend fun transcriptForCourse(courseId: Long): String = transcript

        override suspend fun aiConfig(): LlmConfig? = configProvider()

        override suspend fun summaryTemplate(): SummaryTemplate = selectedTemplate

        override suspend fun updateSummary(
            courseId: Long,
            status: String,
            markdown: String?,
            errorCode: String?,
        ) {
            updates += Update(courseId, status, markdown, errorCode)
        }
    }

    private class FakeScheduleDependencies(
        private val enabled: Boolean,
        private val transcript: String,
        private val config: LlmConfig?,
    ) : SummaryScheduleDependencies {
        val calls = mutableListOf<String>()

        override suspend fun autoSummaryEnabled(): Boolean {
            calls += "enabled"
            return enabled
        }

        override suspend fun transcriptForCourse(courseId: Long): String {
            calls += "transcript"
            return transcript
        }

        override suspend fun aiConfig(): LlmConfig? {
            calls += "config"
            return config
        }

        override suspend fun markQueued(courseId: Long): Boolean {
            calls += "queued"
            return true
        }

        override fun enqueue(courseId: Long) {
            calls += "enqueue"
        }
    }
}
