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

    /** 只观察课程内已标记句子；仍按原课堂顺序返回。 */
    @Query("SELECT * FROM transcript_chunks WHERE courseId = :courseId AND isMarked = 1 ORDER BY seq ASC")
    fun observeMarkedForCourse(courseId: Long): Flow<List<TranscriptChunkEntity>>

    /** 带课程条件更新，防止详情页拿到旧 id 后误标另一门课。 */
    @Query("UPDATE transcript_chunks SET isMarked = 1 WHERE id = :chunkId AND courseId = :courseId")
    suspend fun mark(chunkId: Long, courseId: Long): Int

    @Query("UPDATE transcript_chunks SET isMarked = 0 WHERE id = :chunkId AND courseId = :courseId")
    suspend fun unmark(chunkId: Long, courseId: Long): Int

    /** 课程内当前最大 seq，无数据返回 0 */
    @Query("SELECT COALESCE(MAX(seq), 0) FROM transcript_chunks WHERE courseId = :courseId")
    suspend fun maxSeq(courseId: Long): Int

    @Query("SELECT COUNT(*) FROM transcript_chunks")
    suspend fun countAll(): Int

    @Query("DELETE FROM transcript_chunks WHERE courseId = :courseId")
    suspend fun deleteForCourse(courseId: Long): Int

    @Query("DELETE FROM transcript_chunks")
    suspend fun clearAll()
}