package com.averycorp.prismtask.data.remote

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Token-bucket rate limiter for AI/backend API calls.
 * Prevents quota exhaustion when processing batches.
 */
class RateLimiter(
    private val maxRequestsPerMinute: Int = 20,
    private val maxConcurrent: Int = 3
) {
    private val minuteWindowStart = AtomicLong(0L)
    private val requestCount = AtomicInteger(0)
    private val activeRequests = AtomicInteger(0)

    /**
     * Returns true if the request can proceed, false if rate limited.
     */
    fun tryAcquire(): Boolean {
        val now = System.currentTimeMillis()
        val windowStart = minuteWindowStart.get()

        // Reset counter if we're in a new minute window
        if (now - windowStart > 60_000L) {
            minuteWindowStart.compareAndSet(windowStart, now)
            requestCount.set(0)
        }

        if (activeRequests.get() >= maxConcurrent) {
            Log.w("RateLimiter", "Max concurrent requests ($maxConcurrent) reached")
            return false
        }

        val count = requestCount.incrementAndGet()
        return if (count > maxRequestsPerMinute) {
            requestCount.decrementAndGet()
            Log.w("RateLimiter", "Rate limit reached ($maxRequestsPerMinute req/min)")
            false
        } else {
            activeRequests.incrementAndGet()
            true
        }
    }

    fun release() {
        activeRequests.decrementAndGet()
    }
}

/** Global rate limiters per endpoint key. */
object ApiRateLimiters {
    private val limiters = ConcurrentHashMap<String, RateLimiter>()

    fun forEndpoint(key: String, maxPerMinute: Int = 20): RateLimiter =
        limiters.getOrPut(key) { RateLimiter(maxRequestsPerMinute = maxPerMinute) }
}
