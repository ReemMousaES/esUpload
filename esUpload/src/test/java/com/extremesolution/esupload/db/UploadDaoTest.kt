package com.extremesolution.esupload.db

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.extremesolution.esupload.model.UploadStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UploadDaoTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var db: UploadDatabase
    private lateinit var dao: UploadDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, UploadDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.uploadDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun entity(id: String, status: UploadStatus = UploadStatus.QUEUED, createdAt: Long = 0L) =
        UploadEntity(uploadId = id, filePath = "/tmp/$id.jpg", url = "https://example.com", status = status, createdAt = createdAt)

    @Test
    fun `insert then getById returns the entity`() = runTest {
        val item = entity("a")
        dao.insert(item)

        assertEquals(item, dao.getById("a"))
        assertNull(dao.getById("missing"))
    }

    @Test
    fun `insert with an existing id replaces the row`() = runTest {
        dao.insert(entity("a"))
        dao.insert(entity("a").copy(url = "https://new.example.com"))

        assertEquals("https://new.example.com", dao.getById("a")?.url)
    }

    @Test
    fun `updateStatus and updateProgress change only that row`() = runTest {
        dao.insert(entity("a"))
        dao.insert(entity("b"))

        dao.updateStatus("a", UploadStatus.UPLOADING)
        dao.updateProgress("a", 40)

        val a = dao.getById("a")!!
        assertEquals(UploadStatus.UPLOADING, a.status)
        assertEquals(40, a.progress)
        assertEquals(UploadStatus.QUEUED, dao.getById("b")?.status)
    }

    @Test
    fun `markSuccess stores status, code and body`() = runTest {
        dao.insert(entity("a", UploadStatus.UPLOADING))

        dao.markSuccess("a", code = 201, body = "{\"ok\":true}")

        val a = dao.getById("a")!!
        assertEquals(UploadStatus.UPLOADED, a.status)
        assertEquals(201, a.responseCode)
        assertEquals("{\"ok\":true}", a.responseBody)
    }

    @Test
    fun `markFailed increments attemptCount each time`() = runTest {
        dao.insert(entity("a", UploadStatus.UPLOADING))

        dao.markFailed("a", status = UploadStatus.QUEUED, code = 500, error = "boom")
        dao.markFailed("a", code = null, error = "timeout")

        val a = dao.getById("a")!!
        assertEquals(2, a.attemptCount)
        assertEquals(UploadStatus.FAILED, a.status)
        assertNull(a.responseCode)
        assertEquals("timeout", a.errorMessage)
    }

    @Test
    fun `resetStaleStatuses moves only UPLOADING rows to FAILED`() = runTest {
        dao.insert(entity("stuck", UploadStatus.UPLOADING))
        dao.insert(entity("queued", UploadStatus.QUEUED))
        dao.insert(entity("done", UploadStatus.UPLOADED))

        dao.resetStaleStatuses()

        assertEquals(UploadStatus.FAILED, dao.getById("stuck")?.status)
        assertEquals(UploadStatus.QUEUED, dao.getById("queued")?.status)
        assertEquals(UploadStatus.UPLOADED, dao.getById("done")?.status)
    }

    @Test
    fun `deleteByStatus removes matching rows and returns the count`() = runTest {
        dao.insert(entity("f1", UploadStatus.FAILED))
        dao.insert(entity("f2", UploadStatus.FAILED))
        dao.insert(entity("q", UploadStatus.QUEUED))

        assertEquals(2, dao.deleteByStatus(UploadStatus.FAILED))
        assertNull(dao.getById("f1"))
        assertEquals(UploadStatus.QUEUED, dao.getById("q")?.status)
    }

    @Test
    fun `delete removes a single row`() = runTest {
        dao.insert(entity("a"))
        dao.insert(entity("b"))

        dao.delete("a")

        assertNull(dao.getById("a"))
        assertEquals("b", dao.getById("b")?.uploadId)
    }

    @Test
    fun `observeByStatuses returns matching rows ordered by createdAt`() = runTest {
        dao.insert(entity("late", UploadStatus.FAILED, createdAt = 300))
        dao.insert(entity("early", UploadStatus.QUEUED, createdAt = 100))
        dao.insert(entity("done", UploadStatus.UPLOADED, createdAt = 200))

        val pending = dao.observeByStatuses(listOf(UploadStatus.QUEUED, UploadStatus.FAILED)).awaitValue()

        assertEquals(listOf("early", "late"), pending.map { it.uploadId })
    }

    @Test
    fun `countByStatus counts rows with that status`() = runTest {
        dao.insert(entity("a", UploadStatus.FAILED))
        dao.insert(entity("b", UploadStatus.FAILED))
        dao.insert(entity("c", UploadStatus.QUEUED))

        assertEquals(2, dao.countByStatus(UploadStatus.FAILED).awaitValue())
    }
}
