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

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun getById(id: Long): CourseEntity?

    @Query("SELECT * FROM courses WHERE id = :id")
    fun observeById(id: Long): Flow<CourseEntity?>

    @Query("SELECT * FROM courses ORDER BY startTs DESC")
    suspend fun getAll(): List<CourseEntity>

    @Query("SELECT * FROM courses ORDER BY startTs DESC")
    fun observeAll(): Flow<List<CourseEntity>>

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
}