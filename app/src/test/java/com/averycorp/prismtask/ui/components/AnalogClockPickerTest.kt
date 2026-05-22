package com.averycorp.prismtask.ui.components

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-function regression gate for the analog clock picker. Drives the
 * angle ↔ value math, the locale-stable readout, and the HH:mm parse /
 * format helpers — everything callers serialize and persist hangs off
 * these. The composable rendering is exercised by androidTest smoke
 * suites; this file pins the math so a future refactor cannot silently
 * shift the saved value relative to the tap position.
 */
class AnalogClockPickerTest {

    // --- angle / value mapping --------------------------------------------

    @Test
    fun hour12TopOfClockIs12() {
        // Straight up from center (dy negative, dx ~0) → 12 o'clock → hour 12.
        val angle = angleFromCenter(dx = 0f, dy = -100f)
        assertEquals(0f, angle, 0.5f)
        assertEquals(12, hourFromAngle(angle))
    }

    @Test
    fun hour12ThreeOclockIs3() {
        val angle = angleFromCenter(dx = 100f, dy = 0f)
        assertEquals(90f, angle, 0.5f)
        assertEquals(3, hourFromAngle(angle))
    }

    @Test
    fun hour12SixOclockIs6() {
        val angle = angleFromCenter(dx = 0f, dy = 100f)
        assertEquals(180f, angle, 0.5f)
        assertEquals(6, hourFromAngle(angle))
    }

    @Test
    fun hour12NineOclockIs9() {
        val angle = angleFromCenter(dx = -100f, dy = 0f)
        assertEquals(270f, angle, 0.5f)
        assertEquals(9, hourFromAngle(angle))
    }

    @Test
    fun minuteFromAngleAtTopIsZero() {
        val angle = angleFromCenter(dx = 0f, dy = -100f)
        assertEquals(0, minuteFromAngle(angle))
    }

    @Test
    fun minuteFromAngleAtThreeOclockIs15() {
        val angle = angleFromCenter(dx = 100f, dy = 0f)
        assertEquals(15, minuteFromAngle(angle))
    }

    @Test
    fun minuteFromAngleAtSixOclockIs30() {
        val angle = angleFromCenter(dx = 0f, dy = 100f)
        assertEquals(30, minuteFromAngle(angle))
    }

    @Test
    fun minuteFromAngleAtNineOclockIs45() {
        val angle = angleFromCenter(dx = -100f, dy = 0f)
        assertEquals(45, minuteFromAngle(angle))
    }

    // --- applyPointer drives the active hand --------------------------------

    @Test
    fun applyPointerSetsHour12WhenTappingThreeOclock() {
        val state = AnalogClockState(
            initialHour = 9,
            initialMinute = 0,
            initialSecond = 0,
            is24Hour = false,
            initialActiveHand = ClockHand.HOUR
        )
        // 3 o'clock position on a 200px square (center 100,100).
        applyPointer(Offset(180f, 100f), diameterPx = 200f, state = state, advanceActiveHand = false)
        // 12-hour dial, AM remains AM (state.hour was 9 → AM, stays AM).
        assertEquals(3, state.hour)
    }

    @Test
    fun applyPointerKeepsPmHalfWhenUserWasOnPm() {
        val state = AnalogClockState(
            initialHour = 21,
// 9 PM
            initialMinute = 0,
            initialSecond = 0,
            is24Hour = false,
            initialActiveHand = ClockHand.HOUR
        )
        applyPointer(Offset(180f, 100f), diameterPx = 200f, state = state, advanceActiveHand = false)
        // Tap 3 → state should remain PM, so 15 (3 PM).
        assertEquals(15, state.hour)
    }

    @Test
    fun applyPointerInnerRingPicksPmIn24HourMode() {
        val state = AnalogClockState(
            initialHour = 9,
            initialMinute = 0,
            initialSecond = 0,
            is24Hour = true,
            initialActiveHand = ClockHand.HOUR
        )
        // Inner ring at "3 o'clock" → 3 PM = 15. Diameter 200 → outer
        // radius ~92, inner threshold = 92 * 0.66 ≈ 60.7. We need a
        // pointer between center and that threshold along +x. (140, 100)
        // is 40 px from center → well within the inner ring.
        applyPointer(Offset(140f, 100f), diameterPx = 200f, state = state, advanceActiveHand = false)
        assertEquals(15, state.hour)
    }

    @Test
    fun applyPointerOuterRingPicksAmIn24HourMode() {
        val state = AnalogClockState(
            initialHour = 15,
// 3 PM
            initialMinute = 0,
            initialSecond = 0,
            is24Hour = true,
            initialActiveHand = ClockHand.HOUR
        )
        // Outer ring at "3 o'clock" → hour 3 (AM).
        applyPointer(Offset(180f, 100f), diameterPx = 200f, state = state, advanceActiveHand = false)
        assertEquals(3, state.hour)
    }

