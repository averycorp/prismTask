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

    /**
     * The structural repro: SoD = 7, wall-clock = 02:00, banner must
     * stay hidden because the user's logical day hasn't started yet.
     * The pre-fix `hour < 11` predicate returned true here (user-visible bug).
     */
    @Test
    fun `pre-SoD wall-clock window keeps banner hidden`() {
        // logicalDate is yesterday; todayStart anchors yesterday at 7am.
        val todayStart = millis(2026, 5, 6, 7)
        val now = millis(2026, 5, 7, 2) // 02:00 the next wall-clock morning, still pre-SoD
        assertFalse(
            MorningCheckInBannerDecider.shouldShow(
                now = now,
                todayStart = todayStart,
                sodHour = 7,
                sodMinute = 0,
                cutoffHour = 11,
                featureEnabled = true,
                alreadyCheckedInToday = false,
                dismissedToday = false
            )
        )
    }

    @Test
    fun `inside SoD-cutoff window banner shown`() {
        val todayStart = millis(2026, 5, 7, 7)
        val now = millis(2026, 5, 7, 8)
        assertTrue(
            MorningCheckInBannerDecider.shouldShow(
                now = now,
                todayStart = todayStart,
                sodHour = 7,
                sodMinute = 0,
                cutoffHour = 11,
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
                sodHour = 7,
                sodMinute = 0,
                cutoffHour = 11,
                featureEnabled = true,
                alreadyCheckedInToday = false,
                dismissedToday = false
            )
        )
    }

    @Test
    fun `past cutoff banner hidden`() {
        val todayStart = millis(2026, 5, 7, 7)
        val now = millis(2026, 5, 7, 12)
        assertFalse(
            MorningCheckInBannerDecider.shouldShow(
                now = now,
                todayStart = todayStart,
                sodHour = 7,
                sodMinute = 0,
                cutoffHour = 11,
                featureEnabled = true,
                alreadyCheckedInToday = false,
                dismissedToday = false
            )
        )
    }

    @Test
    fun `at exact cutoff banner hidden`() {
        val todayStart = millis(2026, 5, 7, 7)
        val now = millis(2026, 5, 7, 11)
        assertFalse(
            MorningCheckInBannerDecider.shouldShow(
                now = now,
                todayStart = todayStart,
                sodHour = 7,
                sodMinute = 0,
                cutoffHour = 11,
                featureEnabled = true,
                alreadyCheckedInToday = false,
                dismissedToday = false
            )
        )
    }

    @Test
    fun `cutoff respects user pref - cutoff equals 9 hides banner at 10`() {
        val todayStart = millis(2026, 5, 7, 7)
        val now = millis(2026, 5, 7, 10)
        assertFalse(
            MorningCheckInBannerDecider.shouldShow(
                now = now,
                todayStart = todayStart,
                sodHour = 7,
                sodMinute = 0,
                // user tightened the cutoff via Advanced Tuning slider
                cutoffHour = 9,
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
                sodHour = 7,
                sodMinute = 0,
                cutoffHour = 11,
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
                sodHour = 7,
                sodMinute = 0,
                cutoffHour = 11,
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
                sodHour = 7,
                sodMinute = 0,
                cutoffHour = 11,
                featureEnabled = true,
                alreadyCheckedInToday = false,
                dismissedToday = true
            )
        )
    }

    /**
     * SoD that wraps past midnight (e.g. night-owl user with SoD = 22).
     * Cutoff = 2 means "morning window is 22:00 to 02:00." Banner
     * should stay visible at 23:00 (just after SoD) and at 01:00
     * (still inside the wrapped window), and disappear at 03:00.
     */
    @Test
    fun `SoD wrap-around - in-window after midnight shows banner`() {
        val todayStart = millis(2026, 5, 7, 22)
        val now = millis(2026, 5, 8, 1) // 01:00 the next wall-clock day
        assertTrue(
            MorningCheckInBannerDecider.shouldShow(
                now = now,
                todayStart = todayStart,
                sodHour = 22,
                sodMinute = 0,
                cutoffHour = 2,
                featureEnabled = true,
                alreadyCheckedInToday = false,
                dismissedToday = false
            )
        )
    }

    @Test
    fun `SoD wrap-around - past wrapped cutoff hides banner`() {
        val todayStart = millis(2026, 5, 7, 22)
        val now = millis(2026, 5, 8, 3)
        assertFalse(
            MorningCheckInBannerDecider.shouldShow(
                now = now,
                todayStart = todayStart,
                sodHour = 22,
                sodMinute = 0,
                cutoffHour = 2,
                featureEnabled = true,
                alreadyCheckedInToday = false,
                dismissedToday = false
            )
        )
    }

    @Test
    fun `SoD with minute precision keeps window aligned`() {
        val todayStart = millis(2026, 5, 7, 7, 30)
        val now = millis(2026, 5, 7, 7, 45)
        assertTrue(
            MorningCheckInBannerDecider.shouldShow(
                now = now,
                todayStart = todayStart,
                sodHour = 7,
                sodMinute = 30,
                cutoffHour = 11,
                featureEnabled = true,
                alreadyCheckedInToday = false,
                dismissedToday = false
            )
        )
    }
}
