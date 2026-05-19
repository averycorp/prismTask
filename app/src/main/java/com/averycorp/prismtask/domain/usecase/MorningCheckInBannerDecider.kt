package com.averycorp.prismtask.domain.usecase

/**
 * Pure-function decider for the Today-screen morning check-in banner.
 *
 * The visible window is `[todayStart, todayStart + windowHours)` —
 * the prompt is available for exactly [windowHours] after the user's
 * Start-of-Day and disappears once that span has elapsed.
 */
object MorningCheckInBannerDecider {
    /**
     * Returns true when the morning check-in banner should be visible.
     *
     * @param now wall-clock now in epoch millis
     * @param todayStart SoD-anchored start of the user's logical today,
     *   in epoch millis (compute via [com.averycorp.prismtask.util.DayBoundary]
     *   or `logicalDate.atTime(sod.hour, sod.minute)`)
     * @param windowHours length of the availability window in hours
     *   after SoD (1..24) from
     *   `AdvancedTuningPreferences.getMorningCheckInPromptCutoff()`
     * @param featureEnabled `MorningCheckInPreferences.featureEnabled()`
     * @param alreadyCheckedInToday true if a CheckInLog row already
     *   exists with `date >= todayStart`
     * @param dismissedToday true if the user dismissed the banner today
     */
    fun shouldShow(
        now: Long,
        todayStart: Long,
        windowHours: Int,
        featureEnabled: Boolean,
        alreadyCheckedInToday: Boolean,
        dismissedToday: Boolean
    ): Boolean {
        if (!featureEnabled) return false
        if (alreadyCheckedInToday) return false
        if (dismissedToday) return false
        if (now < todayStart) return false
        val clampedHours = windowHours.coerceIn(1, 24)
        val cutoffMillis = todayStart + clampedHours * MILLIS_PER_HOUR
        return now < cutoffMillis
    }

    private const val MILLIS_PER_HOUR = 60L * 60_000L
}
