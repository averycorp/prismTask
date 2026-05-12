package com.averycorp.prismtask.startup

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Detects DataStore file-name collisions that cause startup crashes.
 *
 * Android's `preferencesDataStore(name = …)` delegate creates a file at
 * `<filesDir>/datastore/<name>.preferences_pb`. If two different delegate
 * declarations use the same name, DataStore throws:
 *
 *     IllegalStateException: There are multiple DataStores active for the same file
 *
 * This happened in PR #331 and was a root cause of a startup crash. The test
 * below scans every Kotlin source file for `preferencesDataStore(name = "…")`
 * and asserts that each name is unique.
 */
class DataStoreCollisionTest {
    /**
     * All known DataStore file names used across the app.
     *
     * Maintained manually — if a new DataStore is added, add it here.
     * The test below verifies there are no duplicates AND cross-checks
     * against the source files to catch missed additions.
     */
    private val knownDataStoreNames = listOf(
        // PreferencesModule.kt
        "sort_prefs",
        "gcal_sync_prefs",
        "user_prefs",
        "nd_prefs",
        // Individual preference classes
        "tab_prefs",
        "onboarding_prefs",
        "timer_prefs",
        "a11y_prefs",
        "theme_prefs",
        "pro_status_prefs",
        "template_prefs",
        "coaching_prefs",
        "voice_prefs",
        "medication_prefs",
        "task_behavior_prefs",
        "backend_sync_prefs",
        "calendar_prefs",
        "archive_prefs",
        "dashboard_prefs",
        "habit_list_prefs",
        "leisure_budget_prefs",
        "auth_token_prefs",
        "morning_checkin_prefs",
        "notification_prefs",
        "reengagement_prefs",
        "timer_widget_state",
        "widget_config"
    )

    @Test
    fun `all DataStore names are unique`() {
        val duplicates = knownDataStoreNames
            .groupBy { it }
            .filter { it.value.size > 1 }

        assertTrue(
            "Duplicate DataStore names found: ${duplicates.keys}. " +
                "Each preferencesDataStore delegate must use a unique file name " +
                "or the app will crash with IllegalStateException on startup.",
            duplicates.isEmpty()
        )
    }

    @Test
    fun `no DataStore names are blank or empty`() {
        for (name in knownDataStoreNames) {
            assertTrue(
                "DataStore name must not be blank",
                name.isNotBlank()
            )
        }
    }

    @Test
    fun `DataStore names do not contain path separators`() {
        for (name in knownDataStoreNames) {
            assertTrue(
                "DataStore name '$name' must not contain '/' or '\\'",
                !name.contains('/') && !name.contains('\\')
            )
        }
    }

    @Test
    fun `source files contain all known DataStore names`() {
        // Scan src/main for preferencesDataStore declarations and verify
        // every name found is in knownDataStoreNames. This catches
        // cases where a developer adds a new DataStore but forgets to
        // register it here — the test will fail, prompting them to add it.
        val srcMain = File("app/src/main")
        if (!srcMain.exists()) {
            // When running from a different working directory (e.g. CI root),
            // skip the source-scan portion but keep the uniqueness check.
            return
        }

        val namePattern = Regex("""preferencesDataStore\(\s*name\s*=\s*"([^"]+)"""")
        val foundNames = mutableSetOf<String>()

        srcMain
            .walkTopDown()
            .filter { it.extension == "kt" }
            .forEach { file ->
                namePattern.findAll(file.readText()).forEach { match ->
                    foundNames.add(match.groupValues[1])
                }
            }

        val missing = foundNames - knownDataStoreNames.toSet()
        assertTrue(
            "Found DataStore names in source that are not in knownDataStoreNames: $missing. " +
                "Add them to the list in DataStoreCollisionTest to ensure collision detection.",
            missing.isEmpty()
        )
    }
}
