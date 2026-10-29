package com.extremesolution.esupload.worker

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressRequestBodyTest {

    private val mediaType = "application/octet-stream".toMediaType()

    @Test
    fun `delegates content type and length`() {
        val delegate = ByteArray(1_234).toRequestBody(mediaType)
        val body = ProgressRequestBody(delegate) {}

        assertEquals(mediaType, body.contentType())
        assertEquals(1_234L, body.contentLength())
    }

    @Test
    fun `writes the delegate bytes unchanged`() {
        val bytes = ByteArray(50_000) { (it % 256).toByte() }
        val body = ProgressRequestBody(bytes.toRequestBody(mediaType)) {}

        val sink = Buffer()
        body.writeTo(sink)

        assertArrayEquals(bytes, sink.readByteArray())
    }

    @Test
    fun `reports progress once per 10 percent bucket and ends at 100`() {
        val reported = mutableListOf<Int>()
        val body = ProgressRequestBody(ByteArray(500_000).toRequestBody(mediaType)) { reported += it }

        body.writeTo(Buffer())

        assertEquals(100, reported.last())
        assertEquals("progress must only increase", reported.sorted(), reported)
        val buckets = reported.dropLast(1).map { it / 10 }
        assertEquals("one report per bucket", buckets.distinct(), buckets)
        assertTrue("at most 11 reports (0..100 by tens), got $reported", reported.size <= 11)
    }

    @Test
    fun `reports nothing for an empty body`() {
        val reported = mutableListOf<Int>()
        val body = ProgressRequestBody(ByteArray(0).toRequestBody(mediaType)) { reported += it }

        body.writeTo(Buffer())

        assertTrue(reported.isEmpty())
    }
}
