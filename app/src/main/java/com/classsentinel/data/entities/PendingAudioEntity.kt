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
    /** 原始课堂音频起点；恢复时不得丢失。 */
    val startOffsetMs: Long = 0L,
    /** 原始课堂音频终点；v4 旧行由 migration 从 durationMs 补齐。 */
    val endOffsetMs: Long = 0L,
    val state: String = "PENDING",
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdTs: Long,
)
