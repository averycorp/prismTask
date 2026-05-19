package com.averycorp.prismtask.domain.usecase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class MorningCheckInBannerDeciderTest {
    private val zone: ZoneId = ZoneOffset.UTC

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `pre-SoD now keeps banner hidden`() {
        // logicalDate is today; todayStart anchors today at 7am.
        // now is one hour before SoD — the user's day hasn't started.
        val todayStart = millis(2026, 5, 7, 7)
        val now = millis(2026, 5, 7, 6)
        assertFalse(
            MorningCheckInBannerDecider.shouldShow(
                now = now,
                todayStart = todayStart,
                windowHours = 12,
                featureEnabled = true,
                alreadyCheckedInToday = false,
                dismissedToday = false
            )
        )
    }

    @Test
    fun `inside window banner shown`() {
        val todayStart = millis(2026, 5, 7, 7)
        val now = millis(2026, 5, 7, 8)
        assertTrue(
            MorningCheckInBannerDecider.shouldShow(
                now = now,
                todayStart = todayStart,
                windowHours = 12,
                featureEnabled = true,
                alreadyCheckedInToday = false,
                dismissedToday = false
            )
        )
    }

    @Test
    fun `at exact SoD banner shown`() {
        val todayStart = millis(2026, 5, 7, 7)
        val now = todayStart
        assertTrue(
            MorningCheckInBannerDecider.shouldShow(
                now = now,
                todayStart = todayStart,
                windowHours = 12,
                featureEnabled = true,
                alreadyCheckedInToday = false,
                dismissedToday = false
            )
        )
    }

    @Test
    fun `past window banner hidden`() {
        // SoD=7, 12h window → cutoff at 19:00; 20:00 is past.
        val todayStart = millis(2026, 5, 7, 7)
        val now = millis(2026, 5, 7, 20)
        assertFalse(
            MorningCheckInBannerDecider.shouldShow(
                now = now,
                todayStart = todayStart,
                windowHours = 12,
                featureEnabled = true,
                alreadyCheckedInToday = false,
                dismissedToday = false
            )
        )
    }

    @Test
    fun `at exact cutoff banner hidden`() {
        val todayStart = millis(2026, 5, 7, 7)
        val now = millis(2026, 5, 7, 19) // SoD + 12h
        assertFalse(
            MorningCheckInBannerDecider.shouldShow(
                now = now,
                todayStart = todayStart,
                windowHours = 12,
                featureEnabled = true,
                alreadyCheckedInToday = false,
                dismissedToday = false
            )
        )
    }

    @Test
    fun `tightened window respects user pref`() {
        // User cut the window from 12h to 2h via Advanced Tuning.
        // SoD=7, +2h → cutoff at 09:00; now=10 is past.
        val todayStart = millis(2026, 5, 7, 7)
        val now = millis(2026, 5, 7, 10)
        assertFalse(
            MorningCheckInBannerDecider.shouldShow(
                now = now,
                todayStart = todayStart,
                windowHours = 2,
                featureEnabled = true,
                alreadyCheckedInToday = false,
                dismissedToday = false
            )
        )
    }

    @Test
    fun `feature disabled hides banner`() {
        val todayStart = millis(2026, 5, 7, 7)
        val now = millis(2026, 5, 7, 8)
        assertFalse(
            MorningCheckInBannerDecider.shouldShow(
                now = now,
                todayStart = todayStart,
                windowHours = 12,
                featureEnabled = false,
                alreadyCheckedInToday = false,
                dismissedToday = false
            )
        )
    }

    @Test
    fun `already checked in today hides banner`() {
        val todayStart = millis(2026, 5, 7, 7)
        val now = millis(2026, 5, 7, 8)
        assertFalse(
            MorningCheckInBannerDecider.shouldShow(
                now = now,
                todayStart = todayStart,
                windowHours = 12,
                featureEnabled = true,
                alreadyCheckedInToday = true,
                dismissedToday = false
            )
        )
    }

    @Test
    fun `dismissed today hides banner`() {
        val todayStart = millis(2026, 5, 7, 7)
        val now = millis(2026, 5, 7, 8)
        assertFalse(
            MorningCheckInBannerDecider.shouldShow(
                now = now,
                todayStart = todayStart,
                windowHours = 12,
                featureEnabled = true,
                alreadyCheckedInToday = false,
                dismissedToday = true
            )
        )
    }

    /**
     * Night-owl SoD (22:00). With a 4-hour window the banner stays
     * visible at 23:00 and at 01:00 the next wall-clock day, and
     * disappears at 03:00. The cutoff is computed from `todayStart`
     * directly, so wall-clock midnight is irrelevant.
     */
    @Test
    fun `late SoD - in-window after midnight shows banner`() {
        val todayStart = millis(2026, 5, 7, 22)
        val now = millis(2026, 5, 8, 1)
        assertTrue(
            MorningCheckInBannerDecider.shouldShow(
                now = now,
                todayStart = todayStart,
                windowHours = 4,
                featureEnabled = true,
                alreadyCheckedInToday = false,
                dismissedToday = false
            )
        )
    }

    @Test
    fun `late SoD - past window hides banner`() {
        val todayStart = millis(2026, 5, 7, 22)
        val now = millis(2026, 5, 8, 3)
        assertFalse(
            MorningCheckInBannerDecider.shouldShow(
                now = now,
                todayStart = todayStart,
                windowHours = 4,
                featureEnabled = true,
                alreadyCheckedInToday = false,
                dismissedToday = false
            )
        )
    }

    @Test
    fun `out-of-range windowHours coerced to valid range`() {
        // windowHours=0 must coerce to 1 (still a 1-hour window after SoD).
        val todayStart = millis(2026, 5, 7, 7)
        val now = millis(2026, 5, 7, 7, 30)
        assertTrue(
            MorningCheckInBannerDecider.shouldShow(
                now = now,
                todayStart = todayStart,
                windowHours = 0,
                featureEnabled = true,
                alreadyCheckedInToday = false,
                dismissedToday = false
            )
        )
    }
}
