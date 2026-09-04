package com.classsentinel.data

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration2To3Test {
    @Test
    fun `v2 data survives study artifact migration and artifact type is unique per course`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE courses (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, startTs INTEGER NOT NULL, endTs INTEGER, summaryMd TEXT, status TEXT NOT NULL, summaryStatus TEXT NOT NULL, summaryError TEXT)",
                        )
                        db.execSQL(
                            "CREATE TABLE transcript_chunks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, courseId INTEGER NOT NULL, seq INTEGER NOT NULL, text TEXT NOT NULL, ts INTEGER NOT NULL, segmentId TEXT NOT NULL, startOffsetMs INTEGER NOT NULL, endOffsetMs INTEGER NOT NULL, isMarked INTEGER NOT NULL)",
                        )
                        db.execSQL(
                            "CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, courseId INTEGER NOT NULL, type TEXT NOT NULL, triggerText TEXT NOT NULL, contextText TEXT NOT NULL, answerText TEXT, notifiedAt INTEGER NOT NULL, ts INTEGER NOT NULL)",
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
            db.execSQL("INSERT INTO courses (id,title,startTs,status,summaryStatus) VALUES (7,'旧课程',1000,'COMPLETED','NONE')")
            db.execSQL("INSERT INTO transcript_chunks (id,courseId,seq,text,ts,segmentId,startOffsetMs,endOffsetMs,isMarked) VALUES (8,7,1,'旧句',1100,'s1',0,1000,0)")
            db.execSQL("INSERT INTO events (id,courseId,type,triggerText,contextText,notifiedAt,ts) VALUES (9,7,'QUESTION','旧触发','旧上下文',1200,1200)")
            db.execSQL("INSERT INTO pending_audio_segments (id,courseId,segmentId,filePath,durationMs,state,attempts,createdTs) VALUES (10,7,'s2','pending/s2.wav',1000,'PENDING',0,1300)")

            MIGRATION_2_3.migrate(db)

            val columns = db.query("PRAGMA table_info(study_artifacts)").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                    }
                }
            }
            assertEquals(
                listOf("id", "courseId", "type", "status", "contentJson", "model", "error", "createdTs", "updatedTs"),
                columns,
            )

            db.execSQL(
                "INSERT INTO study_artifacts (courseId,type,status,contentJson,model,error,createdTs,updatedTs) VALUES (7,'FLASHCARDS','SUCCEEDED','[]','test-model',NULL,2000,2000)",
            )
            db.query("SELECT courseId,type,status,contentJson,model,error FROM study_artifacts").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(7L, cursor.getLong(0))
                assertEquals("FLASHCARDS", cursor.getString(1))
                assertEquals("SUCCEEDED", cursor.getString(2))
                assertEquals("[]", cursor.getString(3))
                assertEquals("test-model", cursor.getString(4))
                assertTrue(cursor.isNull(5))
            }

            db.query("SELECT title FROM courses WHERE id = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("旧课程", cursor.getString(0))
            }
            db.query("SELECT text FROM transcript_chunks WHERE id = 8").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("旧句", cursor.getString(0))
            }
            db.query("SELECT COUNT(*) FROM events WHERE courseId = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
            }
            db.query("SELECT COUNT(*) FROM pending_audio_segments WHERE courseId = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
            }

            var duplicateRejected = false
            try {
                db.execSQL(
                    "INSERT INTO study_artifacts (courseId,type,status,createdTs,updatedTs) VALUES (7,'FLASHCARDS','QUEUED',3000,3000)",
                )
            } catch (_: Exception) {
                duplicateRejected = true
            }
            assertTrue("same course/type must be unique", duplicateRejected)
        } finally {
            helper.close()
        }
    }
}
