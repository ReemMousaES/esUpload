package com.extremesolution.esupload.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [UploadEntity::class], version = 2, exportSchema = false)
abstract class UploadDatabase : RoomDatabase() {
    abstract fun uploadDao(): UploadDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE upload_queue ADD COLUMN notificationTitle TEXT NOT NULL DEFAULT 'Uploading file'"
                )
                database.execSQL(
                    "ALTER TABLE upload_queue ADD COLUMN notificationFileName TEXT"
                )
            }
        }
    }
}
