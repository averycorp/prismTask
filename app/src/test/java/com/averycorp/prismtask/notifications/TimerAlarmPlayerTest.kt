package com.averycorp.prismtask.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM coverage for the percent-to-stream-level math. The MediaPlayer
 * / AudioManager paths require a real device and are exercised manually.
 */
class TimerAlarmPlayerTest {

    @Test
    fun targetVolumeLevel_mapsMidPercentProportionally() {
        // 50% of a 10-step stream → 5
        assertEquals(5, TimerAlarmPlayer.targetVolumeLevel(50, 10))
    }

    @Test
    fun targetVolumeLevel_pinsMaxAt100() {
        assertEquals(15, TimerAlarmPlayer.targetVolumeLevel(100, 15))
    }

    @Test
    fun targetVolumeLevel_clampsHighPercentToMax() {
        // Callers may pre-clamp, but the helper still guards against drift.
        assertEquals(7, TimerAlarmPlayer.targetVolumeLevel(200, 7))
    }

    @Test
    fun targetVolumeLevel_lowPercentAlwaysAudible() {
        // 1% of a 10-step stream rounds to 0 — bump to 1 so the alarm
        // is never silently muted by a slider sitting at the bottom.
        assertEquals(1, TimerAlarmPlayer.targetVolumeLevel(1, 10))
    }

    @Test
    fun targetVolumeLevel_zeroPercentTreatedAsMinimum() {
        // The pref clamp prevents 0 in practice, but the helper still
        // returns the floor of 1 rather than muting outright.
        assertEquals(1, TimerAlarmPlayer.targetVolumeLevel(0, 10))
    }

    @Test
    fun targetVolumeLevel_zeroMaxReturnsZero() {
        // A device with no alarm stream (or a misreporting emulator)
        // returns 0 — no audible output is possible, so we mirror that.
        assertEquals(0, TimerAlarmPlayer.targetVolumeLevel(50, 0))
    }
}
