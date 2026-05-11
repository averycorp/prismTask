package com.averycorp.prismtask.domain.model

import java.time.LocalDate

/**
 * Per-day total of logged minutes within an analytics window. Bars in the
 * `TimeTrackingSection` chart map 1:1 to one of these.
 */
data class DailyTimeBucket(val date: LocalDate, val totalMinutes: Int)

/**
 * Aggregated logged-time response for the analytics time-tracking chart.
 * Mirrors the shape `ProductivityScoreResponse` uses so the wiring in
 * `TaskAnalyticsViewModel` stays symmetric.
 */
data class TimeTrackingResponse(
    val buckets: List<DailyTimeBucket>,
    val totalMinutes: Int,
    val averageMinutesPerActiveDay: Int,
    val activeDayCount: Int,
    val range: ProductivityRange
)
