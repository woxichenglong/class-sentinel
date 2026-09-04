package com.classsentinel.worker

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryManualGenerationTest {

    @Test
    fun `manual request queues before enqueue and does not require auto summary`() = runBlocking {
        val dependencies = FakeDependencies(marked = true)

        val result = SummaryWorker.enqueueManual(7L, dependencies)

        assertTrue(result)
        assertEquals(listOf("queued", "enqueue"), dependencies.calls)
    }

    @Test
    fun `manual request does not enqueue when course cannot be marked queued`() = runBlocking {
        val dependencies = FakeDependencies(marked = false)

        val result = SummaryWorker.enqueueManual(7L, dependencies)

        assertFalse(result)
        assertEquals(listOf("queued"), dependencies.calls)
    }

    private class FakeDependencies(
        private val marked: Boolean,
    ) : SummaryManualScheduleDependencies {
        val calls = mutableListOf<String>()

        override suspend fun markQueued(courseId: Long): Boolean {
            calls += "queued"
            return marked
        }

        override fun enqueue(courseId: Long) {
            calls += "enqueue"
        }
    }
}
