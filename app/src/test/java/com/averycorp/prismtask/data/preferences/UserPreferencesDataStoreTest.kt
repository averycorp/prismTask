package com.averycorp.prismtask.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.averycorp.prismtask.domain.model.AutoDueDate
import com.averycorp.prismtask.domain.model.StartOfWeek
import com.averycorp.prismtask.domain.model.SwipeAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [UserPreferencesDataStore]. Uses [PreferenceDataStoreFactory] with
 * a temp file so tests run as pure JVM without Android/Robolectric.
 */
class UserPreferencesDataStoreTest {
    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var prefs: UserPreferencesDataStore

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val file = File(tmpFolder.root, "user_prefs_test.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        prefs = UserPreferencesDataStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `appearance defaults are compact off borders on radius twelve`() = runTest {
        val a = prefs.appearanceFlow.first()
        assertFalse(a.compactMode)
        assertTrue(a.showTaskCardBorders)
        assertEquals(12, a.cardCornerRadius)
    }

    @Test
    fun `appearance round trip persists all fields`() = runTest {
        prefs.setAppearance(AppearancePrefs(compactMode = true, showTaskCardBorders = false, cardCornerRadius = 20))
        val a = prefs.appearanceFlow.first()
        assertTrue(a.compactMode)
        assertFalse(a.showTaskCardBorders)
        assertEquals(20, a.cardCornerRadius)
    }

    @Test
    fun `card corner radius is clamped to 0 24 range`() = runTest {
        prefs.setCardCornerRadius(-5)
        assertEquals(0, prefs.appearanceFlow.first().cardCornerRadius)
        prefs.setCardCornerRadius(50)
        assertEquals(24, prefs.appearanceFlow.first().cardCornerRadius)
    }

    @Test
    fun `swipe defaults are complete right and delete left`() = runTest {
        val s = prefs.swipeFlow.first()
        assertEquals(SwipeAction.COMPLETE, s.right)
        assertEquals(SwipeAction.DELETE, s.left)
    }

    @Test
    fun `swipe round trip persists enum actions`() = runTest {
        prefs.setSwipe(SwipePrefs(right = SwipeAction.RESCHEDULE, left = SwipeAction.ARCHIVE))
        val s = prefs.swipeFlow.first()
        assertEquals(SwipeAction.RESCHEDULE, s.right)
        assertEquals(SwipeAction.ARCHIVE, s.left)
    }

    @Test
    fun `task defaults are all empty or default on initial read`() = runTest {
        val d = prefs.taskDefaultsFlow.first()
        assertEquals(0, d.defaultPriority)
        assertEquals(-1L, d.defaultReminderOffset)
        assertNull(d.defaultProjectId)
        assertEquals(StartOfWeek.MONDAY, d.startOfWeek)
        assertNull(d.defaultDuration)
        assertEquals(AutoDueDate.NONE, d.autoSetDueDate)
        assertFalse(d.smartDefaultsEnabled)
    }

    @Test
    fun `task defaults round trip with nullable project and duration populated`() = runTest {
        prefs.setTaskDefaults(
            TaskDefaults(
                defaultPriority = 3,
                defaultReminderOffset = 900_000L,
                defaultProjectId = 42L,
                startOfWeek = StartOfWeek.SUNDAY,
                defaultDuration = 30,
                autoSetDueDate = AutoDueDate.TOMORROW,
                smartDefaultsEnabled = true
            )
        )
        val d = prefs.taskDefaultsFlow.first()
        assertEquals(3, d.defaultPriority)
        assertEquals(900_000L, d.defaultReminderOffset)
        assertEquals(42L, d.defaultProjectId)
        assertEquals(StartOfWeek.SUNDAY, d.startOfWeek)
        assertEquals(30, d.defaultDuration)
        assertEquals(AutoDueDate.TOMORROW, d.autoSetDueDate)
        assertTrue(d.smartDefaultsEnabled)
    }

    @Test
    fun `task defaults nullable project and duration stay null when not set`() = runTest {
        prefs.setTaskDefaults(TaskDefaults(defaultPriority = 1))
        val d = prefs.taskDefaultsFlow.first()
        assertNull(d.defaultProjectId)
        assertNull(d.defaultDuration)
        assertEquals(1, d.defaultPriority)
    }

