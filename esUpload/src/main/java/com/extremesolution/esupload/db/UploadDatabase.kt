package com.extremesolution.esupload.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [UploadEntity::class], version = 1, exportSchema = false)
abstract class UploadDatabase : RoomDatabase() {
    abstract fun uploadDao(): UploadDao
}
