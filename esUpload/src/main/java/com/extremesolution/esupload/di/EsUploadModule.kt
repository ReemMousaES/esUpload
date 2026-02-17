package com.extremesolution.esupload.di

import android.content.Context
import androidx.room.Room
import com.extremesolution.esupload.db.UploadDao
import com.extremesolution.esupload.db.UploadDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EsUploadModule {

    @Provides
    @Singleton
    fun provideUploadDatabase(@ApplicationContext context: Context): UploadDatabase =
        Room.databaseBuilder(context, UploadDatabase::class.java, "es_upload_queue_db")
            .addMigrations(UploadDatabase.MIGRATION_1_2)
            .build()

    @Provides
    @Singleton
    fun provideUploadDao(database: UploadDatabase): UploadDao = database.uploadDao()
}