    @Test
    fun `priority is clamped to 0 to 4 range`() = runTest {
        prefs.setTaskDefaults(TaskDefaults(defaultPriority = 99))
        assertEquals(4, prefs.taskDefaultsFlow.first().defaultPriority)
    }

    @Test
    fun `quick add defaults show confirmation true auto assign false`() = runTest {
        val q = prefs.quickAddFlow.first()
        assertTrue(q.showConfirmation)
        assertFalse(q.autoAssignProject)
    }

    @Test
    fun `quick add round trip persists both flags`() = runTest {
        prefs.setQuickAdd(QuickAddPrefs(showConfirmation = false, autoAssignProject = true))
        val q = prefs.quickAddFlow.first()
        assertFalse(q.showConfirmation)
        assertTrue(q.autoAssignProject)
    }

    @Test
    fun `enum serialization survives unknown or missing values by falling back to default`() = runTest {
        // Unknown enum names should fall back to defaults.
        assertEquals(SwipeAction.COMPLETE, SwipeAction.fromName("NONSENSE"))
        assertEquals(SwipeAction.COMPLETE, SwipeAction.fromName(null))
        assertEquals(StartOfWeek.MONDAY, StartOfWeek.fromName("X"))
        assertEquals(AutoDueDate.NONE, AutoDueDate.fromName(null))
    }

    @Test
    fun `clear all resets all keys to defaults`() = runTest {
        prefs.setAppearance(AppearancePrefs(compactMode = true, cardCornerRadius = 20))
        prefs.setSwipe(SwipePrefs(right = SwipeAction.FLAG, left = SwipeAction.NONE))
        prefs.clearAll()
        val snapshot = prefs.allFlow.first()
        assertFalse(snapshot.appearance.compactMode)
        assertEquals(12, snapshot.appearance.cardCornerRadius)
        assertEquals(SwipeAction.COMPLETE, snapshot.swipe.right)
        assertEquals(SwipeAction.DELETE, snapshot.swipe.left)
    }

    @Test
    fun `combined snapshot flow emits all four groups`() = runTest {
        prefs.setCompactMode(true)
        prefs.setSwipeRight(SwipeAction.FLAG)
        prefs.setSmartDefaultsEnabled(true)
        prefs.setQuickAdd(QuickAddPrefs(showConfirmation = false, autoAssignProject = true))
        val snap = prefs.allFlow.first()
        assertTrue(snap.appearance.compactMode)
        assertEquals(SwipeAction.FLAG, snap.swipe.right)
        assertTrue(snap.taskDefaults.smartDefaultsEnabled)
        assertFalse(snap.quickAdd.showConfirmation)
        assertTrue(snap.quickAdd.autoAssignProject)
    }

    @Test
    fun `medication reminder mode default is clock and 240 minutes`() = runTest {
        val p = prefs.medicationReminderModeFlow.first()
        assertEquals(MedicationReminderMode.CLOCK, p.mode)
        assertEquals(240, p.intervalDefaultMinutes)
    }

    @Test
    fun `medication reminder mode round trip persists mode and interval`() = runTest {
        prefs.setMedicationReminderMode(
            MedicationReminderModePrefs(
                mode = MedicationReminderMode.INTERVAL,
                intervalDefaultMinutes = 360
            )
        )
        val p = prefs.medicationReminderModeFlow.first()
        assertEquals(MedicationReminderMode.INTERVAL, p.mode)
        assertEquals(360, p.intervalDefaultMinutes)
    }

    @Test
    fun `medication reminder mode interval is clamped to 60 1440 range`() = runTest {
        prefs.setMedicationReminderMode(
            MedicationReminderModePrefs(MedicationReminderMode.INTERVAL, 5)
        )
        assertEquals(60, prefs.medicationReminderModeFlow.first().intervalDefaultMinutes)
        prefs.setMedicationReminderMode(
            MedicationReminderModePrefs(MedicationReminderMode.INTERVAL, 9999)
        )
        assertEquals(1440, prefs.medicationReminderModeFlow.first().intervalDefaultMinutes)
    }

    @Test
    fun `medication reminder mode falls back to CLOCK on unknown enum value`() = runTest {
        // Round-trips through fromName
        assertEquals(MedicationReminderMode.CLOCK, MedicationReminderMode.fromName(null))
        assertEquals(MedicationReminderMode.CLOCK, MedicationReminderMode.fromName("BOGUS"))
        assertEquals(MedicationReminderMode.INTERVAL, MedicationReminderMode.fromName("INTERVAL"))
    }

