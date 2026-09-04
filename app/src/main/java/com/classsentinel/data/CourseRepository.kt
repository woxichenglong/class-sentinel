package com.classsentinel.data

import androidx.room.withTransaction
import com.classsentinel.data.entities.CourseEntity

/** 超过该时长仍是 RUNNING 的课程才视为进程异常遗留，避免误收正在进行的课程。 */
const val STALE_RUNNING_COURSE_TIMEOUT_MS = 5 * 60 * 1_000L

/**
 * 课程数据仓库。
 *
 * 当前仅承载课程收尾逻辑：finalizeCourse 在单个 Room 事务内调用
 * [CourseDao.finalizeCourse]，由 DAO 的 WHERE 条件（status='RUNNING'
 * 且 endTs IS NULL）保证幂等——重复调用不会覆盖首次写入的 endTs。
 */
class CourseRepository(private val db: AppDatabase) {

    /**
     * 事务性创建新课程：插入即 RUNNING 且 endTs 为空（进行中）。
     * 返回新课程的自增 id。
     */
    suspend fun createRunningCourse(title: String, startTs: Long): Long {
        return db.withTransaction {
            db.courseDao().insert(
                CourseEntity(
                    title = title,
                    startTs = startTs,
                    endTs = null,
                    status = "RUNNING",
                )
            )
        }
    }

    /**
     * 幂等收尾课程：将 RUNNING 且未结束的课程置为 COMPLETED 并写入 endTs。
     * 重复调用（已 COMPLETED 或已有 endTs）不生效，返回 Unit。
     */
    suspend fun finalizeCourse(id: Long, endTs: Long) {
        db.withTransaction {
            db.courseDao().finalizeCourse(id, endTs)
        }
    }

    /**
     * 幂等中止过期课程：将 cutoff 前仍 RUNNING 且未结束的课程置为 ABORTED 并写入 endTs。
     * 已结束或已非 RUNNING 的课程不受影响，返回 Unit。
     */
    suspend fun abortStale(cutoffTs: Long) {
        db.withTransaction {
            db.courseDao().abortStale(cutoffTs)
        }
    }
}
