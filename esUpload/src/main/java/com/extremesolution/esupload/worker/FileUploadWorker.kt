package com.extremesolution.esupload.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.extremesolution.esupload.db.UploadDao
import com.extremesolution.esupload.model.UploadStatus
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

@HiltWorker
class FileUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val uploadDao: UploadDao
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "FileUploadWorker"
        const val KEY_UPLOAD_ID = "uploadId"
        const val KEY_RESPONSE_CODE = "responseCode"
        const val KEY_RESPONSE_BODY = "responseBody"
        const val KEY_ERROR_MESSAGE = "errorMessage"
        const val KEY_PROGRESS = "progress"

        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "esupload_channel"

        private val uploadMutex = Mutex()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    override suspend fun doWork(): Result = uploadMutex.withLock {
        doWorkInternal()
    }

    private suspend fun doWorkInternal(): Result {
        val uploadId = inputData.getString(KEY_UPLOAD_ID) ?: return Result.failure()

        val entity = uploadDao.getById(uploadId)
        if (entity == null) {
            Log.e(TAG, "Upload entity not found for id=$uploadId")
            return Result.failure(workDataOf(KEY_UPLOAD_ID to uploadId, KEY_ERROR_MESSAGE to "Upload entity not found"))
        }

        if (entity.status == UploadStatus.UPLOADED) {
            Log.d(TAG, "Upload $uploadId already completed, skipping")
            return Result.success(workDataOf(KEY_UPLOAD_ID to uploadId))
        }

        val file = File(entity.filePath)
        if (!file.exists()) {
            Log.e(TAG, "File not found: ${entity.filePath}")
            uploadDao.markFailed(uploadId, code = null, error = "File not found: ${entity.filePath}")
            return Result.failure(workDataOf(KEY_UPLOAD_ID to uploadId, KEY_ERROR_MESSAGE to "File not found"))
        }

        uploadDao.updateStatus(uploadId, UploadStatus.UPLOADING)
        setProgress(workDataOf(KEY_UPLOAD_ID to uploadId, KEY_PROGRESS to 0))
        val displayFileName = entity.notificationFileName ?: file.name
        try {
            setForeground(createForegroundInfo(uploadId, entity.notificationTitle, displayFileName, 0))
        } catch (e: Exception) {
            Log.w(TAG, "Could not set foreground (app may be backgrounded): ${e.message}")
        }

        return try {
            val headers: Map<String, String> = gson.fromJson(
                entity.headersJson,
                object : TypeToken<Map<String, String>>() {}.type
            ) ?: emptyMap()

            val params: Map<String, String> = gson.fromJson(
                entity.paramsJson,
                object : TypeToken<Map<String, String>>() {}.type
            ) ?: emptyMap()

            val multipartBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)

            params.forEach { (key, value) ->
                multipartBuilder.addFormDataPart(key, value)
            }

            val mediaType = "application/octet-stream".toMediaType()
            val fileBody = file.asRequestBody(mediaType)
            val progressBody = ProgressRequestBody(fileBody) { percent ->
                uploadDao.updateProgress(uploadId, percent)
                setProgress(workDataOf(KEY_UPLOAD_ID to uploadId, KEY_PROGRESS to percent))
                try {
                    setForeground(createForegroundInfo(uploadId, entity.notificationTitle, displayFileName, percent))
                } catch (e: Exception) {
                    // Silently ignore - notification will show when app comes to foreground
                }
            }
            multipartBuilder.addFormDataPart(
                entity.fileParamName,
                file.name,
                progressBody
            )

            val requestBuilder = Request.Builder()
                .url(entity.url)
                .post(multipartBuilder.build())

            headers.forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
            }

            Log.d(TAG, "Uploading $uploadId to ${entity.url}")

            val response = client.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string() ?: ""
            val responseCode = response.code

            Log.d(TAG, "Upload $uploadId response: code=$responseCode, body=$responseBody")

            if (response.isSuccessful) {
                uploadDao.markSuccess(uploadId, code = responseCode, body = responseBody)
                uploadDao.updateProgress(uploadId, 100)
                setProgress(workDataOf(KEY_UPLOAD_ID to uploadId, KEY_PROGRESS to 100))

                Result.success(
                    workDataOf(
                        KEY_UPLOAD_ID to uploadId,
                        KEY_RESPONSE_CODE to responseCode,
                        KEY_RESPONSE_BODY to responseBody
                    )
                )
            } else {
                Log.e(TAG, "Upload $uploadId failed: code=$responseCode, body=$responseBody")
                handleFailure(uploadId, entity.maxRetries, responseCode, responseBody)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload $uploadId exception: ${e.message}", e)
            handleFailure(uploadId, entity.maxRetries, null, e.message ?: "Unknown error")
        }
    }

    private suspend fun handleFailure(
        uploadId: String,
        maxRetries: Int,
        responseCode: Int?,
        errorMessage: String
    ): Result {
        val entity = uploadDao.getById(uploadId)
        val currentAttempt = (entity?.attemptCount ?: 0) + 1

        return if (currentAttempt < maxRetries) {
            uploadDao.markFailed(uploadId, status = UploadStatus.QUEUED, code = responseCode, error = errorMessage)
            Log.d(TAG, "Upload $uploadId will retry (attempt $currentAttempt/$maxRetries)")
            Result.retry()
        } else {
            uploadDao.markFailed(uploadId, code = responseCode, error = errorMessage)
            Log.e(TAG, "Upload $uploadId exhausted retries ($maxRetries)")
            Result.failure(
                workDataOf(
                    KEY_UPLOAD_ID to uploadId,
                    KEY_RESPONSE_CODE to (responseCode ?: -1),
                    KEY_ERROR_MESSAGE to errorMessage
                )
            )
        }
    }

    private fun createForegroundInfo(
        uploadId: String,
        title: String,
        fileName: String,
        progress: Int
    ): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Uploads",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = applicationContext.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText("$fileName • $progress%")
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setSilent(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
