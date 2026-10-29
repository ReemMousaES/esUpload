package com.extremesolution.esupload.db

import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Blocks until the LiveData emits a value, or fails after [timeoutSeconds]. */
fun <T> LiveData<T>.awaitValue(timeoutSeconds: Long = 2): T {
    var result: T? = null
    val latch = CountDownLatch(1)
    val observer = object : Observer<T> {
        override fun onChanged(value: T) {
            result = value
            latch.countDown()
            removeObserver(this)
        }
    }
    observeForever(observer)
    if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
        removeObserver(observer)
        throw TimeoutException("LiveData never emitted a value")
    }
    @Suppress("UNCHECKED_CAST")
    return result as T
}
