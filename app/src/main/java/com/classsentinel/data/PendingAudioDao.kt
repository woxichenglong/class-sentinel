package com.classsentinel.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.classsentinel.data.entities.PendingAudioEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingAudioDao {
    @Insert
    suspend fun insert(segment: PendingAudioEntity): Long

    @Query("SELECT * FROM pending_audio_segments WHERE courseId = :courseId ORDER BY createdTs ASC")
    fun observeForCourse(courseId: Long): Flow<List<PendingAudioEntity>>

    @Query("SELECT * FROM pending_audio_segments WHERE state = :state ORDER BY createdTs ASC")
    suspend fun getByState(state: String): List<PendingAudioEntity>

    @Query("UPDATE pending_audio_segments SET state = :state, attempts = :attempts, lastError = :lastError WHERE id = :id")
    suspend fun updateState(id: Long, state: String, attempts: Int, lastError: String?)

    @Delete
    suspend fun delete(segment: PendingAudioEntity)

    @Query("DELETE FROM pending_audio_segments WHERE courseId = :courseId")
    suspend fun deleteForCourse(courseId: Long)

    @Query("SELECT * FROM pending_audio_segments ORDER BY createdTs ASC, id ASC")
    suspend fun getAll(): List<PendingAudioEntity>

    @Query("DELETE FROM pending_audio_segments")
    suspend fun clearAll()
}
