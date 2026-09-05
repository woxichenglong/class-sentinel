package com.classsentinel.service

import androidx.room.Room
import androidx.work.testing.WorkManagerTestInitHelper

import com.classsentinel.data.AppDatabase
import com.classsentinel.data.InMemorySecretStore
import com.classsentinel.data.SettingsRepository
import com.classsentinel.data.SettingsRepositoryHolder
import com.classsentinel.data.AiSettings
import com.classsentinel.data.entities.CourseEntity
import com.classsentinel.data.entities.TranscriptChunkEntity
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
 * 生产 handle factory 的 STOP wiring：验证 finalize hook 发生在 durable finalize 之后，
 * 且重复 STOP 不重复调度课后任务；不直接测试 SummaryWorker 的资格判断。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListenServiceHandleFactoryTest {
    @Test
    fun `controller handle invokes course finalization hook once after stop`() = runBlocking {
        val events = mutableListOf<String>()
        val hookCourses = mutableListOf<Long>()
        val handle = createControllerHandle(
            store = object : CourseSessionStore {
                override suspend fun createCourse(): Long {
                    events += "create"
                    return 42L
                }

                override suspend fun finalizeCourse(courseId: Long, endTs: Long) {
                    events += "finalize"
                }
            },
            pipeline = object : SessionPipeline {
                override suspend fun start() {
                    events += "start"
                }

                override suspend fun stop() {
                    events += "pipeline.stop"
                }
            },
            onCourseFinalized = { courseId ->
                events += "summary"
                hookCourses += courseId
            },
        )

        assertTrue(handle.start())
        assertTrue(handle.stop())
        assertFalse(handle.stop())

        assertEquals(listOf("create", "start", "pipeline.stop", "finalize", "summary"), events)
        assertEquals(listOf(42L), hookCourses)
    }

    @Test
    fun `production default hook uses SummaryWorker eligibility after controller stop`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val settings = SettingsRepository.createForTests(context, InMemorySecretStore())
        try {
            db.courseDao().insert(
                CourseEntity(
                    id = 42L,
                    title = "课程",
                    startTs = 1L,
                    status = "COMPLETED",
                ),
            )
            db.transcriptDao().insert(
                TranscriptChunkEntity(
                    courseId = 42L,
                    seq = 1,
                    text = "有正文",
                    ts = 2L,
                    startOffsetMs = 1L,
                    endOffsetMs = 2L,
                ),
            )
            settings.saveAutoSummary(true)
            settings.saveAiSettings(AiSettings("https://llm.invalid/v1", "test-key", "test-model"))
            SettingsRepositoryHolder.installForTests(settings)

            val handle = createControllerHandle(
                store = object : CourseSessionStore {
                    override suspend fun createCourse(): Long = 42L
                    override suspend fun finalizeCourse(courseId: Long, endTs: Long) = Unit
                },
                pipeline = object : SessionPipeline {
                    override suspend fun start() = Unit
                    override suspend fun stop() = Unit
                },
                context = context,
                db = db,
            )

            assertTrue(handle.start())
            assertTrue(handle.stop())

            assertEquals("QUEUED", db.courseDao().getById(42L)?.summaryStatus)
        } finally {
            SettingsRepositoryHolder.installForTests(null)
            db.close()
        }
    }
}
