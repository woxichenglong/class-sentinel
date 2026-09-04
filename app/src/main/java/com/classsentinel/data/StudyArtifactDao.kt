package com.classsentinel.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.classsentinel.data.entities.StudyArtifactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StudyArtifactDao {
    /** 插入新产物；相同课程/类型已存在时返回 -1，避免并发重复行。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(artifact: StudyArtifactEntity): Long

    @Update
    suspend fun update(artifact: StudyArtifactEntity)

    @Query("SELECT * FROM study_artifacts WHERE courseId = :courseId AND type = :type LIMIT 1")
    suspend fun getForCourseAndType(courseId: Long, type: String): StudyArtifactEntity?

    @Query("SELECT * FROM study_artifacts WHERE courseId = :courseId ORDER BY updatedTs DESC, id DESC")
    suspend fun getForCourse(courseId: Long): List<StudyArtifactEntity>

    @Query("SELECT * FROM study_artifacts WHERE courseId = :courseId ORDER BY updatedTs DESC, id DESC")
    fun observeForCourse(courseId: Long): Flow<List<StudyArtifactEntity>>

    @Query(
        "UPDATE study_artifacts SET status = :status, contentJson = :contentJson, " +
            "model = :model, error = :error, updatedTs = :updatedTs WHERE id = :id",
    )
    suspend fun updateContent(
        id: Long,
        status: String,
        contentJson: String?,
        model: String?,
        error: String?,
        updatedTs: Long,
    ): Int

    @Query("UPDATE study_artifacts SET status = :status, error = :error, updatedTs = :updatedTs WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, error: String?, updatedTs: Long): Int

    @Query("DELETE FROM study_artifacts WHERE courseId = :courseId")
    suspend fun deleteForCourse(courseId: Long): Int

    @Query("DELETE FROM study_artifacts")
    suspend fun clearAll(): Int

    /**
     * 确保一个课程/类型只有一个产物，并保留首次创建时间。
     * 插入竞争时以数据库唯一索引为最终裁判，不制造重复行。
     */
    @Transaction
    suspend fun upsert(artifact: StudyArtifactEntity): Long {
        val current = getForCourseAndType(artifact.courseId, artifact.type)
        if (current != null) {
            update(artifact.copy(id = current.id, createdTs = current.createdTs))
            return current.id
        }
        val insertedId = insertIfAbsent(artifact)
        if (insertedId != -1L) return insertedId
        return getForCourseAndType(artifact.courseId, artifact.type)?.id
            ?: error("study artifact insert conflict without existing row")
    }
}
