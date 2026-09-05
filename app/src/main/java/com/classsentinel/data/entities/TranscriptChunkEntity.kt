package com.classsentinel.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 转写文本块（VAD 分句后的每条句子）。seq 保留数据库写入顺序；历史读取优先按
 * 原始课堂 offset 排序，旧的 0/0 offset 行再按 seq 回退。
 */
@Entity(
    tableName = "transcript_chunks",
    indices = [
        Index(value = ["courseId", "seq"]),
        // 仅恢复路径使用；live transcript 的 recoveryKey 保持 NULL，避免污染空 segmentId。
        Index(value = ["courseId", "recoveryKey"], unique = true),
    ],
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
    /** VAD 分句片段 ID */
    val segmentId: String = "",
    /** pending recovery 的稳定幂等键；live transcript 为 NULL。 */
    val recoveryKey: String? = null,
    /** 片段起始偏移（毫秒） */
    val startOffsetMs: Long = 0L,
    /** 片段结束偏移（毫秒） */
    val endOffsetMs: Long = 0L,
    /** 是否被标记 */
    val isMarked: Boolean = false,
)