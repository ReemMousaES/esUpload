package com.extremesolution.esupload.worker

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.Sink
import okio.buffer

class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: suspend (percent: Int) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun writeTo(sink: BufferedSink) {
        val totalBytes = contentLength()
        val countingSink = CountingSink(sink, totalBytes, onProgress)
        val bufferedSink = countingSink.buffer()
        delegate.writeTo(bufferedSink)
        bufferedSink.flush()
    }

    private class CountingSink(
        delegate: Sink,
        private val totalBytes: Long,
        private val onProgress: suspend (percent: Int) -> Unit
    ) : ForwardingSink(delegate) {

        private var bytesWritten = 0L
        private var lastReportedPercent = -1

        override fun write(source: Buffer, byteCount: Long) {
            super.write(source, byteCount)
            bytesWritten += byteCount
            if (totalBytes > 0) {
                val percent = ((bytesWritten * 100) / totalBytes).toInt().coerceAtMost(100)
                val bucket = percent / 5
                val lastBucket = lastReportedPercent / 5
                if (bucket != lastBucket || percent == 100) {
                    lastReportedPercent = percent
                    kotlinx.coroutines.runBlocking {
                        onProgress(percent)
                    }
                }
            }
        }
    }
}
