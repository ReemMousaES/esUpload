package com.extremesolution.esupload.worker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.extremesolution.esupload.db.UploadDao
import com.extremesolution.esupload.db.UploadDatabase
import com.extremesolution.esupload.db.UploadEntity
import com.extremesolution.esupload.model.UploadStatus
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FileUploadWorkerTest {

    private lateinit var context: Context
    private lateinit var db: UploadDatabase
    private lateinit var dao: UploadDao
    private lateinit var server: MockWebServer
    private lateinit var file: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, UploadDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.uploadDao()
        server = MockWebServer().apply { start() }
        file = File(context.cacheDir, "photo.jpg").apply { writeText("fake-image-bytes") }
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
        file.delete()
    }

    private fun entity(
        id: String = "u1",
        url: String = server.url("/upload").toString(),
        filePath: String = file.absolutePath,
        maxRetries: Int = 3,
        attemptCount: Int = 0,
        status: UploadStatus = UploadStatus.QUEUED
    ) = UploadEntity(
        uploadId = id,
        filePath = filePath,
        url = url,
        headersJson = """{"Authorization":"Bearer token"}""",
        paramsJson = """{"eventId":"evt_1"}""",
        fileParamName = "photo",
        maxRetries = maxRetries,
        attemptCount = attemptCount,
        status = status
    )

    private fun buildWorker(uploadId: String? = "u1"): FileUploadWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker = FileUploadWorker(appContext, workerParameters, dao)
        }
        val input = if (uploadId != null) workDataOf(FileUploadWorker.KEY_UPLOAD_ID to uploadId) else workDataOf()
        return TestListenableWorkerBuilder<FileUploadWorker>(context)
            .setInputData(input)
            .setWorkerFactory(factory)
            .build()
    }

    @Test
    fun `successful upload marks the row UPLOADED and returns the response`() = runTest {
        dao.insert(entity())
        server.enqueue(MockResponse(code = 200, body = "ok"))

        val result = buildWorker().doWork()

        assertTrue(result is Result.Success)
        assertEquals(200, result.outputData.getInt(FileUploadWorker.KEY_RESPONSE_CODE, -1))
        assertEquals("ok", result.outputData.getString(FileUploadWorker.KEY_RESPONSE_BODY))

        val row = dao.getById("u1")!!
        assertEquals(UploadStatus.UPLOADED, row.status)
        assertEquals(100, row.progress)
        assertEquals(200, row.responseCode)
        assertEquals("ok", row.responseBody)
    }

    @Test
    fun `sends a multipart POST with headers, params and the file`() = runTest {
        dao.insert(entity())
        server.enqueue(MockResponse(code = 200, body = "ok"))

        buildWorker().doWork()

        val request = server.takeRequest()
        val body = request.body!!.utf8()
        assertEquals("POST", request.method)
        assertEquals("/upload", request.url.encodedPath)
        assertEquals("Bearer token", request.headers["Authorization"])
        assertTrue(request.headers["Content-Type"]!!.startsWith("multipart/form-data"))
        assertTrue(body.contains("name=\"eventId\""))
        assertTrue(body.contains("evt_1"))
        assertTrue(body.contains("name=\"photo\"; filename=\"photo.jpg\""))
        assertTrue(body.contains("fake-image-bytes"))
    }

    @Test
    fun `server error with retries left returns retry and re-queues the row`() = runTest {
        dao.insert(entity(maxRetries = 3, attemptCount = 0))
        server.enqueue(MockResponse(code = 500, body = "server down"))

        val result = buildWorker().doWork()

        assertTrue(result is Result.Retry)
        val row = dao.getById("u1")!!
        assertEquals(UploadStatus.QUEUED, row.status)
        assertEquals(1, row.attemptCount)
        assertEquals(500, row.responseCode)
        assertEquals("server down", row.errorMessage)
    }

    @Test
    fun `server error on the last attempt fails and marks the row FAILED`() = runTest {
        dao.insert(entity(maxRetries = 3, attemptCount = 2))
        server.enqueue(MockResponse(code = 422, body = "bad request"))

        val result = buildWorker().doWork()

        assertTrue(result is Result.Failure)
        assertEquals(422, result.outputData.getInt(FileUploadWorker.KEY_RESPONSE_CODE, 0))
        assertEquals("bad request", result.outputData.getString(FileUploadWorker.KEY_ERROR_MESSAGE))
        val row = dao.getById("u1")!!
        assertEquals(UploadStatus.FAILED, row.status)
        assertEquals(3, row.attemptCount)
    }

    @Test
    fun `network error returns retry with no response code`() = runTest {
        val unreachableUrl = server.url("/upload").toString()
        server.close()
        dao.insert(entity(url = unreachableUrl))

        val result = buildWorker().doWork()

        assertTrue(result is Result.Retry)
        val row = dao.getById("u1")!!
        assertEquals(UploadStatus.QUEUED, row.status)
        assertNull(row.responseCode)
        assertTrue(row.errorMessage!!.isNotEmpty())
    }

    @Test
    fun `missing file fails without calling the server`() = runTest {
        dao.insert(entity(filePath = "/does/not/exist.jpg"))

        val result = buildWorker().doWork()

        assertTrue(result is Result.Failure)
        assertEquals(0, server.requestCount)
        val row = dao.getById("u1")!!
        assertEquals(UploadStatus.FAILED, row.status)
        assertTrue(row.errorMessage!!.startsWith("File not found"))
    }

    @Test
    fun `already uploaded row succeeds without calling the server`() = runTest {
        dao.insert(entity(status = UploadStatus.UPLOADED))

        val result = buildWorker().doWork()

        assertTrue(result is Result.Success)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `unknown upload id fails`() = runTest {
        val result = buildWorker(uploadId = "missing").doWork()

        assertTrue(result is Result.Failure)
        assertEquals("Upload entity not found", result.outputData.getString(FileUploadWorker.KEY_ERROR_MESSAGE))
    }

    @Test
    fun `missing upload id input fails`() = runTest {
        val result = buildWorker(uploadId = null).doWork()

        assertTrue(result is Result.Failure)
        assertEquals(0, server.requestCount)
    }
}
