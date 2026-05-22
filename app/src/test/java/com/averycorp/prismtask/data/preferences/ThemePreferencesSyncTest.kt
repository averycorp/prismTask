package com.averycorp.prismtask.data.preferences

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [33], application = Application::class)
class ThemePreferencesSyncTest {
    private lateinit var prefs: ThemePreferences

    @Before
    fun setUp() = runBlocking {
        prefs = ThemePreferences(ApplicationProvider.getApplicationContext())
        prefs.clearAll()
    }

    @Test
    fun snapshot_includesWidgetThemeOverride() = runTest {
        prefs.setWidgetThemeOverride("CYBERPUNK")
        val snapshot = prefs.snapshot()
        assertEquals("CYBERPUNK", snapshot["widget_theme_override"])
    }

    @Test
    fun applyRemoteSnapshot_appliesWidgetThemeOverride() = runTest {
        val remote = mapOf("widget_theme_override" to "MATRIX")
        prefs.applyRemoteSnapshot(remote, 123L)
        assertEquals("MATRIX", prefs.getWidgetThemeOverride().first())
    }
}
