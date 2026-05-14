package com.averycorp.prismtask.data.preferences

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Covers the override-volume preference pair added for the
 * timer "ring at a set volume" feature. Older keys are exercised
 * indirectly by [DataExporter] / [DeviceConfigImporter] tests; this
 * file scopes itself to the new shape.
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [33], application = Application::class)
class TimerPreferencesTest {
    private lateinit var prefs: TimerPreferences

    @Before
    fun setUp() = runBlocking {
        prefs = TimerPreferences(ApplicationProvider.getApplicationContext())
        prefs.setOverrideVolume(false)
        prefs.setAlarmVolumePercent(TimerPreferences.DEFAULT_ALARM_VOLUME_PERCENT)
        prefs.setAlarmSoundId(TimerPreferences.DEFAULT_ALARM_SOUND_ID)
        prefs.setRingDurationSeconds(TimerPreferences.DEFAULT_RING_DURATION_SECONDS)
        prefs.setVibrateEnabled(TimerPreferences.DEFAULT_VIBRATE_ENABLED)
        prefs.setVibrationDurationSeconds(TimerPreferences.DEFAULT_VIBRATION_DURATION_SECONDS)
    }

    @Test
    fun overrideVolume_defaultsToFalse() = runTest {
        assertFalse(prefs.getOverrideVolume().first())
    }

    @Test
    fun overrideVolume_roundTripsTrue() = runTest {
        prefs.setOverrideVolume(true)
        assertTrue(prefs.getOverrideVolume().first())
    }

    @Test
    fun alarmVolumePercent_defaults() = runTest {
        assertEquals(
            TimerPreferences.DEFAULT_ALARM_VOLUME_PERCENT,
            prefs.getAlarmVolumePercent().first()
        )
    }

    @Test
    fun alarmVolumePercent_roundTrips() = runTest {
        prefs.setAlarmVolumePercent(42)
        assertEquals(42, prefs.getAlarmVolumePercent().first())
    }

    @Test
    fun alarmVolumePercent_clampsHigh() = runTest {
        prefs.setAlarmVolumePercent(250)
        assertEquals(
            TimerPreferences.MAX_ALARM_VOLUME_PERCENT,
            prefs.getAlarmVolumePercent().first()
        )
    }

    @Test
    fun alarmVolumePercent_clampsLow() = runTest {
        prefs.setAlarmVolumePercent(-5)
        assertEquals(
            TimerPreferences.MIN_ALARM_VOLUME_PERCENT,
            prefs.getAlarmVolumePercent().first()
        )
    }

    @Test
    fun alarmSoundId_defaultsToSystemDefault() = runTest {
        assertEquals(
            TimerPreferences.DEFAULT_ALARM_SOUND_ID,
            prefs.getAlarmSoundId().first()
        )
    }

    @Test
    fun alarmSoundId_roundTripsBuiltIn() = runTest {
        prefs.setAlarmSoundId("chime_gentle")
        assertEquals("chime_gentle", prefs.getAlarmSoundId().first())
    }

    @Test
    fun alarmSoundId_blankFallsBackToDefault() = runTest {
        prefs.setAlarmSoundId("   ")
        assertEquals(
            TimerPreferences.DEFAULT_ALARM_SOUND_ID,
            prefs.getAlarmSoundId().first()
        )
    }

    @Test
    fun ringDurationSeconds_defaults() = runTest {
        assertEquals(
            TimerPreferences.DEFAULT_RING_DURATION_SECONDS,
            prefs.getRingDurationSeconds().first()
        )
    }

    @Test
    fun ringDurationSeconds_roundTrips() = runTest {
        prefs.setRingDurationSeconds(20)
        assertEquals(20, prefs.getRingDurationSeconds().first())
    }

    @Test
    fun ringDurationSeconds_clampsHigh() = runTest {
        prefs.setRingDurationSeconds(9_999)
        assertEquals(
            TimerPreferences.MAX_RING_DURATION_SECONDS,
            prefs.getRingDurationSeconds().first()
        )
    }

    @Test
    fun ringDurationSeconds_clampsLow() = runTest {
        prefs.setRingDurationSeconds(0)
        assertEquals(
            TimerPreferences.MIN_RING_DURATION_SECONDS,
            prefs.getRingDurationSeconds().first()
        )
    }

    @Test
    fun vibrateEnabled_defaultsTrue() = runTest {
        // Default preserves the pre-feature behavior where the channel's
        // DEFAULT_ALL vibration fired on every timer completion.
        assertTrue(prefs.getVibrateEnabled().first())
    }

    @Test
    fun vibrateEnabled_roundTripsFalse() = runTest {
        prefs.setVibrateEnabled(false)
        assertFalse(prefs.getVibrateEnabled().first())
    }

    @Test
    fun vibrationDurationSeconds_defaults() = runTest {
        assertEquals(
            TimerPreferences.DEFAULT_VIBRATION_DURATION_SECONDS,
            prefs.getVibrationDurationSeconds().first()
        )
    }

    @Test
    fun vibrationDurationSeconds_roundTrips() = runTest {
        prefs.setVibrationDurationSeconds(15)
        assertEquals(15, prefs.getVibrationDurationSeconds().first())
    }

    @Test
    fun vibrationDurationSeconds_clampsHigh() = runTest {
        prefs.setVibrationDurationSeconds(9_999)
        assertEquals(
            TimerPreferences.MAX_VIBRATION_DURATION_SECONDS,
            prefs.getVibrationDurationSeconds().first()
        )
    }

    @Test
    fun vibrationDurationSeconds_clampsLow() = runTest {
        prefs.setVibrationDurationSeconds(0)
        assertEquals(
            TimerPreferences.MIN_VIBRATION_DURATION_SECONDS,
            prefs.getVibrationDurationSeconds().first()
        )
    }
}
