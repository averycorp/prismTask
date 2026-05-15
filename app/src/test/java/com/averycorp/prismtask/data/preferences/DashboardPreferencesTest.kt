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
        val custom = DashboardPreferences.DEFAULT_ORDER.reversed()
        prefs.setSectionOrder(custom)
        assertEquals(custom, prefs.getSectionOrder().first())
    }

    @Test
    fun getSectionOrder_appendsKeysAddedAfterUserCustomized() = runTest {
        // Simulate an older install whose stored order predates "habits" being a toggleable section.
        val legacy = DashboardPreferences.DEFAULT_ORDER.filterNot { it == "habits" }
        prefs.setSectionOrder(legacy)

        val resolved = prefs.getSectionOrder().first()
        assertEquals(legacy + "habits", resolved)
    }

    @Test
    fun getHiddenSections_defaultsToEmpty() = runTest {
        assertEquals(DashboardPreferences.DEFAULT_HIDDEN, prefs.getHiddenSections().first())
        assertTrue(prefs.getHiddenSections().first().isEmpty())
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
    fun getCompletionCountMode_defaultsToTasksAndHabits() = runTest {
        assertEquals(CompletionCountMode.TASKS_AND_HABITS, prefs.getCompletionCountMode().first())
    }

    @Test
    fun setCompletionCountMode_roundTripsForEveryVariant() = runTest {
        for (mode in CompletionCountMode.entries) {
            prefs.setCompletionCountMode(mode)
            assertEquals(mode, prefs.getCompletionCountMode().first())
        }
    }

    @Test
    fun completionCountMode_fromName_fallsBackToTasksAndHabitsForUnknown() {
        assertEquals(CompletionCountMode.TASKS_AND_HABITS, CompletionCountMode.fromName(null))
        assertEquals(CompletionCountMode.TASKS_AND_HABITS, CompletionCountMode.fromName("garbage"))
        assertEquals(
            CompletionCountMode.TASKS_HABITS_AND_SELFCARE,
            CompletionCountMode.fromName(CompletionCountMode.TASKS_HABITS_AND_SELFCARE.name)
        )
    }

    @Test
    fun resetToDefaults_clearsAllOverrides() = runTest {
        prefs.setSectionOrder(listOf("completed"))
        prefs.setHiddenSections(setOf("completed"))
        prefs.setProgressStyle("bar")
        prefs.setCompletionCountMode(CompletionCountMode.TASKS_ONLY)
        prefs.resetToDefaults()

        assertEquals(DashboardPreferences.DEFAULT_ORDER, prefs.getSectionOrder().first())
        assertEquals(DashboardPreferences.DEFAULT_HIDDEN, prefs.getHiddenSections().first())
        assertEquals("ring", prefs.getProgressStyle().first())
        assertEquals(CompletionCountMode.TASKS_AND_HABITS, prefs.getCompletionCountMode().first())
    }
}
