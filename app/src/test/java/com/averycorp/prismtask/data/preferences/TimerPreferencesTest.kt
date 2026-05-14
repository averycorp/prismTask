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
}