    // region AI features opt-out (PII egress audit, 2026-04-26) ---------

    @Test
    fun `ai features default to enabled true`() = runTest {
        val p = prefs.aiFeaturePrefsFlow.first()
        assertTrue(p.enabled)
    }

    @Test
    fun `set ai features enabled false persists round trip`() = runTest {
        prefs.setAiFeaturesEnabled(false)
        assertFalse(prefs.aiFeaturePrefsFlow.first().enabled)
    }

    @Test
    fun `set ai features enabled true after disable persists round trip`() = runTest {
        prefs.setAiFeaturesEnabled(false)
        prefs.setAiFeaturesEnabled(true)
        assertTrue(prefs.aiFeaturePrefsFlow.first().enabled)
    }

    @Test
    fun `is ai features enabled blocking returns current value`() {
        // Default is true.
        assertTrue(prefs.isAiFeaturesEnabledBlocking())
        // After disabling, the blocking getter reflects the change — this
        // is the contract that AiFeatureGateInterceptor depends on so that
        // every outbound request reflects the latest user preference, with
        // no stale-cache window.
        kotlinx.coroutines.runBlocking { prefs.setAiFeaturesEnabled(false) }
        assertFalse(prefs.isAiFeaturesEnabledBlocking())
    }

    // endregion ----------------------------------------------------------

    // region Per-feature AI opt-ins (F3 low-risk bundle) ----------------

    @Test
    fun `per-feature ai prefs all default to true`() = runTest {
        val p = prefs.perFeatureAiPrefsFlow.first()
        assertTrue(p.chatEnabled)
        assertTrue(p.dailyBriefingEnabled)
        assertTrue(p.smartPomodoroEnabled)
        assertTrue(p.weeklyPlannerEnabled)
        assertTrue(p.morningCheckInEnabled)
    }

    @Test
    fun `set ai chat enabled false persists round trip`() = runTest {
        prefs.setAiChatEnabled(false)
        val p = prefs.perFeatureAiPrefsFlow.first()
        assertFalse(p.chatEnabled)
        // Other per-feature prefs unaffected.
        assertTrue(p.dailyBriefingEnabled)
        assertTrue(p.smartPomodoroEnabled)
        assertTrue(p.weeklyPlannerEnabled)
    }

    @Test
    fun `set daily briefing enabled false persists round trip`() = runTest {
        prefs.setAiDailyBriefingEnabled(false)
        assertFalse(prefs.perFeatureAiPrefsFlow.first().dailyBriefingEnabled)
    }

    @Test
    fun `set smart pomodoro enabled false persists round trip`() = runTest {
        prefs.setAiSmartPomodoroEnabled(false)
        assertFalse(prefs.perFeatureAiPrefsFlow.first().smartPomodoroEnabled)
    }

    @Test
    fun `set weekly planner enabled false persists round trip`() = runTest {
        prefs.setAiWeeklyPlannerEnabled(false)
        assertFalse(prefs.perFeatureAiPrefsFlow.first().weeklyPlannerEnabled)
    }

    @Test
    fun `set morning checkin enabled false persists round trip`() = runTest {
        prefs.setAiMorningCheckInEnabled(false)
        val p = prefs.perFeatureAiPrefsFlow.first()
        assertFalse(p.morningCheckInEnabled)
        // Other per-feature prefs unaffected.
        assertTrue(p.chatEnabled)
        assertTrue(p.dailyBriefingEnabled)
        assertTrue(p.smartPomodoroEnabled)
        assertTrue(p.weeklyPlannerEnabled)
    }

    @Test
    fun `per-feature ai prefs are independent of master toggle`() = runTest {
        // Master OFF, per-feature ON: master is the privacy gate, but the
        // per-feature pref keeps its own state for the UI layer.
        prefs.setAiFeaturesEnabled(false)
        val p = prefs.perFeatureAiPrefsFlow.first()
        assertTrue(p.chatEnabled)
        assertTrue(p.dailyBriefingEnabled)
        // Master toggle independent.
        assertFalse(prefs.aiFeaturePrefsFlow.first().enabled)
    }

    // endregion ----------------------------------------------------------
}
