package com.classsentinel.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.classsentinel.data.entities.EventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {

    @Insert
    suspend fun insert(event: EventEntity): Long

    /** 回填 LLM 答案 */
    @Query("UPDATE events SET answerText = :answer WHERE id = :eventId")
    suspend fun updateAnswer(eventId: Long, answer: String)

    /** 按课程查询事件，时间正序 */
    @Query("SELECT * FROM events WHERE courseId = :courseId ORDER BY ts ASC")
    suspend fun getForCourse(courseId: Long): List<EventEntity>

    @Query("SELECT * FROM events WHERE courseId = :courseId ORDER BY ts ASC")
    fun observeForCourse(courseId: Long): Flow<List<EventEntity>>

    /** 每课事件数（统计） */
    @Query("SELECT COUNT(*) FROM events WHERE courseId = :courseId")
    suspend fun countForCourse(courseId: Long): Int

    /** 每日计数基础：统计 [dayStart, dayEnd) 时间段内的事件总数 */
    @Query("SELECT COUNT(*) FROM events WHERE ts >= :dayStart AND ts < :dayEnd")
    suspend fun countInRange(dayStart: Long, dayEnd: Long): Int

    @Query("SELECT COUNT(*) FROM events")
    suspend fun countAll(): Int

    @Query("DELETE FROM events")
    suspend fun clearAll()
}