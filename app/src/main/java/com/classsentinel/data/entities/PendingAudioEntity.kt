package com.classsentinel.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_audio_segments",
    indices = [Index(value = ["courseId", "segmentId"], unique = true)],
)
data class PendingAudioEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val courseId: Long,
    val segmentId: String,
    val filePath: String,
    val durationMs: Long,
    val state: String = "PENDING",
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdTs: Long,
)
