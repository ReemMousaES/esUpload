package com.extremesolution.esupload.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.extremesolution.esupload.model.UploadStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Builds a database with the v1.0.0 schema (before the notification columns),
 * then opens it with Room at the current version. Room validates every column
 * after migrating, so a migration that doesn't match [UploadEntity] fails here.
 */
@RunWith(AndroidJUnit4::class)
class UploadDatabaseMigrationTest {

    private val dbName = "migration-test.db"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration 1 to 2 keeps existing rows and adds notification defaults`() = runTest {
        createVersion1Database()

        val db = Room.databaseBuilder(context, UploadDatabase::class.java, dbName)
            .addMigrations(UploadDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        val migrated = db.uploadDao().getById("legacy")!!
        db.close()

        assertEquals("/storage/photo.jpg", migrated.filePath)
        assertEquals("https://example.com/upload", migrated.url)
        assertEquals("{\"Authorization\":\"Bearer t\"}", migrated.headersJson)
        assertEquals(UploadStatus.FAILED, migrated.status)
        assertEquals(2, migrated.attemptCount)
        assertEquals("Uploading file", migrated.notificationTitle)
        assertNull(migrated.notificationFileName)
    }

    private fun createVersion1Database() {
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `upload_queue` (" +
                    "`uploadId` TEXT NOT NULL, `filePath` TEXT NOT NULL, `url` TEXT NOT NULL, " +
                    "`headersJson` TEXT NOT NULL, `paramsJson` TEXT NOT NULL, `fileParamName` TEXT NOT NULL, " +
                    "`maxRetries` INTEGER NOT NULL, `attemptCount` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                    "`progress` INTEGER NOT NULL, `responseCode` INTEGER, `responseBody` TEXT, " +
                    "`errorMessage` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`uploadId`))"
            )
            db.execSQL(
                "INSERT INTO upload_queue VALUES ('legacy', '/storage/photo.jpg', 'https://example.com/upload', " +
                    "'{\"Authorization\":\"Bearer t\"}', '{}', 'file', 5, 2, 'FAILED', 30, 500, 'err', 'boom', 1, 2)"
            )
            db.version = 1
        }
    }
}
