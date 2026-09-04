package com.classsentinel.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.testing.TestListenableWorkerBuilder
import com.classsentinel.data.HistoryCleanup
import com.classsentinel.data.HistoryCleanupResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Task 17：保留清理 Worker 的运行时接线与调度契约。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionCleanupWorkerTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun `worker passes retention setting and clock to cleanup repository`() = runBlocking {
        val cleanup = RecordingCleanup()
        val worker = workerWith(
            cleanup = cleanup,
            retentionDays = "7",
            nowMillis = 123_456L,
        )

        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(listOf("7" to 123_456L), cleanup.calls)
    }

    @Test
    fun `worker retries when cleanup repository fails`() = runBlocking {
        val cleanup = object : HistoryCleanup {
            override suspend fun deleteExpired(retentionDays: String, nowMillis: Long): HistoryCleanupResult {
                throw IllegalStateException("synthetic cleanup failure")
            }
        }
        val worker = workerWith(cleanup, retentionDays = "30", nowMillis = 123_456L)

        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
    }

    @Test
    fun `cleanup request repeats daily without requiring network`() {
        val request = RetentionCleanupWorker.buildRequest()

        assertEquals(24L * 60L * 60L * 1_000L, request.workSpec.intervalDuration)
        assertEquals(NetworkType.NOT_REQUIRED, request.workSpec.constraints.requiredNetworkType)
    }

    private fun workerWith(
        cleanup: HistoryCleanup,
        retentionDays: String,
        nowMillis: Long,
    ): RetentionCleanupWorker = TestListenableWorkerBuilder.from(
        context,
        RetentionCleanupWorker::class.java,
    )
        .build()
        .apply {
            dependencies = object : RetentionCleanupDependencies {
                override val cleanup: HistoryCleanup = cleanup
                override val retentionDays: suspend () -> String = { retentionDays }
                override val clock: () -> Long = { nowMillis }
            }
        }

    private class RecordingCleanup : HistoryCleanup {
        val calls = mutableListOf<Pair<String, Long>>()

        override suspend fun deleteExpired(retentionDays: String, nowMillis: Long): HistoryCleanupResult {
            calls += retentionDays to nowMillis
            return HistoryCleanupResult()
        }
    }
}
