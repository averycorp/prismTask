package com.averycorp.prismtask.util

import android.util.Log
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.math.min

private const val TAG = "RetryUtils"

suspend fun <T> withRetry(
    maxAttempts: Int = 3,
    initialDelayMs: Long = 500L,
    maxDelayMs: Long = 10_000L,
    factor: Double = 2.0,
    shouldRetry: (Exception) -> Boolean = ::isRetryableException,
    block: suspend () -> T
): T {
    var currentDelay = initialDelayMs
    repeat(maxAttempts - 1) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            if (!shouldRetry(e)) throw e
            Log.w(TAG, "Attempt ${attempt + 1} failed, retrying in ${currentDelay}ms", e)
            delay(currentDelay + (Math.random() * 200).toLong())
            currentDelay = min((currentDelay * factor).toLong(), maxDelayMs)
        }
    }
    return block()
}

fun isRetryableException(e: Exception): Boolean = when (e) {
    is IOException -> true
    is com.google.firebase.firestore.FirebaseFirestoreException -> when (e.code) {
        com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE,
        com.google.firebase.firestore.FirebaseFirestoreException.Code.ABORTED,
        com.google.firebase.firestore.FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
        com.google.firebase.firestore.FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED -> true
        else -> false
    }
    else -> false
}
