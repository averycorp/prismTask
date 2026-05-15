package com.averycorp.prismtask.ui.screens.medication.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression gate for [applyHoursMinutesEdit] — the helper that drives the
 * split hours/minutes inputs in [IntervalHoursMinutesField] for medication
 * custom reminder intervals.
 *
 * The contract mirrors [applyMinuteFieldEdit] but for two fields: never
 * silently coerce out-of-range input, never commit when either field is
 * empty (treat empty as "still typing"), and reject minutes ≥ 60.
 */
class IntervalHoursMinutesInputTest {

    @Test
    fun fullyValidInput_commits() {
        val update = applyHoursMinutesEdit(
            rawHours = "1",
            rawMinutes = "30",
            minTotalMinutes = 60,
            maxTotalMinutes = 1440
        )
        assertEquals("1", update.hoursText)
        assertEquals("30", update.minutesText)
        assertEquals(90, update.newTotalMinutes)
        assertFalse(update.outOfRange)
    }

    @Test
    fun bothEmpty_isBlank_notError_andDoesNotCommit() {
        val update = applyHoursMinutesEdit(
            rawHours = "",
            rawMinutes = "",
            minTotalMinutes = 60,
            maxTotalMinutes = 1440
        )
        assertEquals("", update.hoursText)
        assertEquals("", update.minutesText)
        assertNull(update.newTotalMinutes)
        assertFalse(update.outOfRange)
    }

    @Test
    fun emptyMinutes_doesNotCommit() {
        // User cleared the minutes field to retype; we must hold without
        // committing — committing here would re-key remember() and overwrite
        // their next keystroke.
        val update = applyHoursMinutesEdit(
            rawHours = "2",
            rawMinutes = "",
            minTotalMinutes = 60,
            maxTotalMinutes = 1440
        )
        assertEquals("2", update.hoursText)
        assertEquals("", update.minutesText)
        assertNull(update.newTotalMinutes)
        assertFalse(update.outOfRange)
    }

    @Test
    fun emptyHours_withValidMinutes_doesNotCommit() {
        val update = applyHoursMinutesEdit(
            rawHours = "",
            rawMinutes = "30",
            minTotalMinutes = 60,
            maxTotalMinutes = 1440
        )
        assertEquals("", update.hoursText)
        assertEquals("30", update.minutesText)
        assertNull(update.newTotalMinutes)
        // 0h 30m = 30 total, below 60 min — flag the error.
        assertTrue(update.outOfRange)
    }

    @Test
    fun minutesOverflow_isHeld_notCoerced() {
        // Typing "90" into the minutes field is invalid even if total would
        // be in range. Hold the digits so the user can correct without
        // surprise rewrites.
        val update = applyHoursMinutesEdit(
            rawHours = "1",
            rawMinutes = "90",
            minTotalMinutes = 60,
            maxTotalMinutes = 1440
        )
        assertEquals("90", update.minutesText)
        assertNull(update.newTotalMinutes)
        assertTrue(update.outOfRange)
    }

    @Test
    fun totalBelowMinimum_isHeld() {
        val update = applyHoursMinutesEdit(
            rawHours = "0",
            rawMinutes = "45",
            minTotalMinutes = 60,
            maxTotalMinutes = 1440
        )
        assertNull(update.newTotalMinutes)
        assertTrue(update.outOfRange)
    }

    @Test
    fun totalAboveMaximum_isHeld() {
        val update = applyHoursMinutesEdit(
            rawHours = "25",
            rawMinutes = "0",
            minTotalMinutes = 60,
            maxTotalMinutes = 1440
        )
        assertNull(update.newTotalMinutes)
        assertTrue(update.outOfRange)
    }

    @Test
    fun exactBoundsCommit() {
        val low = applyHoursMinutesEdit(
            rawHours = "1",
            rawMinutes = "0",
            minTotalMinutes = 60,
            maxTotalMinutes = 1440
        )
        assertEquals(60, low.newTotalMinutes)
        assertFalse(low.outOfRange)

        val high = applyHoursMinutesEdit(
            rawHours = "24",
            rawMinutes = "0",
            minTotalMinutes = 60,
            maxTotalMinutes = 1440
        )
        assertEquals(1440, high.newTotalMinutes)
        assertFalse(high.outOfRange)
    }

    @Test
    fun nonDigitCharacters_areStripped() {
        val update = applyHoursMinutesEdit(
            rawHours = "1h",
            rawMinutes = "30m",
            minTotalMinutes = 60,
            maxTotalMinutes = 1440
        )
        assertEquals("1", update.hoursText)
        assertEquals("30", update.minutesText)
        assertEquals(90, update.newTotalMinutes)
        assertFalse(update.outOfRange)
    }

    @Test
    fun overlongInput_isCappedAtTwoDigits() {
        // Two digits is enough for hours (max 24) and minutes (max 59); this
        // matches the cap in `applyMinuteFieldEdit` semantics.
        val update = applyHoursMinutesEdit(
            rawHours = "123",
            rawMinutes = "456",
            minTotalMinutes = 60,
            maxTotalMinutes = 1440
        )
        assertEquals("12", update.hoursText)
        assertEquals("45", update.minutesText)
    }
}
