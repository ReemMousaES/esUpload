package com.extremesolution.esupload.model

import com.google.gson.annotations.SerializedName

/**
 * Represents a single file upload request with its metadata.
 * This is the primary input to [com.extremesolution.esupload.EsUploadManager.enqueue].
 *
 * @param uploadId Unique identifier for this upload (used for tracking & deduplication).
 * @param filePath Absolute path to the file on disk.
 * @param url The server endpoint URL.
 * @param headers HTTP headers (e.g. Authorization).
 * @param params Multipart form parameters sent alongside the file.
 * @param fileParamName The multipart parameter name for the file (default "file").
 * @param maxRetries Maximum number of retry attempts before marking as failed.
 */
data class UploadRequest(
    @SerializedName("uploadId") val uploadId: String,
    @SerializedName("filePath") val filePath: String,
    @SerializedName("url") val url: String,
    @SerializedName("headers") val headers: Map<String, String> = emptyMap(),
    @SerializedName("params") val params: Map<String, String> = emptyMap(),
    @SerializedName("fileParamName") val fileParamName: String = "file",
    @SerializedName("maxRetries") val maxRetries: Int = 5
)
