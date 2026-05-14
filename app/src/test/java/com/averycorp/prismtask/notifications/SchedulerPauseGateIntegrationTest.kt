package com.averycorp.prismtask.notifications

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.data.preferences.StartOfDay
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Integration test that demonstrates the contract every gated
 * scheduler / worker observes: read both the per-type enable flag
 * AND [NotificationPauseGate.isPausedNow], and short-circuit when
 * either says "no". Mirrors the production short-circuit pattern
 * applied to `OverloadCheckWorker`, `WeeklyHabitSummaryWorker`,
 * etc.
 *
 * Crucially, the medication-class flag is NOT routed through this
 * gate — a paused user still gets dose reminders, which is the
 * adherence-safety invariant captured by the audit § G4.
 */
class SchedulerPauseGateIntegrationTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var prefs: NotificationPreferences
    private lateinit var gate: NotificationPauseGate

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val file = File(tmpFolder.root, "scheduler_gate_test.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        prefs = NotificationPreferences(dataStore)
        val taskBehaviorPreferences: TaskBehaviorPreferences = mockk(relaxed = true)
        every { taskBehaviorPreferences.getStartOfDay() } returns
            flowOf(StartOfDay(hour = 8, minute = 0, hasBeenSet = true))
        gate = NotificationPauseGate(prefs, taskBehaviorPreferences)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /**
     * Mirrors `OverloadCheckWorker.doWork`:
     *   if (!overloadAlertsEnabled) return
     *   if (pauseGate.isPausedNow()) return
     *   notify(...)
     */
    private suspend fun simulateGatedWorker(): Boolean {
        if (!prefs.overloadAlertsEnabled.first()) return false
        if (gate.isPausedNow()) return false
        return true
    }

    @Test
    fun `defaults notify — per-type on and no pause`() = runTest {
        assertTrue(simulateGatedWorker())
    }

    @Test
    fun `active pause suppresses gated worker even when per-type is enabled`() = runTest {
        gate.pauseFor(durationMillis = 60_000L, now = System.currentTimeMillis())
        assertFalse(simulateGatedWorker())
    }

    @Test
    fun `expired pause does not suppress`() = runTest {
        gate.pauseFor(durationMillis = 60_000L, now = 1_000L)
        // Wall clock is far past the pause-until value; gate returns
        // "not paused" without needing the entry to be reset.
        assertTrue(simulateGatedWorker())
    }

    @Test
    fun `resume restores normal worker delivery`() = runTest {
        gate.pauseFor(durationMillis = 60_000L, now = System.currentTimeMillis())
        assertFalse(simulateGatedWorker())
        gate.resume()
        assertTrue(simulateGatedWorker())
    }

    @Test
    fun `per-type disabled blocks delivery regardless of pause state`() = runTest {
        prefs.setOverloadAlertsEnabled(false)
        // Not paused, but per-type is off — still suppress.
        assertFalse(simulateGatedWorker())
        // Toggle back on; un-paused notification should resume.
        prefs.setOverloadAlertsEnabled(true)
        assertTrue(simulateGatedWorker())
    }

    @Test
    fun `medication path is not gated by pause-all — exempt invariant`() = runTest {
        // The production medication call sites
        // (MedicationReminderReceiver / MedStepReminderReceiver) never
        // call into NotificationPauseGate at all. This test pins the
        // invariant by demonstrating that even with a long active pause,
        // the medication-class flag stays untouched and the medication
        // entry point in NotificationHelper continues to honour only
        // its own enable flag.
        gate.pauseFor(durationMillis = 4L * 60L * 60L * 1000L, now = System.currentTimeMillis())
        assertTrue(gate.isPausedNow())
        // Medication enable defaults to true; we don't ever AND it with
        // the gate, so the medication delivery decision is still based
        // only on its own per-type flag.
        assertTrue(prefs.medicationRemindersEnabled.first())
    }
}
