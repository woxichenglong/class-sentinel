package com.classsentinel.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.classsentinel.data.entities.TranscriptChunkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptDao {

    @Insert
    suspend fun insert(chunk: TranscriptChunkEntity): Long

    /** 按课程查询转写块，按 seq 升序（时间线顺序） */
    @Query("SELECT * FROM transcript_chunks WHERE courseId = :courseId ORDER BY seq ASC")
    suspend fun getForCourse(courseId: Long): List<TranscriptChunkEntity>

    @Query("SELECT * FROM transcript_chunks WHERE courseId = :courseId ORDER BY seq ASC")
    fun observeForCourse(courseId: Long): Flow<List<TranscriptChunkEntity>>

    /** 课程内当前最大 seq，无数据返回 0 */
    @Query("SELECT COALESCE(MAX(seq), 0) FROM transcript_chunks WHERE courseId = :courseId")
    suspend fun maxSeq(courseId: Long): Int

    @Query("SELECT COUNT(*) FROM transcript_chunks")
    suspend fun countAll(): Int

    @Query("DELETE FROM transcript_chunks")
    suspend fun clearAll()
}