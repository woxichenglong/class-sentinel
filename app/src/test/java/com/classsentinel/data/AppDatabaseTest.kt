package com.classsentinel.data

import androidx.room.Room
import com.classsentinel.data.entities.CourseEntity
import com.classsentinel.data.entities.EventEntity
import com.classsentinel.data.entities.TranscriptChunkEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Room 数据库集成测试（Robolectric 提供 Android 框架影子实现 + 原生 SQLite）。
 * 覆盖：课程 CRUD / 事件统计与每日计数 / 转写块排序 / 三表联合完整性。
 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun course(start: Long, end: Long? = null) = CourseEntity(
        title = "课程@$start",
        startTs = start,
        endTs = end,
    )

    private fun event(courseId: Long, type: String = "QUESTION", ts: Long = 1000L) = EventEntity(
        courseId = courseId,
        type = type,
        triggerText = "触发句-$ts",
        contextText = "上下文-$ts",
        answerText = null,
        notifiedAt = ts,
        ts = ts,
    )

    private fun chunk(courseId: Long, seq: Int, ts: Long = seq * 1000L) = TranscriptChunkEntity(
        courseId = courseId,
        seq = seq,
        text = "转写-$seq",
        ts = ts,
    )

    @Test
    fun `course insert - update endTs - query by id - ordered by startTs desc`() = runBlocking {
        val c1 = db.courseDao().insert(course(start = 1_000L))
        val c2 = db.courseDao().insert(course(start = 2_000L))
        val c3 = db.courseDao().insert(course(start = 3_000L))

        db.courseDao().updateEndTs(c1, 10_000L)

        val loaded = db.courseDao().getById(c1)
        assertNotNull(loaded)
        assertEquals(10_000L, loaded!!.endTs)
        assertNull(db.courseDao().getById(c2)?.endTs)
        assertEquals("课程@1000", loaded.title)

        // startTs 倒序
        val all = db.courseDao().getAll()
        assertEquals(listOf(c3, c2, c1), all.map { it.id })
        assertTrue(all.first().startTs > all.last().startTs)
    }

    @Test
    fun `event insert - per course query - statistics - daily range count`() = runBlocking {
        val cid = db.courseDao().insert(course(start = 1_000L))
        db.eventDao().insert(event(cid, "ROLLCALL", ts = 1_000L))
        db.eventDao().insert(event(cid, "QUESTION", ts = 2_000L))
        db.eventDao().insert(event(cid, "QUESTION", ts = 3_000L))
        val other = db.courseDao().insert(course(start = 200_000L))
        db.eventDao().insert(event(other, type = "QUESTION", ts = 4_000L))

        // 按课程查询 + 时间正序
        val events = db.eventDao().getForCourse(cid)
        assertEquals(3, events.size)
        assertEquals(listOf(1_000L, 2_000L, 3_000L), events.map { it.ts })
        assertTrue(events.all { it.courseId == cid })

        // 统计
        assertEquals(3, db.eventDao().countForCourse(cid))
        assertEquals(1, db.eventDao().countForCourse(other))
        assertEquals(4, db.eventDao().countAll())

        // 每日计数（时间窗 [dayStart, dayEnd)）
        assertEquals(3, db.eventDao().countInRange(1_000L, 4_000L))
        assertEquals(1, db.eventDao().countInRange(4_000L, 400_000L))
        assertEquals(0, db.eventDao().countInRange(0L, 999L))
    }

    @Test
    fun `transcript chunks insert - seq ordering - maxSeq`() = runBlocking {
        val cid = db.courseDao().insert(course(start = 1_000L))
        // 故意乱序插入，查询必须按 seq 升序
        db.transcriptDao().insert(chunk(cid, seq = 3))
        db.transcriptDao().insert(chunk(cid, seq = 1))
        db.transcriptDao().insert(chunk(cid, seq = 2))
        val other = db.courseDao().insert(course(start = 9_000L))
        db.transcriptDao().insert(chunk(other, seq = 1))

        val chunks = db.transcriptDao().getForCourse(cid)
        assertEquals(listOf(1, 2, 3), chunks.map { it.seq })
        assertEquals("转写-1", chunks.first().text)
        assertEquals("转写-3", chunks.last().text)

        assertEquals(3, db.transcriptDao().maxSeq(cid))
        assertEquals(1, db.transcriptDao().maxSeq(other))
        assertEquals(4, db.transcriptDao().countAll())
    }

    @Test
    fun `cross table - course with events and chunks stays complete`() = runBlocking {
        val cid = db.courseDao().insert(course(start = 1_000L, end = 10_000L))
        db.eventDao().insert(event(cid, "ROLLCALL", ts = 2_000L))
        db.eventDao().insert(event(cid, "QUESTION", ts = 3_000L))
        db.transcriptDao().insert(chunk(cid, seq = 1))
        db.transcriptDao().insert(chunk(cid, seq = 2))

        // 课程完整
        val course = db.courseDao().getById(cid)!!
        assertEquals(1_000L, course.startTs)
        assertEquals(10_000L, course.endTs)

        // 事件与转写块都归属该课程
        val events = db.eventDao().getForCourse(cid)
        val chunks = db.transcriptDao().getForCourse(cid)
        assertTrue(events.all { it.courseId == cid })
        assertTrue(chunks.all { it.courseId == cid })
        assertEquals(2, events.size)
        assertEquals(2, chunks.size)
        assertEquals(listOf("ROLLCALL", "QUESTION"), events.map { it.type })

        // 联合统计：CourseSummary 事件数 = 全表事件数
        val summaries = db.courseDao().observeSummaries().first()
        assertEquals(1, summaries.size)
        assertEquals(2, summaries[0].eventCount)
        assertEquals(cid, summaries[0].course.id)
        assertEquals("课程@1000", summaries[0].course.title)
    }
}