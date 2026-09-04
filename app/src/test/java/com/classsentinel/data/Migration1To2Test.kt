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
class Migration1To2Test {
    @Test
    fun `v1 data survives v2 migration with durable session metadata`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE courses (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, startTs INTEGER NOT NULL, endTs INTEGER, summaryMd TEXT)")
                        db.execSQL("CREATE TABLE transcript_chunks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, courseId INTEGER NOT NULL, seq INTEGER NOT NULL, text TEXT NOT NULL, ts INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, courseId INTEGER NOT NULL, type TEXT NOT NULL, triggerText TEXT NOT NULL, contextText TEXT NOT NULL, answerText TEXT, notifiedAt INTEGER NOT NULL, ts INTEGER NOT NULL)")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val db = helper.writableDatabase
        try {
            db.execSQL("INSERT INTO courses (id,title,startTs,endTs,summaryMd) VALUES (1,'旧课',1000,NULL,NULL)")
            db.execSQL("INSERT INTO courses (id,title,startTs,endTs,summaryMd) VALUES (2,'已完成旧课',2000,1900,NULL)")
            db.execSQL("INSERT INTO transcript_chunks (id,courseId,seq,text,ts) VALUES (1,1,0,'旧句',1100)")

            MIGRATION_1_2.migrate(db)

            val courseColumns = db.query("PRAGMA table_info(courses)").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            assertTrue(courseColumns.containsAll(listOf("status", "summaryStatus", "summaryError")))
            val transcriptColumns = db.query("PRAGMA table_info(transcript_chunks)").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            }
            assertTrue(transcriptColumns.containsAll(listOf("segmentId", "startOffsetMs", "endOffsetMs", "isMarked")))

            db.query("SELECT title,startTs,status,summaryStatus,summaryError FROM courses WHERE id=1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("旧课", cursor.getString(0))
                assertEquals(1000L, cursor.getLong(1))
                assertEquals("ABORTED", cursor.getString(2))
                assertEquals("NONE", cursor.getString(3))
                assertTrue(cursor.isNull(4))
            }
            db.query("SELECT status FROM courses WHERE id=2").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("COMPLETED", cursor.getString(0))
            }
            db.query("SELECT text,segmentId,startOffsetMs,endOffsetMs,isMarked FROM transcript_chunks WHERE id=1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("旧句", cursor.getString(0))
                assertEquals("", cursor.getString(1))
                assertEquals(0L, cursor.getLong(2))
                assertEquals(0L, cursor.getLong(3))
                assertEquals(0L, cursor.getLong(4))
            }
            db.query("SELECT COUNT(*) FROM pending_audio_segments").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }
        } finally {
            helper.close()
        }
    }
}
