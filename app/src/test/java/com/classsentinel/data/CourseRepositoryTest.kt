package com.classsentinel.data

import androidx.room.Room
import com.classsentinel.data.entities.CourseEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * CourseRepository.finalizeCourse 行为测试（Robolectric + Room in-memory）。
 *
 * RED（TDD）：CourseRepository 尚不存在，本测试当前无法编译，待 Task 15 实现后转绿。
 *
 * 覆盖单一行为：status=RUNNING 且 endTs=null 的课程被 finalizeCourse 收尾后
 * 变为 COMPLETED 且写入 endTs；重复收尾（再次以更大的 endTs 调用）不得覆盖
 * 首次写入的 endTs（幂等）。所有断言通过读回数据库验证，不依赖调用返回值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CourseRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: CourseRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = CourseRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `finalizeCourse marks running course completed with endTs and is idempotent`() = runBlocking {
        // 合成测试数据：status=RUNNING、endTs=null 的进行中课程
        val id = db.courseDao().insert(
            CourseEntity(
                title = "课程@finalize",
                startTs = 1_000L,
                endTs = null,
                status = "RUNNING",
            ),
        )

        // 首次收尾：变为 COMPLETED 且写入 endTs=2000
        repository.finalizeCourse(id, 2000L)

        var course = db.courseDao().getById(id)!!
        assertEquals("COMPLETED", course.status)
        assertEquals(2000L, course.endTs)

        // 重复收尾（幂等）：以 3000 再次调用，不得覆盖首次写入的 endTs
        repository.finalizeCourse(id, 3000L)

        course = db.courseDao().getById(id)!!
        assertEquals("COMPLETED", course.status)
        assertEquals(2000L, course.endTs)
        assertNull(course.summaryMd)
    }

    @Test
    fun `abortStale marks stale running course aborted with cutoff endTs`() = runBlocking {
        // 合成测试数据：status=RUNNING、startTs 早于 cutoff、endTs=null 的过期课程
        val id = db.courseDao().insert(
            CourseEntity(
                title = "课程@abortStale",
                startTs = 1_000L,
                endTs = null,
                status = "RUNNING",
            ),
        )

        // 过期判定：startTs(1000) < cutoff(10000) 应被置为 ABORTED 并写入 endTs=cutoff
        repository.abortStale(10_000L)

        val course = db.courseDao().getById(id)!!
        assertEquals("ABORTED", course.status)
        assertEquals(10_000L, course.endTs)
    }

    @Test
    fun `findRunningCourses returns only open running courses`() = runBlocking {
        // 合成测试数据：一条 status=RUNNING、endTs=null 的进行中课程
        val runningId = db.courseDao().insert(
            CourseEntity(
                title = "课程@findRunning-running",
                startTs = 1_000L,
                endTs = null,
                status = "RUNNING",
            ),
        )
        // 一条 status=COMPLETED 且 endTs 非空的已结束课程
        db.courseDao().insert(
            CourseEntity(
                title = "课程@findRunning-completed",
                startTs = 2_000L,
                endTs = 3_000L,
                status = "COMPLETED",
            ),
        )

        // 只应返回第一条（仍在运行）课程，已结束课程必须被过滤掉
        val result = db.courseDao().findRunningCourses()
        assertEquals(listOf(runningId), result.map { it.id })
    }

    @Test
    fun `markRunning clears endTs and sets running status`() = runBlocking {
        // 合成测试数据：status=COMPLETED、endTs=3000 的已结束课程
        val id = db.courseDao().insert(
            CourseEntity(
                title = "课程@markRunning",
                startTs = 1_000L,
                endTs = 3_000L,
                status = "COMPLETED",
            ),
        )

        // 重新标记为进行中：变为 RUNNING 且清空 endTs
        db.courseDao().markRunning(id)

        val course = db.courseDao().getById(id)!!
        assertEquals("RUNNING", course.status)
        assertNull(course.endTs)
    }

    @Test
    fun `updateSummaryStatus persists status and error`() = runBlocking {
        // 合成测试数据：默认 summaryStatus=NONE、summaryError=null 的新课程
        val id = db.courseDao().insert(
            CourseEntity(
                title = "课程@updateSummaryStatus",
                startTs = 1_000L,
                endTs = 2_000L,
                status = "COMPLETED",
            ),
        )

        // 写入总结失败状态与脱敏错误信息
        db.courseDao().updateSummaryStatus(id, "FAILED", "redacted failure")

        // 读回验证：状态与错误均须持久化
        val course = db.courseDao().getById(id)!!
        assertEquals("FAILED", course.summaryStatus)
        assertEquals("redacted failure", course.summaryError)
    }

    @Test
    fun `createRunningCourse starts with running status`() = runBlocking {
        // 通过仓库创建新课程：应直接以 RUNNING 状态起步（而非实体默认的 COMPLETED）
        val id = repository.createRunningCourse("课程@createRunning", 1_000L)

        // 读回验证：status=RUNNING、endTs=null、title/startTs 保留
        val course = db.courseDao().getById(id)!!
        assertEquals("RUNNING", course.status)
        assertNull(course.endTs)
        assertEquals("课程@createRunning", course.title)
        assertEquals(1_000L, course.startTs)
    }

    @Test
    fun `updateSummary persists markdown status and safe error atomically`() = runBlocking {
        val id = db.courseDao().insert(
            CourseEntity(
                title = "课程@updateSummary",
                startTs = 1_000L,
                endTs = 2_000L,
                status = "COMPLETED",
            ),
        )

        db.courseDao().updateSummary(id, "SUCCEEDED", "## 知识点\n傅里叶", null)
        var course = db.courseDao().getById(id)!!
        assertEquals("SUCCEEDED", course.summaryStatus)
        assertEquals("## 知识点\n傅里叶", course.summaryMd)
        assertNull(course.summaryError)

        db.courseDao().updateSummary(id, "FAILED", null, "GENERATION_FAILED")
        course = db.courseDao().getById(id)!!
        assertEquals("FAILED", course.summaryStatus)
        assertNull(course.summaryMd)
        assertEquals("GENERATION_FAILED", course.summaryError)
    }

    @Test
    fun `markSummaryQueued clears stale summary and rejects duplicate in flight queue`() = runBlocking {
        val id = db.courseDao().insert(
            CourseEntity(
                title = "课程@manualSummary",
                startTs = 1_000L,
                endTs = 2_000L,
                summaryMd = "## 旧总结",
                summaryStatus = "FAILED",
                summaryError = "GENERATION_FAILED",
                status = "COMPLETED",
            ),
        )

        assertEquals(1, db.courseDao().markSummaryQueued(id))
        var course = db.courseDao().getById(id)!!
        assertEquals("QUEUED", course.summaryStatus)
        assertNull(course.summaryMd)
        assertNull(course.summaryError)

        assertEquals(0, db.courseDao().markSummaryQueued(id))
        course = db.courseDao().getById(id)!!
        assertEquals("QUEUED", course.summaryStatus)

        val runningId = db.courseDao().insert(
            CourseEntity(
                title = "课程@manualSummaryRunning",
                startTs = 1_000L,
                endTs = null,
                summaryStatus = "RUNNING",
                status = "RUNNING",
            ),
        )
        assertEquals(0, db.courseDao().markSummaryQueued(runningId))
    }
}
