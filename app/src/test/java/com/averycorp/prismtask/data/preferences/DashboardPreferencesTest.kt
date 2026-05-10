package com.averycorp.prismtask.data.preferences

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [33], application = Application::class)
class DashboardPreferencesTest {
    private lateinit var prefs: DashboardPreferences

    @Before
    fun setUp() = runBlocking {
        prefs = DashboardPreferences(ApplicationProvider.getApplicationContext())
        prefs.resetToDefaults()
    }

    @Test
    fun getSectionOrder_defaultsToDefaultOrder() = runTest {
        val order = prefs.getSectionOrder().first()
        assertEquals(DashboardPreferences.DEFAULT_ORDER, order)
    }

    @Test
    fun setSectionOrder_roundTrips() = runTest {
        val custom = listOf("habits", "overdue", "progress")
        prefs.setSectionOrder(custom)
        assertEquals(custom, prefs.getSectionOrder().first())
    }

    @Test
    fun getHiddenSections_defaultsToHabits() = runTest {
        assertEquals(DashboardPreferences.DEFAULT_HIDDEN, prefs.getHiddenSections().first())
        assertTrue("habits" in prefs.getHiddenSections().first())
    }

    @Test
    fun setHiddenSections_roundTrips() = runTest {
        prefs.setHiddenSections(setOf("completed", "planned"))
        assertEquals(setOf("completed", "planned"), prefs.getHiddenSections().first())
    }

    @Test
    fun getCollapsedSections_defaultsIncludePlannedAndCompleted() = runTest {
        val collapsed = prefs.getCollapsedSections().first()
        assertTrue("planned" in collapsed)
        assertTrue("completed" in collapsed)
    }

    @Test
    fun setSectionCollapsed_togglesMembership() = runTest {
        prefs.setSectionCollapsed("today_tasks", collapsed = true)
        assertTrue("today_tasks" in prefs.getCollapsedSections().first())

        prefs.setSectionCollapsed("today_tasks", collapsed = false)
        assertTrue("today_tasks" !in prefs.getCollapsedSections().first())
    }

    @Test
    fun setProgressStyle_roundTrips() = runTest {
        prefs.setProgressStyle("bar")
        assertEquals("bar", prefs.getProgressStyle().first())
    }

    @Test
    fun resetToDefaults_clearsAllOverrides() = runTest {
        prefs.setSectionOrder(listOf("habits"))
        prefs.setHiddenSections(setOf("completed"))
        prefs.setProgressStyle("bar")
        prefs.resetToDefaults()

        assertEquals(DashboardPreferences.DEFAULT_ORDER, prefs.getSectionOrder().first())
        assertEquals(DashboardPreferences.DEFAULT_HIDDEN, prefs.getHiddenSections().first())
        assertEquals("ring", prefs.getProgressStyle().first())
    }
}
