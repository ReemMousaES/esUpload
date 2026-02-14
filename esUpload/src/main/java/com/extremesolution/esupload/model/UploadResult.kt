package com.extremesolution.esupload.model

sealed class UploadResult {
    data class Success(
        val uploadId: String,
        val responseCode: Int,
        val responseBody: String
    ) : UploadResult()

    data class Failure(
        val uploadId: String,
        val responseCode: Int?,
        val errorMessage: String
    ) : UploadResult()

    data class Progress(
        val uploadId: String,
        val percent: Int
    ) : UploadResult()
}