    @Test
    fun applyPointerAdvanceActiveHandFromHourToMinute() {
        val state = AnalogClockState(
            initialHour = 9,
            initialMinute = 0,
            initialSecond = 0,
            is24Hour = false,
            initialActiveHand = ClockHand.HOUR
        )
        applyPointer(Offset(180f, 100f), diameterPx = 200f, state = state, advanceActiveHand = true)
        assertEquals(ClockHand.MINUTE, state.activeHand)
    }

    @Test
    fun applyPointerAdvanceActiveHandFromMinuteToSecond() {
        val state = AnalogClockState(
            initialHour = 9,
            initialMinute = 0,
            initialSecond = 0,
            is24Hour = false,
            initialActiveHand = ClockHand.MINUTE
        )
        applyPointer(Offset(100f, 180f), diameterPx = 200f, state = state, advanceActiveHand = true)
        assertEquals(30, state.minute)
        assertEquals(ClockHand.SECOND, state.activeHand)
    }

    @Test
    fun applyPointerSecondHandIsTerminal() {
        val state = AnalogClockState(
            initialHour = 9,
            initialMinute = 0,
            initialSecond = 0,
            is24Hour = false,
            initialActiveHand = ClockHand.SECOND
        )
        applyPointer(Offset(100f, 180f), diameterPx = 200f, state = state, advanceActiveHand = true)
        assertEquals(30, state.second)
        // Second is terminal — tapping does not roll back to HOUR.
        assertEquals(ClockHand.SECOND, state.activeHand)
    }

    @Test
    fun applyPointerIgnoresTapsTooCloseToCenter() {
        val state = AnalogClockState(
            initialHour = 9,
            initialMinute = 0,
            initialSecond = 0,
            is24Hour = false,
            initialActiveHand = ClockHand.HOUR
        )
        applyPointer(Offset(102f, 100f), diameterPx = 200f, state = state, advanceActiveHand = false)
        // Hour unchanged — the pointer is 2 px from center, below the
        // jitter threshold.
        assertEquals(9, state.hour)
    }

    // --- AM/PM toggling preserves visible dial hour -------------------------

    @Test
    fun toggleAmPmFlipsHalfOfDay() {
        val state = AnalogClockState(
            initialHour = 9,
            initialMinute = 0,
            initialSecond = 0,
            is24Hour = false
        )
        assertEquals(true, state.isAm)
        state.toggleAmPm()
        assertEquals(21, state.hour)
        assertEquals(false, state.isAm)
        state.toggleAmPm()
        assertEquals(9, state.hour)
        assertEquals(true, state.isAm)
    }

    // --- readout formatter -------------------------------------------------

    @Test
    fun readoutFormats12HourAm() {
        assertEquals("8:30:00 AM", formatAnalogClockTime(8, 30, 0, is24Hour = false))
    }

    @Test
    fun readoutFormats12HourPm() {
        assertEquals("2:30:45 PM", formatAnalogClockTime(14, 30, 45, is24Hour = false))
    }

    @Test
    fun readoutFormats12HourMidnight() {
        assertEquals("12:00:00 AM", formatAnalogClockTime(0, 0, 0, is24Hour = false))
    }

    @Test
    fun readoutFormats12HourNoon() {
        assertEquals("12:00:00 PM", formatAnalogClockTime(12, 0, 0, is24Hour = false))
    }

    @Test
    fun readoutFormats24HourPadsHourAndMinute() {
        assertEquals("08:05:09", formatAnalogClockTime(8, 5, 9, is24Hour = true))
    }

    @Test
    fun readoutFormats24HourMidnight() {
        assertEquals("00:00:00", formatAnalogClockTime(0, 0, 0, is24Hour = true))
    }

    // --- HH:mm round-tripping ----------------------------------------------

    @Test
    fun parseHhMmAccepts09_00() {
        assertEquals(9 to 0, parseHhMm("09:00"))
    }

    @Test
    fun parseHhMmAccepts23_59() {
        assertEquals(23 to 59, parseHhMm("23:59"))
    }

    @Test
    fun parseHhMmRejectsHourOutOfRange() {
        assertNull(parseHhMm("24:00"))
    }

    @Test
    fun parseHhMmRejectsMinuteOutOfRange() {
        assertNull(parseHhMm("09:60"))
    }

    @Test
    fun parseHhMmRejectsMissingColon() {
        assertNull(parseHhMm("0900"))
    }

    @Test
    fun parseHhMmRejectsNonNumeric() {
        assertNull(parseHhMm("9a:00"))
    }

    @Test
    fun formatHhMmZeroPads() {
        assertEquals("09:05", formatHhMm(9, 5))
    }

    @Test
    fun formatHhMmHandlesMidnight() {
        assertEquals("00:00", formatHhMm(0, 0))
    }

    @Test
    fun formatHhMmHandlesEndOfDay() {
        assertEquals("23:59", formatHhMm(23, 59))
    }
}
