package com.classsentinel.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 转写文本块（VAD 分句后的每条句子），按 seq 在课程内有序。
 */
@Entity(
    tableName = "transcript_chunks",
    indices = [Index(value = ["courseId", "seq"])],
)
data class TranscriptChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val courseId: Long,
    /** 课程内递增序号，用于时间线排序 */
    val seq: Int,
    /** 转写文本 */
    val text: String,
    /** 转写时间 */
    val ts: Long,
)