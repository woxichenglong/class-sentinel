package com.classsentinel.data

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** v4 → v5：旧 pending 与 transcript 不丢，旧 pending 的 end offset 可由 duration 恢复。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration4To5Test {
    @Test
    fun `old history and pending rows survive offset migration`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE transcript_chunks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, courseId INTEGER NOT NULL, seq INTEGER NOT NULL, text TEXT NOT NULL, ts INTEGER NOT NULL, segmentId TEXT NOT NULL, recoveryKey TEXT, startOffsetMs INTEGER NOT NULL, endOffsetMs INTEGER NOT NULL, isMarked INTEGER NOT NULL)",
                        )
                        db.execSQL(
                            "CREATE TABLE pending_audio_segments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, courseId INTEGER NOT NULL, segmentId TEXT NOT NULL, filePath TEXT NOT NULL, durationMs INTEGER NOT NULL, state TEXT NOT NULL, attempts INTEGER NOT NULL, lastError TEXT, createdTs INTEGER NOT NULL)",
                        )
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )
        val db = helper.writableDatabase
        try {
            db.execSQL(
                "INSERT INTO transcript_chunks (id,courseId,seq,text,ts,segmentId,recoveryKey,startOffsetMs,endOffsetMs,isMarked) VALUES (1,5,1,'旧历史',1000,'',NULL,0,0,0)",
            )
            db.execSQL(
                "INSERT INTO pending_audio_segments (id,courseId,segmentId,filePath,durationMs,state,attempts,createdTs) VALUES (2,5,'old-pending','private/old.wav',1200,'PENDING',0,2000)",
            )

            MIGRATION_4_5.migrate(db)

            db.query("SELECT text FROM transcript_chunks WHERE id=1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("旧历史", cursor.getString(0))
            }
            db.query("SELECT startOffsetMs,endOffsetMs FROM pending_audio_segments WHERE id=2").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
                assertEquals(1200L, cursor.getLong(1))
            }
            db.execSQL(
                "INSERT INTO pending_audio_segments (courseId,segmentId,filePath,durationMs,startOffsetMs,endOffsetMs,state,attempts,createdTs) VALUES (5,'new-pending','private/new.wav',1000,600000,601000,'PENDING',0,3000)",
            )
            db.query("SELECT COUNT(*) FROM pending_audio_segments WHERE courseId=5").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2L, cursor.getLong(0))
            }
        } finally {
            helper.close()
        }
    }
}
