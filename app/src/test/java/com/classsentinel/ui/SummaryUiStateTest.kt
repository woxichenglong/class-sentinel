package com.classsentinel.ui

import com.classsentinel.worker.SummaryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryUiStateTest {

    @Test
    fun `none exposes manual generate action`() {
        val state = summaryUiState(SummaryStatus.NONE, null, null)

        assertTrue(state is SummaryUiState.NotGenerated)
        assertEquals("生成总结", state.actionLabel)
        assertTrue(state.actionEnabled)
        assertEquals("未生成总结", summaryStatusLabel(SummaryStatus.NONE))
    }

    @Test
    fun `queued and running expose progress and disable duplicate action`() {
        val queued = summaryUiState(SummaryStatus.QUEUED, null, null)
        val running = summaryUiState(SummaryStatus.RUNNING, null, null)

        assertTrue(queued is SummaryUiState.Queued)
        assertEquals("等待生成…", queued.progressLabel)
        assertFalse(queued.actionEnabled)
        assertEquals("生成中…", queued.actionLabel)

        assertTrue(running is SummaryUiState.Running)
        assertEquals("正在生成…", running.progressLabel)
        assertFalse(running.actionEnabled)
        assertEquals("生成中…", running.actionLabel)
        assertEquals("生成中", summaryStatusLabel(SummaryStatus.RUNNING))
    }

    @Test
    fun `failed state maps provider error to safe retry reason`() {
        val rawProviderBody = "provider body: secret response"
        val state = summaryUiState(
            SummaryStatus.FAILED,
            markdown = null,
            errorCode = "GENERATION_FAILED:$rawProviderBody",
        )
        val failed = state as SummaryUiState.Failed

        assertEquals(SummaryFailure.GENERATION, failed.reason)
        assertEquals("重试", state.actionLabel)
        assertTrue(state.actionEnabled)
        assertFalse(failed.reason.userMessage.contains(rawProviderBody))
        assertEquals("生成失败", summaryStatusLabel(SummaryStatus.FAILED))
    }

    @Test
    fun `succeeded state splits known markdown headings and remains copyable`() {
        val markdown = "## 知识点\n傅里叶变换\n## 作业\n完成习题一"

        val state = summaryUiState(SummaryStatus.SUCCEEDED, markdown, null)
        val succeeded = state as SummaryUiState.Succeeded

        assertEquals(markdown, succeeded.markdown)
        assertEquals(
            listOf(
                SummarySection("知识点", "傅里叶变换"),
                SummarySection("作业", "完成习题一"),
            ),
            succeeded.sections,
        )
        assertEquals("已生成", summaryStatusLabel(SummaryStatus.SUCCEEDED))
    }

    @Test
    fun `unknown status and error remain safe and recoverable`() {
        val state = summaryUiState("FUTURE_STATUS", null, "provider exception with credentials")
        val failed = state as SummaryUiState.Failed

        assertEquals(SummaryFailure.UNKNOWN, failed.reason)
        assertEquals("重试", state.actionLabel)
        assertFalse(failed.reason.userMessage.contains("provider exception"))
        assertEquals("未生成总结", summaryStatusLabel("FUTURE_STATUS"))
    }
}
