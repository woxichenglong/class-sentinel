package com.classsentinel.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.classsentinel.data.entities.CourseEntity
import com.classsentinel.data.entities.EventEntity
import com.classsentinel.data.entities.TranscriptChunkEntity

/**
 * 应用数据库：课程 / 课堂事件 / 转写块。
 * version 1 首次发布；exportSchema=false 不做 schema 导出。
 */
@Database(
    entities = [CourseEntity::class, EventEntity::class, TranscriptChunkEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun courseDao(): CourseDao

    abstract fun eventDao(): EventDao

    abstract fun transcriptDao(): TranscriptDao

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
                ).build().also { instance = it }
            }
    }
}