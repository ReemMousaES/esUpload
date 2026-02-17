package com.extremesolution.esupload

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.extremesolution.esupload.db.UploadDao
import com.extremesolution.esupload.db.UploadEntity
import com.extremesolution.esupload.model.UploadRequest
import com.extremesolution.esupload.model.UploadStatus
import com.extremesolution.esupload.worker.FileUploadWorker
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public API for the esUpload library.
 *
 * All uploads are executed **strictly one at a time** via a Mutex inside the worker.
 * Each upload is enqueued as unique work keyed by its uploadId.
 *
 * Usage:
 * ```
 * esUploadManager.enqueue(UploadRequest(
 *     uploadId = "photo_123",
 *     filePath = "/storage/.../photo.jpg",
 *     url = "https://api.example.com/upload",
 *     headers = mapOf("Authorization" to "Bearer token"),
 *     params = mapOf("eventId" to "evt_1", "overlayId" to "ovl_2"),
 *     fileParamName = "photo"
 * ))
 * ```
 */
@Singleton
class EsUploadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploadDao: UploadDao
) {

    private val workManager: WorkManager
        get() = WorkManager.getInstance(context)

    companion object {
        private const val TAG = "EsUploadManager"
        const val TAG_ALL_UPLOADS = "all_uploads"
    }

    private val gson = Gson()

    /**
     * Enqueue a single upload. It will be executed sequentially
     * after all previously enqueued uploads complete.
     */
    suspend fun enqueue(request: UploadRequest) {
        val entity = UploadEntity(
            uploadId = request.uploadId,
            filePath = request.filePath,
            url = request.url,
            headersJson = gson.toJson(request.headers),
            paramsJson = gson.toJson(request.params),
            fileParamName = request.fileParamName,
            maxRetries = request.maxRetries,
            notificationTitle = request.notificationTitle,
            notificationFileName = request.notificationFileName,
            status = UploadStatus.QUEUED
        )
        uploadDao.insert(entity)

        val workRequest = OneTimeWorkRequestBuilder<FileUploadWorker>()
            .setInputData(workDataOf(FileUploadWorker.KEY_UPLOAD_ID to request.uploadId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(10))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG_ALL_UPLOADS)
            .addTag("upload_${request.uploadId}")
            .build()

        workManager.enqueueUniqueWork(
            "upload_${request.uploadId}",
            ExistingWorkPolicy.KEEP,
            workRequest
        )

        Log.d(TAG, "Enqueued upload: ${request.uploadId}")
    }

    /**
     * Enqueue multiple uploads. They will all be executed one by one in order.
     */
    suspend fun enqueueAll(requests: List<UploadRequest>) {
        requests.forEach { enqueue(it) }
    }

    /**
     * Retry a previously failed upload by re-enqueuing it.
     */
    suspend fun retry(uploadId: String) {
        val entity = uploadDao.getById(uploadId) ?: run {
            Log.e(TAG, "Cannot retry: upload $uploadId not found")
            return
        }

        uploadDao.updateStatus(uploadId, UploadStatus.QUEUED)

        val workRequest = OneTimeWorkRequestBuilder<FileUploadWorker>()
            .setInputData(workDataOf(FileUploadWorker.KEY_UPLOAD_ID to uploadId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(10))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG_ALL_UPLOADS)
            .addTag("upload_$uploadId")
            .build()

        workManager.enqueueUniqueWork(
            "upload_$uploadId",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        Log.d(TAG, "Retrying upload: $uploadId")
    }

    /**
     * Cancel all pending and running uploads.
     */
    fun cancelAll() {
        workManager.cancelAllWorkByTag(TAG_ALL_UPLOADS)
        Log.d(TAG, "Cancelled all uploads")
    }

    /**
     * Delete a specific upload from the queue.
     */
    suspend fun delete(uploadId: String) {
        uploadDao.delete(uploadId)
        Log.d(TAG, "Deleted upload: $uploadId")
    }

    /**
     * Delete all failed uploads.
     */
    suspend fun deleteAllFailed(): Int {
        return uploadDao.deleteByStatus(UploadStatus.FAILED)
    }

    /**
     * Reset any uploads stuck in UPLOADING state (e.g. after process death)
     * back to FAILED so the user can retry them.
     */
    suspend fun resetStaleUploads() {
        uploadDao.resetStaleStatuses()
    }

    // --- Observation ---

    /**
     * Observe all uploads in the queue.
     */
    fun observeAll(): LiveData<List<UploadEntity>> = uploadDao.observeAll()

    /**
     * Observe uploads by status.
     */
    fun observeByStatus(status: UploadStatus): LiveData<List<UploadEntity>> =
        uploadDao.observeByStatus(status)

    /**
     * Observe uploads that are not yet uploaded (queued, uploading, or failed).
     */
    fun observePending(): LiveData<List<UploadEntity>> =
        uploadDao.observeByStatuses(listOf(UploadStatus.QUEUED, UploadStatus.UPLOADING, UploadStatus.FAILED))

    /**
     * Observe the count of uploads by status.
     */
    fun observeCountByStatus(status: UploadStatus): LiveData<Int> =
        uploadDao.countByStatus(status)

    /**
     * Observe WorkManager work info for all uploads.
     * Useful for tracking real-time progress of the currently running upload.
     */
    fun observeWorkInfo(): LiveData<List<WorkInfo>> =
        workManager.getWorkInfosByTagLiveData(TAG_ALL_UPLOADS)
}
