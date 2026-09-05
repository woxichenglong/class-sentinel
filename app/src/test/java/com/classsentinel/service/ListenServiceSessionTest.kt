package com.classsentinel.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ListenServiceSessionTest {
    @Test
    fun `concurrent starts create one session handle and start it once`() = runTest {
        var createCount = 0
        val startEntered = CompletableDeferred<Unit>()
        val releaseStart = CompletableDeferred<Unit>()
        val handle = object : ListenSessionHandle {
            var startCount = 0
            override suspend fun start(): Boolean {
                startCount++
                startEntered.complete(Unit)
                releaseStart.await()
                return true
            }
            override suspend fun stop(): Boolean = true
        }
        val session = ListenServiceSession(
            scope = CoroutineScope(coroutineContext),
            createHandle = {
                createCount++
                handle
            },
            stopSelfResult = { true },
        )

        val starts = List(5) { session.start() }
        runCurrent()
        startEntered.await()

        assertEquals(1, createCount)
        assertEquals(1, handle.startCount)

        releaseStart.complete(Unit)
        starts.forEach { it.join() }
    }

    @Test
    fun `stop waits for cancelled but incomplete start before stopSelfResult`() = runTest {
        val createEntered = CompletableDeferred<Unit>()
        val releaseCreate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val handle = object : ListenSessionHandle {
            override suspend fun start(): Boolean = true
            override suspend fun stop(): Boolean {
                events += "handle.stop"
                return true
            }
        }
        val session = ListenServiceSession(
            scope = CoroutineScope(coroutineContext),
            createHandle = {
                createEntered.complete(Unit)
                try {
                    withContext(NonCancellable) { releaseCreate.await() }
                    handle
                } finally {
                    events += "startFinished"
                }
            },
            stopSelfResult = {
                events += "stopSelfResult"
                true
            },
        )

        val startJob = session.start()
        runCurrent()
        createEntered.await()
        startJob.cancel()

        val stopJob = session.stop(7)
        assertEquals(false, stopJob.isCompleted)

        releaseCreate.complete(Unit)
        stopJob.join()
        startJob.join()

        assertEquals(listOf("startFinished", "handle.stop", "stopSelfResult"), events)
    }

    @Test
    fun `stop calls handle stop before stopSelfResult`() = runTest {
        val events = mutableListOf<String>()
        val handle = object : ListenSessionHandle {
            override suspend fun start(): Boolean {
                events += "handle.start"
                return true
            }
            override suspend fun stop(): Boolean {
                events += "handle.stop"
                return true
            }
        }
        val session = ListenServiceSession(
            scope = CoroutineScope(coroutineContext),
            createHandle = { handle },
            stopSelfResult = {
                events += "stopSelfResult"
                true
            },
        )

        session.start().join()
        session.stop(11).join()

        assertEquals(listOf("handle.start", "handle.stop", "stopSelfResult"), events)
    }

    @Test
    fun `handle creation failure is reported without escaping from the service scope`() = runTest {
        var failure: Throwable? = null
        val session = ListenServiceSession(
            scope = CoroutineScope(coroutineContext),
            createHandle = { throw IllegalStateException("sensitive provider detail") },
            stopSelfResult = { true },
            onStartFailure = { failure = it },
        )

        val job = session.start()
        job.join()

        assertEquals(IllegalStateException::class.java, failure?.javaClass)
        assertEquals(true, job.isCompleted)
    }

    @Test
    fun `rejected handle start is reported as a service start failure`() = runTest {
        var failure: Throwable? = null
        val handle = object : ListenSessionHandle {
            override suspend fun start(): Boolean = false
            override suspend fun stop(): Boolean = true
        }
        val session = ListenServiceSession(
            scope = CoroutineScope(coroutineContext),
            createHandle = { handle },
            stopSelfResult = { true },
            onStartFailure = { failure = it },
        )

        val job = session.start()
        job.join()

        assertEquals("SESSION_START_REJECTED", failure?.message)
    }

    @Test
    fun `stop after non cooperative start still stops the handle that reached running`() = runTest {
        val startEntered = CompletableDeferred<Unit>()
        val releaseStart = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val handle = object : ListenSessionHandle {
            override suspend fun start(): Boolean {
                events += "handle.start"
                startEntered.complete(Unit)
                withContext(NonCancellable) { releaseStart.await() }
                events += "handle.running"
                return true
            }

            override suspend fun stop(): Boolean {
                events += "handle.stop"
                return true
            }
        }
        val session = ListenServiceSession(
            scope = CoroutineScope(coroutineContext),
            createHandle = { handle },
            stopSelfResult = { events += "stopSelfResult"; true },
        )

        val startJob = session.start()
        runCurrent()
        startEntered.await()
        val stopJob = session.stop(7)
        assertEquals(false, stopJob.isCompleted)

        releaseStart.complete(Unit)
        stopJob.join()
        startJob.join()

        assertEquals(
            listOf("handle.start", "handle.running", "handle.stop", "stopSelfResult"),
            events,
        )
    }

    @Test
    fun `successful stop discards handle so next start recreates it from current profile`() = runTest {
        var selectedProfile = "profile-a"
        val createdProfiles = mutableListOf<String>()
        val handles = mutableListOf<ListenSessionHandle>()
        fun newHandle(profile: String): ListenSessionHandle = object : ListenSessionHandle {
            override suspend fun start(): Boolean = true
            override suspend fun stop(): Boolean = true
        }.also { handles += it }

        val session = ListenServiceSession(
            scope = CoroutineScope(coroutineContext),
            createHandle = {
                createdProfiles += selectedProfile
                newHandle(selectedProfile)
            },
            stopSelfResult = { true },
        )

        session.start().join()
        session.stop(1).join()
        selectedProfile = "profile-b"
        session.start().join()

        assertEquals(listOf("profile-a", "profile-b"), createdProfiles)
        assertEquals(2, handles.size)
    }

    @Test
    fun `concurrent and repeated stop invoke the handle stop only once`() = runTest {
        val stopIds = mutableListOf<Int>()
        val handle = object : ListenSessionHandle {
            var stopCount = 0
            override suspend fun start(): Boolean = true
            override suspend fun stop(): Boolean {
                stopCount++
                return true
            }
        }
        val session = ListenServiceSession(
            scope = CoroutineScope(coroutineContext),
            createHandle = { handle },
            stopSelfResult = { stopIds += it; true },
        )

        session.start().join()
        val firstStop = session.stop(1)
        val concurrentStop = session.stop(2)
        firstStop.join()
        concurrentStop.join()
        session.stop(3).join()

        assertEquals(1, handle.stopCount)
        assertEquals(listOf(1, 2, 3), stopIds)
    }

    @Test
    fun `stop preserves cancellation exception from handle stop`() = runTest {
        var completion: Throwable? = null
        val handle = object : ListenSessionHandle {
            override suspend fun start(): Boolean = true
            override suspend fun stop(): Boolean {
                throw CancellationException("stop-cancelled")
            }
        }
        val session = ListenServiceSession(
            scope = CoroutineScope(coroutineContext),
            createHandle = { handle },
            stopSelfResult = { true },
        )

        session.start().join()
        val stopJob = session.stop(9)
        stopJob.invokeOnCompletion { completion = it }
        stopJob.join()

        assertEquals(true, stopJob.isCancelled)
        assertEquals("stop-cancelled", completion?.message)
    }
}
