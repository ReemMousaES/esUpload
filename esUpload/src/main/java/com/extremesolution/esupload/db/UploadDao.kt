package com.extremesolution.esupload.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.extremesolution.esupload.model.UploadStatus

@Dao
interface UploadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: UploadEntity)

    @Query("SELECT * FROM upload_queue WHERE uploadId = :uploadId")
    suspend fun getById(uploadId: String): UploadEntity?

    @Query("SELECT * FROM upload_queue ORDER BY createdAt ASC")
    fun observeAll(): LiveData<List<UploadEntity>>

    @Query("SELECT * FROM upload_queue WHERE status = :status ORDER BY createdAt ASC")
    fun observeByStatus(status: UploadStatus): LiveData<List<UploadEntity>>

    @Query("SELECT * FROM upload_queue WHERE status IN (:statuses) ORDER BY createdAt ASC")
    fun observeByStatuses(statuses: List<UploadStatus>): LiveData<List<UploadEntity>>

    @Query("UPDATE upload_queue SET status = :status, updatedAt = :now WHERE uploadId = :uploadId")
    suspend fun updateStatus(uploadId: String, status: UploadStatus, now: Long = System.currentTimeMillis())

    @Query("UPDATE upload_queue SET progress = :progress, updatedAt = :now WHERE uploadId = :uploadId")
    suspend fun updateProgress(uploadId: String, progress: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE upload_queue SET status = :status, responseCode = :code, responseBody = :body, updatedAt = :now WHERE uploadId = :uploadId")
    suspend fun markSuccess(uploadId: String, status: UploadStatus = UploadStatus.UPLOADED, code: Int, body: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE upload_queue SET status = :status, attemptCount = attemptCount + 1, errorMessage = :error, responseCode = :code, updatedAt = :now WHERE uploadId = :uploadId")
    suspend fun markFailed(uploadId: String, status: UploadStatus = UploadStatus.FAILED, code: Int? = null, error: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE upload_queue SET status = :newStatus, updatedAt = :now WHERE status = :oldStatus")
    suspend fun resetStaleStatuses(oldStatus: UploadStatus = UploadStatus.UPLOADING, newStatus: UploadStatus = UploadStatus.FAILED, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM upload_queue WHERE uploadId = :uploadId")
    suspend fun delete(uploadId: String)

    @Query("DELETE FROM upload_queue WHERE status = :status")
    suspend fun deleteByStatus(status: UploadStatus): Int

    @Query("SELECT COUNT(*) FROM upload_queue WHERE status = :status")
    fun countByStatus(status: UploadStatus): LiveData<Int>
}
