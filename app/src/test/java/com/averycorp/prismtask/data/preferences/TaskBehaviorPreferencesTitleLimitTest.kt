package com.averycorp.prismtask.data.preferences

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Scoped to the new `maxTaskTitleLength` + enabled-toggle pair on
 * [TaskBehaviorPreferences]. Other [TaskBehaviorPreferences] keys are
 * exercised by [DataExporter] / `DeviceConfigImporter` round-trip tests.
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [33], application = Application::class)
class TaskBehaviorPreferencesTitleLimitTest {
    private lateinit var prefs: TaskBehaviorPreferences

    @Before
    fun setUp() = runTest {
        prefs = TaskBehaviorPreferences(ApplicationProvider.getApplicationContext())
        prefs.resetToDefaults()
    }

    @Test
    fun titleLengthLimit_defaultsToConfiguredDefault() = runTest {
        val limit = prefs.getTitleLengthLimit().first()
        assertEquals(TitleLengthLimit.DEFAULT_LIMIT, limit.limit)
    }

    @Test
    fun titleLengthLimit_returnsNullWhenDisabled() = runTest {
        prefs.setMaxTaskTitleLengthEnabled(false)
        assertNull(prefs.getTitleLengthLimit().first().limit)
    }

    @Test
    fun titleLengthLimit_reEnableRestoresConfiguredValue() = runTest {
        prefs.setMaxTaskTitleLength(250)
        prefs.setMaxTaskTitleLengthEnabled(false)
        prefs.setMaxTaskTitleLengthEnabled(true)
        assertEquals(250, prefs.getTitleLengthLimit().first().limit)
    }

    @Test
    fun setMaxTaskTitleLength_clampsAboveMax() = runTest {
        prefs.setMaxTaskTitleLength(10_000)
        assertEquals(TitleLengthLimit.MAX_LIMIT, prefs.getTitleLengthLimit().first().limit)
    }

    @Test
    fun setMaxTaskTitleLength_clampsBelowMin() = runTest {
        prefs.setMaxTaskTitleLength(0)
        assertEquals(TitleLengthLimit.MIN_LIMIT, prefs.getTitleLengthLimit().first().limit)
    }

    @Test
    fun setMaxTaskTitleLength_roundTripsValidValue() = runTest {
        prefs.setMaxTaskTitleLength(180)
        assertEquals(180, prefs.getTitleLengthLimit().first().limit)
    }
}
