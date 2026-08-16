package com.classsentinel.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 课堂事件（点名 / 提问），归属于一次课程。
 * type 取 EventType 的 name：ROLLCALL | QUESTION。
 */
@Entity(
    tableName = "events",
    indices = [Index(value = ["courseId"])],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val courseId: Long,
    /** "ROLLCALL" | "QUESTION" */
    val type: String,
    /** 触发句 */
    val triggerText: String,
    /** 上下文（当前版本 = 触发句） */
    val contextText: String,
    /** AI 回答，未回答为 null */
    val answerText: String? = null,
    /** 提醒下发时间 */
    val notifiedAt: Long,
    /** 事件发生时间 */
    val ts: Long,
)