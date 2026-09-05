package com.classsentinel.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.classsentinel.data.entities.CourseEntity
import com.classsentinel.data.entities.EventEntity
import com.classsentinel.data.entities.PendingAudioEntity
import com.classsentinel.data.entities.StudyArtifactEntity
import com.classsentinel.data.entities.TranscriptChunkEntity

/**
 * 应用数据库：课程 / 课堂事件 / 转写块 / 待上传音频段。
 * version 1 首次发布；version 2 新增 pending_audio_segments 表；version 3 新增学习产物表；
 * version 4 为 recovery transcript 增加只针对恢复路径的幂等键；version 5 保存 pending 原始 offset。
 * exportSchema=true，导出 Room schema。
 */
@Database(
    entities = [
        CourseEntity::class,
        EventEntity::class,
        TranscriptChunkEntity::class,
        PendingAudioEntity::class,
        StudyArtifactEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun courseDao(): CourseDao

    abstract fun eventDao(): EventDao

    abstract fun transcriptDao(): TranscriptDao

    abstract fun pendingAudioDao(): PendingAudioDao

    abstract fun studyArtifactDao(): StudyArtifactDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** 进程内单例；数据库操作请放到 IO 线程 */
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "class_sentinel.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
            }
    }
}