package com.averycorp.prismtask.smoke

import androidx.compose.ui.test.assertIsDisplayed
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test

/**
 * Smoke tests for the Settings screen. In v1.4 Settings is a bottom-nav
 * tab (not a gear icon on each screen per the original spec), so these
 * tests verify the tab is always reachable and that clicking it doesn't
 * crash the harness. Individual settings sections have their own
 * ViewModel-level unit coverage.
 */
@HiltAndroidTest
class SettingsSmokeTest : SmokeTestBase() {
    @Test
    fun settingsGear_isReachableFromTodayTab() {
        composeRule.waitForIdle()
        findTab("Settings").assertIsDisplayed()
    }

    @Test
    fun settingsGear_isReachableFromTasksTab() {
        composeRule.waitForIdle()
        clickTab("Tasks")
        findTab("Settings").assertIsDisplayed()
    }

    @Test
    fun settingsGear_isReachableFromHabitsTab() {
        composeRule.waitForIdle()
        // Bottom-nav label is "Habits" for the habit list.
        clickTab("Habits")
        findTab("Settings").assertIsDisplayed()
    }

    @Test
    fun settingsGear_clickDoesNotCrashApp() {
        composeRule.waitForIdle()
        clickTab("Settings")
        // If we got this far without an exception, the Settings route at
        // least composes for an initial frame.
        findTab("Settings").assertIsDisplayed()
    }
}
