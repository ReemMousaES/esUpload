package com.extremesolution.esupload

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.extremesolution.esupload.db.UploadDao
import com.extremesolution.esupload.db.UploadDatabase
import com.extremesolution.esupload.db.UploadEntity
import com.extremesolution.esupload.model.UploadRequest
import com.extremesolution.esupload.model.UploadStatus
import com.extremesolution.esupload.worker.FileUploadWorker
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The network constraint is never met in the test WorkManager, so enqueued
 * work stays ENQUEUED and these tests check only what the manager schedules.
 */
@RunWith(AndroidJUnit4::class)
class EsUploadManagerTest {

    private lateinit var context: Context
    private lateinit var db: UploadDatabase
    private lateinit var dao: UploadDao
    private lateinit var workManager: WorkManager
    private lateinit var manager: EsUploadManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
        workManager = WorkManager.getInstance(context)
        db = Room.inMemoryDatabaseBuilder(context, UploadDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.uploadDao()
        manager = EsUploadManager(context, dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun request(id: String) = UploadRequest(
        uploadId = id,
        filePath = "/storage/$id.jpg",
        url = "https://example.com/upload",
        headers = mapOf("Authorization" to "Bearer t"),
        params = mapOf("eventId" to "evt_1"),
        fileParamName = "photo",
        maxRetries = 4,
        notificationTitle = "Uploading photo",
        notificationFileName = "IMG_1.jpg"
    )

    private fun uniqueWork(id: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork("upload_$id").get()

    @Test
    fun `enqueue stores the request as a QUEUED row`() = runTest {
        manager.enqueue(request("a"))

        val row = dao.getById("a")!!
        assertEquals(UploadStatus.QUEUED, row.status)
        assertEquals("/storage/a.jpg", row.filePath)
        assertEquals("""{"Authorization":"Bearer t"}""", row.headersJson)
        assertEquals("""{"eventId":"evt_1"}""", row.paramsJson)
        assertEquals("photo", row.fileParamName)
        assertEquals(4, row.maxRetries)
        assertEquals("Uploading photo", row.notificationTitle)
        assertEquals("IMG_1.jpg", row.notificationFileName)
    }

    @Test
    fun `enqueue schedules unique tagged work that needs a network`() = runTest {
        manager.enqueue(request("a"))

        val work = uniqueWork("a").single()
        assertEquals(WorkInfo.State.ENQUEUED, work.state)
        assertTrue(work.tags.contains(EsUploadManager.TAG_ALL_UPLOADS))
        assertTrue(work.tags.contains("upload_a"))
        assertTrue(work.tags.contains(FileUploadWorker::class.java.name))
        assertEquals(androidx.work.NetworkType.CONNECTED, work.constraints.requiredNetworkType)
    }

    @Test
    fun `enqueue keeps existing work for the same id`() = runTest {
        manager.enqueue(request("a"))
        val firstId = uniqueWork("a").single().id

        manager.enqueue(request("a"))

        assertEquals(firstId, uniqueWork("a").single().id)
    }

    @Test
    fun `enqueueAll schedules work for every request`() = runTest {
        manager.enqueueAll(listOf(request("a"), request("b"), request("c")))

        val all = workManager.getWorkInfosByTag(EsUploadManager.TAG_ALL_UPLOADS).get()
        assertEquals(3, all.size)
        listOf("a", "b", "c").forEach { assertEquals(UploadStatus.QUEUED, dao.getById(it)?.status) }
    }

    @Test
    fun `retry re-queues the row and replaces the work`() = runTest {
        manager.enqueue(request("a"))
        val firstId = uniqueWork("a").single().id
        dao.markFailed("a", code = 500, error = "boom")

        manager.retry("a")

        assertEquals(UploadStatus.QUEUED, dao.getById("a")?.status)
        val active = uniqueWork("a").filter { it.state == WorkInfo.State.ENQUEUED }
        assertNotEquals(firstId, active.single().id)
    }

    @Test
    fun `retry of an unknown id schedules nothing`() = runTest {
        manager.retry("missing")

        assertTrue(uniqueWork("missing").isEmpty())
    }

    @Test
    fun `cancelAll cancels every upload`() = runTest {
        manager.enqueueAll(listOf(request("a"), request("b")))

        manager.cancelAll()

        val all = workManager.getWorkInfosByTag(EsUploadManager.TAG_ALL_UPLOADS).get()
        assertTrue(all.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun `deleteAllFailed removes only failed rows`() = runTest {
        dao.insert(UploadEntity(uploadId = "f", filePath = "/f", url = "u", status = UploadStatus.FAILED))
        dao.insert(UploadEntity(uploadId = "q", filePath = "/q", url = "u", status = UploadStatus.QUEUED))

        assertEquals(1, manager.deleteAllFailed())
        assertEquals(UploadStatus.QUEUED, dao.getById("q")?.status)
    }

    @Test
    fun `resetStaleUploads marks interrupted uploads as FAILED`() = runTest {
        dao.insert(UploadEntity(uploadId = "s", filePath = "/s", url = "u", status = UploadStatus.UPLOADING))

        manager.resetStaleUploads()

        assertEquals(UploadStatus.FAILED, dao.getById("s")?.status)
    }
}
