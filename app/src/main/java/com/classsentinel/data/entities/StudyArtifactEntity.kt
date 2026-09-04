package com.classsentinel.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 课程级学习产物：闪卡、小测或双语复习结果。
 * 内容使用严格 JSON 保存；生成失败只保存安全错误码，不保存 provider 原文。
 */
@Entity(
    tableName = "study_artifacts",
    indices = [Index(value = ["courseId", "type"], unique = true)],
)
data class StudyArtifactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val courseId: Long,
    val type: String,
    val status: String = STATUS_NONE,
    val contentJson: String? = null,
    val model: String? = null,
    val error: String? = null,
    val createdTs: Long,
    val updatedTs: Long,
) {
    companion object {
        const val TYPE_FLASHCARDS = "FLASHCARDS"
        const val TYPE_QUIZ = "QUIZ"
        const val TYPE_BILINGUAL_SUMMARY = "BILINGUAL_SUMMARY"

        const val STATUS_NONE = "NONE"
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_SUCCEEDED = "SUCCEEDED"
        const val STATUS_FAILED = "FAILED"
    }
}
