package com.classsentinel.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一次听讲课程（每次 START 建一行，STOP 时写 endTs）。
 * 待办：summaryMd 由后续 Phase 的 AI 总结回填。
 */
@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 课程标题（当前 = 开始时间，如 "2026-08-16 10:30"） */
    val title: String,
    /** 开始时间戳 */
    val startTs: Long,
    /** 结束时间戳；null = 仍在进行中 */
    val endTs: Long? = null,
    /** Markdown 总结，未总结为 null */
    val summaryMd: String? = null,
)