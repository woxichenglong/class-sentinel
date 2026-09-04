package com.classsentinel.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.classsentinel.data.entities.CourseEntity
import kotlinx.coroutines.flow.Flow

/** 课程 + 每课事件数（History 列表项） */
data class CourseSummary(
    @Embedded val course: CourseEntity,
    @ColumnInfo(name = "event_count") val eventCount: Int,
)

@Dao
interface CourseDao {

    /** 插入课程，返回自增 id */
    @Insert
    suspend fun insert(course: CourseEntity): Long

    @Update
    suspend fun update(course: CourseEntity)

    /** 记录课程结束时间（STOP / onDestroy 时调用） */
    @Query("UPDATE courses SET endTs = :endTs WHERE id = :id")
    suspend fun updateEndTs(id: Long, endTs: Long)

    /**
     * 幂等收尾：仅当课程仍为 RUNNING 且尚未结束时，将其置为 COMPLETED 并写入 endTs。
     * 返回更新的行数；重复调用（已 COMPLETED 或已有 endTs）返回 0，不覆盖首次结束时间。
     */
    @Query(
        "UPDATE courses SET status = 'COMPLETED', endTs = :endTs " +
            "WHERE id = :id AND status = 'RUNNING' AND endTs IS NULL"
    )
    suspend fun finalizeCourse(id: Long, endTs: Long): Int

    /**
     * 批量中止过期课程：将 cutoff 前仍 RUNNING 且尚未结束的课程置为 ABORTED 并写入 endTs。
     * 返回更新的行数；已结束或已非 RUNNING 的课程不受影响。
     */
    @Query(
        "UPDATE courses SET status = 'ABORTED', endTs = :cutoffTs " +
            "WHERE status = 'RUNNING' AND endTs IS NULL AND startTs < :cutoffTs"
    )
    suspend fun abortStale(cutoffTs: Long): Int

    /**
     * 将指定课程置为 RUNNING 并清空 endTs（重新开始/恢复场景）。
     * 返回更新的行数；id 不存在时返回 0。
     */
    @Query("UPDATE courses SET status = 'RUNNING', endTs = NULL WHERE id = :id")
    suspend fun markRunning(id: Long): Int

    /** 持久化课程摘要状态；error 可空。返回更新的行数；id 不存在时返回 0 */
    @Query("UPDATE courses SET summaryStatus = :status, summaryError = :error WHERE id = :id")
    suspend fun updateSummaryStatus(id: Long, status: String, error: String?): Int

    /** 原子写入摘要正文、状态和安全错误码；失败时正文必须被清空。 */
    @Query(
        "UPDATE courses SET summaryMd = :markdown, summaryStatus = :status, " +
            "summaryError = :errorCode WHERE id = :id",
    )
    suspend fun updateSummary(id: Long, status: String, markdown: String?, errorCode: String?): Int

    /**
     * 为手动生成/重试原子地创建一个新的排队状态；已排队或运行中的课程拒绝重复入队。
     * 返回更新的行数；不存在或已有进行中任务时返回 0。
     */
    @Query(
        "UPDATE courses SET summaryMd = NULL, summaryStatus = 'QUEUED', " +
            "summaryError = NULL WHERE id = :id AND summaryStatus NOT IN ('QUEUED', 'RUNNING')",
    )
    suspend fun markSummaryQueued(id: Long): Int

    /** 查询所有尚未结束的 RUNNING 课程（endTs 为空），按开始时间升序，便于旧课程恢复 */
    @Query(
        "SELECT * FROM courses WHERE status = 'RUNNING' AND endTs IS NULL " +
            "ORDER BY startTs ASC"
    )
    suspend fun findRunningCourses(): List<CourseEntity>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun getById(id: Long): CourseEntity?

    @Query("SELECT * FROM courses WHERE id = :id")
    fun observeById(id: Long): Flow<CourseEntity?>

    @Query("SELECT * FROM courses ORDER BY startTs DESC")
    suspend fun getAll(): List<CourseEntity>

    @Query("SELECT * FROM courses ORDER BY startTs DESC")
    fun observeAll(): Flow<List<CourseEntity>>

    /** 查询已结束且早于 cutoff 的课程；进行中的课程不参与自动保留清理。 */
    @Query("SELECT * FROM courses WHERE endTs IS NOT NULL AND startTs < :cutoffTs ORDER BY startTs ASC")
    suspend fun getFinishedBefore(cutoffTs: Long): List<CourseEntity>

    /** 课程列表 + 每课事件数，按开始时间倒序（History 数据源） */
    @Query(
        """
        SELECT c.*, COUNT(e.id) AS event_count
        FROM courses c
        LEFT JOIN events e ON e.courseId = c.id
        GROUP BY c.id
        ORDER BY c.startTs DESC
        """
    )
    fun observeSummaries(): Flow<List<CourseSummary>>

    @Query("DELETE FROM courses")
    suspend fun clearAll()

    @Query("DELETE FROM courses WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}