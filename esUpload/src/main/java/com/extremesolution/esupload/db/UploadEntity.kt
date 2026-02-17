package com.extremesolution.esupload.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.extremesolution.esupload.model.UploadStatus

@Entity(tableName = "upload_queue")
data class UploadEntity(
    @PrimaryKey
    val uploadId: String,
    val filePath: String,
    val url: String,
    val headersJson: String = "{}",
    val paramsJson: String = "{}",
    val fileParamName: String = "file",
    val maxRetries: Int = 5,
    val notificationTitle: String = "Uploading file",
    val notificationFileName: String? = null,
    val attemptCount: Int = 0,
    val status: UploadStatus = UploadStatus.QUEUED,
    val progress: Int = 0,
    val responseCode: Int? = null,
    val responseBody: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
