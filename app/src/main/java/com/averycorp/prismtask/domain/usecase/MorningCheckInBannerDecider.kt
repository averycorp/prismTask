package com.averycorp.prismtask.domain.usecase

/**
 * Pure-function decider for the Today-screen morning check-in banner.
 *
 * Replaces the inline `hour < 11` predicate that previously lived in
 * `TodayViewModel`. That predicate compared raw wall-clock hour against
 * a hardcoded `11` and so ignored both ends of the user's
 * Start-of-Day window: the banner appeared between calendar midnight
 * and SoD (when the user's day hadn't started yet), and the
 * `MorningCheckInPromptCutoff` Settings slider had no effect on the
 * banner.
 *
 * See `docs/audits/MORNING_CHECKIN_SOD_BOUNDARY_AUDIT.md`.
 */
object MorningCheckInBannerDecider {
    /**
     * Returns true when the morning check-in banner should be visible.
     *
     * The visible window is `[todayStart, todayStart + offset)` where
     * `offset` is the wall-clock distance from SoD to [cutoffHour]. The
     * cutoff is interpreted as a wall-clock hour: if the cutoff hour is
     * less than or equal to the SoD hour, it's treated as falling on the
     * wall-clock day after SoD (so SoD = 22, cutoff = 2 means a 4-hour
     * window 22:00–02:00). A cutoff of exactly the SoD hour collapses
     * to a 24-hour window — uncommon but consistent with the
     * "morning starts at SoD" mental model.
     *
     * @param now wall-clock now in epoch millis
     * @param todayStart SoD-anchored start of the user's logical today,
     *   in epoch millis (compute via [com.averycorp.prismtask.util.DayBoundary]
     *   or `logicalDate.atTime(sod.hour, sod.minute)`)
     * @param sodHour user's configured Start-of-Day hour (0..23)
     * @param sodMinute user's configured Start-of-Day minute (0..59)
     * @param cutoffHour wall-clock cutoff hour (0..23) from
     *   `AdvancedTuningPreferences.getMorningCheckInPromptCutoff()`
     * @param featureEnabled `MorningCheckInPreferences.featureEnabled()`
     * @param alreadyCheckedInToday true if a CheckInLog row already
     *   exists with `date >= todayStart`
     * @param dismissedToday true if the user dismissed the banner today
     */
    fun shouldShow(
        now: Long,
        todayStart: Long,
        sodHour: Int,
        sodMinute: Int,
        cutoffHour: Int,
        featureEnabled: Boolean,
        alreadyCheckedInToday: Boolean,
        dismissedToday: Boolean
    ): Boolean {
        if (!featureEnabled) return false
        if (alreadyCheckedInToday) return false
        if (dismissedToday) return false
        if (now < todayStart) return false
        val offsetMinutes = windowOffsetMinutes(sodHour, sodMinute, cutoffHour)
        val cutoffMillis = todayStart + offsetMinutes * MILLIS_PER_MINUTE
        return now < cutoffMillis
    }

    private fun windowOffsetMinutes(sodHour: Int, sodMinute: Int, cutoffHour: Int): Int {
        val sodMinutes = sodHour * MINUTES_PER_HOUR + sodMinute
        val cutoffMinutes = cutoffHour * MINUTES_PER_HOUR
        val raw = cutoffMinutes - sodMinutes
        return if (raw <= 0) raw + MINUTES_PER_DAY else raw
    }

    private const val MINUTES_PER_HOUR = 60
    private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
    private const val MILLIS_PER_MINUTE = 60_000L
}
