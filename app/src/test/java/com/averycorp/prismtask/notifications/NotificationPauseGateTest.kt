package com.averycorp.prismtask.notifications

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.data.preferences.StartOfDay
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [NotificationPauseGate] — the single seam every
 * non-medication scheduler/worker consults before posting an
 * MH-first-gated notification.
 *
 * Tests the pure `isPaused` predicate, the persistence round-trip,
 * the duration helpers, and the SoD-aware "until tomorrow morning"
 * resolution.
 */
class NotificationPauseGateTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var prefs: NotificationPreferences
    private lateinit var taskBehaviorPreferences: TaskBehaviorPreferences
    private lateinit var gate: NotificationPauseGate

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val file = File(tmpFolder.root, "pause_gate_test.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        prefs = NotificationPreferences(dataStore)

        taskBehaviorPreferences = mockk(relaxed = true)
        // Default SoD = 8 AM, hasBeenSet = true. Individual tests override.
        every { taskBehaviorPreferences.getStartOfDay() } returns
            flowOf(StartOfDay(hour = 8, minute = 0, hasBeenSet = true))

        gate = NotificationPauseGate(
            notificationPreferences = prefs,
            taskBehaviorPreferences = taskBehaviorPreferences
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `isPaused returns false when pauseUntil is zero`() {
        assertFalse(NotificationPauseGate.isPaused(pauseUntilEpochMs = 0L, now = 1_000_000L))
    }

    @Test
    fun `isPaused returns false when pauseUntil is in the past`() {
        assertFalse(NotificationPauseGate.isPaused(pauseUntilEpochMs = 500_000L, now = 1_000_000L))
    }

    @Test
    fun `isPaused returns false when pauseUntil equals now`() {
        // Boundary: equality is not paused — the gate is strict-greater.
        assertFalse(NotificationPauseGate.isPaused(pauseUntilEpochMs = 1_000_000L, now = 1_000_000L))
    }

    @Test
    fun `isPaused returns true when pauseUntil is strictly in the future`() {
        assertTrue(NotificationPauseGate.isPaused(pauseUntilEpochMs = 1_000_001L, now = 1_000_000L))
    }

    @Test
    fun `default isPausedNow is false on a fresh preferences store`() = runTest {
        assertFalse(gate.isPausedNow(now = System.currentTimeMillis()))
    }

    @Test
    fun `pauseFor writes expiry as now plus duration`() = runTest {
        val now = 10_000_000L
        gate.pauseFor(durationMillis = 60_000L, now = now)
        assertEquals(10_060_000L, gate.pauseUntilOnce())
        assertTrue(gate.isPausedNow(now = now + 1_000L))
        assertFalse(gate.isPausedNow(now = 11_000_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `pauseFor rejects zero duration`() = runTest {
        gate.pauseFor(durationMillis = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `pauseFor rejects negative duration`() = runTest {
        gate.pauseFor(durationMillis = -1L)
    }

    @Test
    fun `resume zeros out the expiry`() = runTest {
        gate.pauseFor(durationMillis = 60_000L, now = 5_000L)
        assertNotEquals(0L, gate.pauseUntilOnce())
        gate.resume()
        assertEquals(0L, gate.pauseUntilOnce())
        assertFalse(gate.isPausedNow(now = 5_001L))
    }

    @Test
    fun `pauseUntilTomorrowMorning lands strictly after now`() = runTest {
        val now = System.currentTimeMillis()
        gate.pauseUntilTomorrowMorning(now = now)
        val until = gate.pauseUntilOnce()
        assertTrue("Expected until ($until) > now ($now)", until > now)
    }

    @Test
    fun `pauseUntilTomorrowMorning honors a custom SoD hour`() = runTest {
        // SoD = 4 AM. now = today 02:00 local — pre-SoD, so the "current
        // logical day" started yesterday 04:00 and the next boundary is
        // today 04:00, which is in the future. Pause until that.
        every { taskBehaviorPreferences.getStartOfDay() } returns
            flowOf(StartOfDay(hour = 4, minute = 0, hasBeenSet = true))

        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 2)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis
        gate.pauseUntilTomorrowMorning(now = now)
        val until = gate.pauseUntilOnce()
        // The next 04:00 should land within 24 hours of `now`.
        assertTrue("Expected until > now", until > now)
        assertTrue("Expected until within 24 h of now", until <= now + 24L * 60 * 60 * 1000)
    }

    @Test
    fun `pause then resume round-trip is idempotent`() = runTest {
        gate.pauseFor(durationMillis = 60_000L, now = 1_000L)
        gate.resume()
        gate.resume() // double resume is a no-op
        assertEquals(0L, gate.pauseUntilOnce())
    }

    @Test
    fun `stale pause expiry compares as not paused without explicit clear`() = runTest {
        // Past expiry persists, but isPausedNow returns false based on
        // wall-clock comparison — so we don't need a worker to zero it.
        gate.pauseFor(durationMillis = 60_000L, now = 100_000L)
        assertEquals(160_000L, gate.pauseUntilOnce())
        assertFalse(gate.isPausedNow(now = 999_999_999L))
    }
}
