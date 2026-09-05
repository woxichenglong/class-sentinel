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

/** v3 → v4 recoveryKey migration：保留旧行，live NULL key 不被唯一索引误伤。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Migration3To4Test {
    @Test
    fun `v3 transcript data survives recovery key migration`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE transcript_chunks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, courseId INTEGER NOT NULL, seq INTEGER NOT NULL, text TEXT NOT NULL, ts INTEGER NOT NULL, segmentId TEXT NOT NULL, startOffsetMs INTEGER NOT NULL, endOffsetMs INTEGER NOT NULL, isMarked INTEGER NOT NULL)",
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
                "INSERT INTO transcript_chunks (id,courseId,seq,text,ts,segmentId,startOffsetMs,endOffsetMs,isMarked) VALUES (1,7,1,'旧句',1100,'',0,1000,0)",
            )
            MIGRATION_3_4.migrate(db)

            db.query("PRAGMA table_info(transcript_chunks)").use { cursor ->
                val columns = buildList {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue("new nullable recoveryKey column exists", columns.contains("recoveryKey"))
            }
            db.query("SELECT text,recoveryKey FROM transcript_chunks WHERE id=1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("旧句", cursor.getString(0))
                assertTrue(cursor.isNull(1))
            }

            // SQLite UNIQUE index 对 NULL 保持可重复，live path 不会因空 recovery key 被拦截。
            db.execSQL(
                "INSERT INTO transcript_chunks (courseId,seq,text,ts,segmentId,recoveryKey,startOffsetMs,endOffsetMs,isMarked) VALUES (7,2,'live-2',1200,'',NULL,1000,2000,0)",
            )
            db.execSQL(
                "INSERT INTO transcript_chunks (courseId,seq,text,ts,segmentId,recoveryKey,startOffsetMs,endOffsetMs,isMarked) VALUES (7,3,'recovery',1300,'s1','pending-audio:9',2000,3000,0)",
            )
            var duplicateRejected = false
            try {
                db.execSQL(
                    "INSERT INTO transcript_chunks (courseId,seq,text,ts,segmentId,recoveryKey,startOffsetMs,endOffsetMs,isMarked) VALUES (7,4,'duplicate',1400,'s1','pending-audio:9',3000,4000,0)",
                )
            } catch (_: Exception) {
                duplicateRejected = true
            }
            assertTrue("same recovery key must be unique per course", duplicateRejected)
        } finally {
            helper.close()
        }
    }
}
