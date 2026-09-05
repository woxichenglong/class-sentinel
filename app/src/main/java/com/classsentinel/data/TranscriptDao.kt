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

    @Query("SELECT id FROM transcript_chunks WHERE courseId = :courseId AND recoveryKey = :recoveryKey LIMIT 1")
    suspend fun findRecoveryId(courseId: Long, recoveryKey: String): Long?

    /** 按真实课堂 offset 排序；旧 0/0 行使用 seq 保持历史顺序，同 offset 再用 id 稳定打破平局。 */
    @Query("""
        SELECT * FROM transcript_chunks
        WHERE courseId = :courseId
        ORDER BY
            CASE WHEN startOffsetMs = 0 AND endOffsetMs = 0 THEN 1 ELSE 0 END ASC,
            startOffsetMs ASC,
            endOffsetMs ASC,
            seq ASC,
            id ASC
    """)
    suspend fun getForCourse(courseId: Long): List<TranscriptChunkEntity>

    @Query("""
        SELECT * FROM transcript_chunks
        WHERE courseId = :courseId
        ORDER BY
            CASE WHEN startOffsetMs = 0 AND endOffsetMs = 0 THEN 1 ELSE 0 END ASC,
            startOffsetMs ASC,
            endOffsetMs ASC,
            seq ASC,
            id ASC
    """)
    fun observeForCourse(courseId: Long): Flow<List<TranscriptChunkEntity>>

    /** 只观察课程内已标记句子；与完整历史使用同一课堂时间线排序。 */
    @Query("""
        SELECT * FROM transcript_chunks
        WHERE courseId = :courseId AND isMarked = 1
        ORDER BY
            CASE WHEN startOffsetMs = 0 AND endOffsetMs = 0 THEN 1 ELSE 0 END ASC,
            startOffsetMs ASC,
            endOffsetMs ASC,
            seq ASC,
            id ASC
    """)
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