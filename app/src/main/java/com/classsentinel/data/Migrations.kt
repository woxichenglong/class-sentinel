package com.classsentinel.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE courses ADD COLUMN status TEXT NOT NULL DEFAULT 'COMPLETED'")
        db.execSQL("ALTER TABLE courses ADD COLUMN summaryStatus TEXT NOT NULL DEFAULT 'NONE'")
        db.execSQL("ALTER TABLE courses ADD COLUMN summaryError TEXT")
        db.execSQL("UPDATE courses SET status = CASE WHEN endTs IS NULL THEN 'ABORTED' ELSE 'COMPLETED' END")

        db.execSQL("ALTER TABLE transcript_chunks ADD COLUMN segmentId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE transcript_chunks ADD COLUMN startOffsetMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE transcript_chunks ADD COLUMN endOffsetMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE transcript_chunks ADD COLUMN isMarked INTEGER NOT NULL DEFAULT 0")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_audio_segments (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                courseId INTEGER NOT NULL,
                segmentId TEXT NOT NULL,
                filePath TEXT NOT NULL,
                durationMs INTEGER NOT NULL,
                state TEXT NOT NULL DEFAULT 'PENDING',
                attempts INTEGER NOT NULL DEFAULT 0,
                lastError TEXT,
                createdTs INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_pending_audio_segments_courseId_segmentId " +
                "ON pending_audio_segments(courseId, segmentId)",
        )
    }
}

/** v2 → v3：为每门课程增加一个按类型唯一的学习产物记录。 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS study_artifacts (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                courseId INTEGER NOT NULL,
                type TEXT NOT NULL,
                status TEXT NOT NULL,
                contentJson TEXT,
                model TEXT,
                error TEXT,
                createdTs INTEGER NOT NULL,
                updatedTs INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_study_artifacts_courseId_type " +
                "ON study_artifacts(courseId, type)",
        )
    }
}
