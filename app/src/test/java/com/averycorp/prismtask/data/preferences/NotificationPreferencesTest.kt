package com.averycorp.prismtask.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [NotificationPreferences]. Each test gets a brand new
 * [DataStore] rooted in a fresh [TemporaryFolder] so the documented
 * defaults are observable on first read regardless of what any sibling
 * test wrote.
 */
class NotificationPreferencesTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var prefs: NotificationPreferences

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val file = File(tmpFolder.root, "notification_prefs_test.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        prefs = NotificationPreferences(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `task reminders default to true`() = runTest {
        assertTrue(prefs.taskRemindersEnabled.first())
    }

    @Test
    fun `setTaskRemindersEnabled false round-trips`() = runTest {
        prefs.setTaskRemindersEnabled(false)
        assertEquals(false, prefs.taskRemindersEnabled.first())
    }

    @Test
    fun `every per-type flag defaults to true`() = runTest {
        assertTrue(prefs.taskRemindersEnabled.first())
        assertTrue(prefs.timerAlertsEnabled.first())
        assertTrue(prefs.medicationRemindersEnabled.first())
        assertTrue(prefs.dailyBriefingEnabled.first())
        assertTrue(prefs.eveningSummaryEnabled.first())
        assertTrue(prefs.weeklySummaryEnabled.first())
        assertTrue(prefs.overloadAlertsEnabled.first())
        assertTrue(prefs.reengagementEnabled.first())
    }

    @Test
    fun `default importance is standard`() = runTest {
        assertEquals(NotificationPreferences.IMPORTANCE_STANDARD, prefs.importance.first())
    }

    @Test
    fun `setImportance urgent persists`() = runTest {
        prefs.setImportance(NotificationPreferences.IMPORTANCE_URGENT)
        assertEquals(NotificationPreferences.IMPORTANCE_URGENT, prefs.importance.first())
    }

    @Test
    fun `setImportance rejects unknown values and falls back to default`() = runTest {
        prefs.setImportance("bogus")
        assertEquals(NotificationPreferences.DEFAULT_IMPORTANCE, prefs.importance.first())
    }

    @Test
    fun `default reminder offset is 15 minutes`() = runTest {
        assertEquals(900_000L, prefs.defaultReminderOffset.first())
        assertEquals(NotificationPreferences.DEFAULT_REMINDER_OFFSET_MS, prefs.defaultReminderOffset.first())
    }

    @Test
    fun `setDefaultReminderOffset 1 hour persists`() = runTest {
        prefs.setDefaultReminderOffset(3_600_000L)
        assertEquals(3_600_000L, prefs.defaultReminderOffset.first())
    }

    @Test
    fun `OFFSET_NONE persists distinctly so callers can detect opt-out`() = runTest {
        prefs.setDefaultReminderOffset(NotificationPreferences.OFFSET_NONE)
        assertEquals(NotificationPreferences.OFFSET_NONE, prefs.defaultReminderOffset.first())
    }

    @Test
    fun `previous importance starts null until first recorded`() = runTest {
        assertNull(prefs.getPreviousImportanceOnce())
    }

    @Test
    fun `setPreviousImportance round-trips`() = runTest {
        prefs.setPreviousImportance(NotificationPreferences.IMPORTANCE_URGENT)
        assertEquals(NotificationPreferences.IMPORTANCE_URGENT, prefs.getPreviousImportanceOnce())
    }

    @Test
    fun `ALL_IMPORTANCES contains the three documented levels`() {
        assertEquals(3, NotificationPreferences.ALL_IMPORTANCES.size)
        assertTrue(NotificationPreferences.IMPORTANCE_MINIMAL in NotificationPreferences.ALL_IMPORTANCES)
        assertTrue(NotificationPreferences.IMPORTANCE_STANDARD in NotificationPreferences.ALL_IMPORTANCES)
        assertTrue(NotificationPreferences.IMPORTANCE_URGENT in NotificationPreferences.ALL_IMPORTANCES)
    }

    @Test
    fun `ALL_REMINDER_OFFSETS contains every documented option including OFFSET_NONE`() {
        assertTrue(0L in NotificationPreferences.ALL_REMINDER_OFFSETS)
        assertTrue(300_000L in NotificationPreferences.ALL_REMINDER_OFFSETS)
        assertTrue(900_000L in NotificationPreferences.ALL_REMINDER_OFFSETS)
        assertTrue(1_800_000L in NotificationPreferences.ALL_REMINDER_OFFSETS)
        assertTrue(3_600_000L in NotificationPreferences.ALL_REMINDER_OFFSETS)
        assertTrue(86_400_000L in NotificationPreferences.ALL_REMINDER_OFFSETS)
        assertTrue(NotificationPreferences.OFFSET_NONE in NotificationPreferences.ALL_REMINDER_OFFSETS)
    }

    // region Per-type volume / vibration override

    @Test
    fun `per-type defaults follow phone with no overrides`() = runTest {
        assertTrue(prefs.taskRemindersFollowSystem.first())
        assertEquals(false, prefs.taskRemindersVolumeLoud.first())
        assertEquals(false, prefs.taskRemindersVibrationRepeat.first())
        val overrides = prefs.getOverridesForOnce(NotificationOverrideType.TASK_REMINDERS)
        assertEquals(false, overrides.loud)
        assertEquals(false, overrides.repeat)
    }

    @Test
    fun `legacy global override volume seeds per-type loud and follow-system off`() = runTest {
        prefs.setOverrideVolumeEnabled(true)
        // Per-type follow-system inherits "false" because legacy override
        // implies the user previously opted out of phone defaults.
        assertEquals(false, prefs.taskRemindersFollowSystem.first())
        assertEquals(true, prefs.taskRemindersVolumeLoud.first())
        val overrides = prefs.getOverridesForOnce(NotificationOverrideType.TASK_REMINDERS)
        assertEquals(true, overrides.loud)
        assertEquals(false, overrides.repeat)
    }

    @Test
    fun `legacy global repeating vibration seeds per-type repeat and follow-system off`() = runTest {
        prefs.setRepeatingVibrationEnabled(true)
        assertEquals(false, prefs.timerAlertsFollowSystem.first())
        assertEquals(true, prefs.timerAlertsVibrationRepeat.first())
        val overrides = prefs.getOverridesForOnce(NotificationOverrideType.TIMER_ALERTS)
        assertEquals(false, overrides.loud)
        assertEquals(true, overrides.repeat)
    }

    @Test
    fun `explicit per-type write overrides legacy fallback`() = runTest {
        prefs.setOverrideVolumeEnabled(true)
        prefs.setTaskRemindersVolumeLoud(false)
        // Per-type explicit value wins over the legacy global.
        assertEquals(false, prefs.taskRemindersVolumeLoud.first())
    }

    @Test
    fun `follow-system on forces overrides off at resolution`() = runTest {
        prefs.setMedicationRemindersFollowSystem(true)
        prefs.setMedicationRemindersVolumeLoud(true)
        prefs.setMedicationRemindersVibrationRepeat(true)
        // Stored values persist (the user can flip follow-system off to
        // restore them), but the resolved pair is forced to (false, false).
        assertEquals(true, prefs.medicationRemindersVolumeLoud.first())
        val overrides = prefs.getOverridesForOnce(NotificationOverrideType.MEDICATION_REMINDERS)
        assertEquals(false, overrides.loud)
        assertEquals(false, overrides.repeat)
    }

    @Test
    fun `follow-system off surfaces stored overrides`() = runTest {
        prefs.setTimerAlertsFollowSystem(false)
        prefs.setTimerAlertsVolumeLoud(true)
        prefs.setTimerAlertsVibrationRepeat(true)
        val overrides = prefs.getOverridesForOnce(NotificationOverrideType.TIMER_ALERTS)
        assertEquals(true, overrides.loud)
        assertEquals(true, overrides.repeat)
    }

    // endregion
}
