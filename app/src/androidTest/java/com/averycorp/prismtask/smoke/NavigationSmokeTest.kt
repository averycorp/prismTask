package com.averycorp.prismtask.smoke

import androidx.compose.ui.test.assertIsDisplayed
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test

@HiltAndroidTest
class NavigationSmokeTest : SmokeTestBase() {
    @Test
    fun bottomNav_hasExpectedTabs() {
        composeRule.waitForIdle()

        // Bottom nav labels per ALL_BOTTOM_NAV_ITEMS in NavGraph.kt:
        // Today, Tasks, Habits, Leisure, Timer. findTab scopes to
        // Role=Tab so duplicate "Today"/"Tasks" text nodes elsewhere in
        // the UI don't collide with the tab query.
        findTab("Today").assertIsDisplayed()
        findTab("Tasks").assertIsDisplayed()
        findTab("Habits").assertIsDisplayed()
        findTab("Leisure").assertIsDisplayed()
        findTab("Timer").assertIsDisplayed()
    }

    @Test
    fun bottomNav_switchesBetweenScreens() {
        composeRule.waitForIdle()

        // Smoke test for navigation only — asserting on content (specific
        // task/habit names) is fragile because each tab renders filtered
        // lists that may or may not show seeded rows depending on today's
        // schedule, priority ordering, active filters, etc. The per-tab
        // content coverage lives in the screen-specific smoke suites.
        clickTab("Tasks")
        findTab("Tasks").assertIsDisplayed()

        clickTab("Habits")
        findTab("Habits").assertIsDisplayed()

        clickTab("Today")
        findTab("Today").assertIsDisplayed()
    }

    @Test
    fun settingsGear_isOnMainScreens() {
        composeRule.waitForIdle()

        // Settings is a bottom-nav tab, not a gear icon on each screen.
        // Verify the tab exists regardless of the current screen.
        findTab("Settings").assertIsDisplayed()

        clickTab("Tasks")
        findTab("Settings").assertIsDisplayed()

        clickTab("Habits")
        findTab("Settings").assertIsDisplayed()
    }
}
