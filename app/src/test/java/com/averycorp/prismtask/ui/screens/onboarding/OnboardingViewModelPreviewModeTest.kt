package com.averycorp.prismtask.ui.screens.onboarding

import com.averycorp.prismtask.data.preferences.A11yPreferences
import com.averycorp.prismtask.data.preferences.HabitListPreferences
import com.averycorp.prismtask.data.preferences.NdPreferencesDataStore
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.data.preferences.OnboardingPreferences
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.preferences.ThemePreferences
import com.averycorp.prismtask.data.preferences.TourCardPreferences
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.preferences.VoicePreferences
import com.averycorp.prismtask.data.remote.AuthManager
import com.averycorp.prismtask.data.remote.CanonicalOnboardingSync
import com.averycorp.prismtask.data.remote.SyncService
import com.averycorp.prismtask.data.remote.sync.PrismSyncLogger
import com.averycorp.prismtask.data.repository.SelfCareRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies the admin "Show Tutorial" replay path: with [OnboardingViewModel.setPreviewMode]
 * enabled, every mutating method must short-circuit so the visual flow runs end to
 * end without changing the account. See [SettingsScreen]'s admin entry point and the
 * `?preview={preview}` arg on `PrismTaskRoute.Onboarding`.
 *
 * The OnboardingViewModel constructor reads only `authManager.isSignedIn`/`currentUser`
 * eagerly; every other dependency is consumed via `Flow.stateIn(WhileSubscribed)`,
 * which never collects upstream when there are no subscribers. So the StateFlow
 * mocks (returned by `mockk(relaxed = true)`) are safe to leave unstubbed for
 * the read paths these tests exercise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelPreviewModeTest {
    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var onboardingPreferences: OnboardingPreferences
    private lateinit var themePreferences: ThemePreferences
    private lateinit var ndPreferencesDataStore: NdPreferencesDataStore
    private lateinit var authManager: AuthManager
    private lateinit var syncService: SyncService
    private lateinit var taskRepository: TaskRepository
    private lateinit var selfCareRepository: SelfCareRepository
    private lateinit var userPreferencesDataStore: UserPreferencesDataStore
    private lateinit var taskBehaviorPreferences: TaskBehaviorPreferences
    private lateinit var canonicalOnboardingSync: CanonicalOnboardingSync
    private lateinit var habitListPreferences: HabitListPreferences
    private lateinit var a11yPreferences: A11yPreferences
    private lateinit var notificationPreferences: NotificationPreferences
    private lateinit var voicePreferences: VoicePreferences
    private lateinit var logger: PrismSyncLogger
    private lateinit var tourCardPreferences: TourCardPreferences

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        onboardingPreferences = mockk(relaxed = true)
        themePreferences = mockk(relaxed = true)
        ndPreferencesDataStore = mockk(relaxed = true)
        authManager = mockk(relaxed = true)
        syncService = mockk(relaxed = true)
        taskRepository = mockk(relaxed = true)
        selfCareRepository = mockk(relaxed = true)
        userPreferencesDataStore = mockk(relaxed = true)
        taskBehaviorPreferences = mockk(relaxed = true)
        canonicalOnboardingSync = mockk(relaxed = true)
        habitListPreferences = mockk(relaxed = true)
        a11yPreferences = mockk(relaxed = true)
        notificationPreferences = mockk(relaxed = true)
        voicePreferences = mockk(relaxed = true)
        logger = mockk(relaxed = true)
        tourCardPreferences = mockk(relaxed = true)

        // The init block reads .value on these StateFlows synchronously.
        every { authManager.isSignedIn } returns MutableStateFlow(false)
        every { authManager.currentUser } returns MutableStateFlow(null)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() = OnboardingViewModel(
        onboardingPreferences = onboardingPreferences,
        themePreferences = themePreferences,
        ndPreferencesDataStore = ndPreferencesDataStore,
        authManager = authManager,
        syncService = syncService,
        taskRepository = taskRepository,
        selfCareRepository = selfCareRepository,
        userPreferencesDataStore = userPreferencesDataStore,
        taskBehaviorPreferences = taskBehaviorPreferences,
        canonicalOnboardingSync = canonicalOnboardingSync,
        habitListPreferences = habitListPreferences,
        a11yPreferences = a11yPreferences,
        notificationPreferences = notificationPreferences,
        voicePreferences = voicePreferences,
        logger = logger,
        tourCardPreferences = tourCardPreferences
    )

    @Test
    fun `preview mode flag defaults to false`() {
        val vm = newViewModel()
        assertFalse(vm.isPreviewMode.value)
    }

    @Test
    fun `setPreviewMode(true) flips the flag`() {
        val vm = newViewModel()
        vm.setPreviewMode(true)
        assertTrue(vm.isPreviewMode.value)
    }

    @Test
    fun `preview mode skips theme writes`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.setPreviewMode(true)

        vm.setThemeMode("dark")
        vm.setAccentColor("#FF00FF")
        advanceUntilIdle()

        coVerify(exactly = 0) { themePreferences.setThemeMode(any()) }
        coVerify(exactly = 0) { themePreferences.setAccentColor(any()) }
    }

    @Test
    fun `preview mode skips task creation`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.setPreviewMode(true)

        vm.createQuickTask("Replay tutorial task")
        advanceUntilIdle()

        coVerify(exactly = 0) { taskRepository.addTask(title = any()) }
    }

    @Test
    fun `preview mode skips ND, habit list, a11y, voice, AI, and forgiveness writes`() =
        runTest(dispatcher) {
            val vm = newViewModel()
            vm.setPreviewMode(true)

            vm.setAdhdMode(true)
            vm.setCalmMode(true)
            vm.setFocusReleaseMode(true)
            vm.setSelfCareEnabled(false)
            vm.setMedicationEnabled(false)
            vm.setSchoolEnabled(false)
            vm.setHouseworkEnabled(false)
            vm.setLeisureEnabled(false)
            vm.setReduceMotion(true)
            vm.setHighContrast(true)
            vm.setLargeTouchTargets(true)
            vm.setVoiceInputEnabled(false)
            vm.setAiFeaturesEnabled(false)
            vm.setForgivenessStreaksEnabled(false)
            vm.setStreakMaxMissedDays(7)
            advanceUntilIdle()

            coVerify(exactly = 0) { ndPreferencesDataStore.setAdhdMode(any()) }
            coVerify(exactly = 0) { ndPreferencesDataStore.setCalmMode(any()) }
            coVerify(exactly = 0) { ndPreferencesDataStore.setFocusReleaseMode(any()) }
            coVerify(exactly = 0) { habitListPreferences.setSelfCareEnabled(any()) }
            coVerify(exactly = 0) { habitListPreferences.setMedicationEnabled(any()) }
            coVerify(exactly = 0) { habitListPreferences.setSchoolEnabled(any()) }
            coVerify(exactly = 0) { habitListPreferences.setHouseworkEnabled(any()) }
            coVerify(exactly = 0) { habitListPreferences.setLeisureEnabled(any()) }
            coVerify(exactly = 0) { habitListPreferences.setStreakMaxMissedDays(any()) }
            coVerify(exactly = 0) { a11yPreferences.setReduceMotion(any()) }
            coVerify(exactly = 0) { a11yPreferences.setHighContrast(any()) }
            coVerify(exactly = 0) { a11yPreferences.setLargeTouchTargets(any()) }
            coVerify(exactly = 0) { voicePreferences.setVoiceInputEnabled(any()) }
            coVerify(exactly = 0) { userPreferencesDataStore.setAiFeaturesEnabled(any()) }
            coVerify(exactly = 0) { userPreferencesDataStore.setForgivenessPrefs(any()) }
        }

    @Test
    fun `preview mode skips notification, widget, and start-of-day writes`() =
        runTest(dispatcher) {
            val vm = newViewModel()
            vm.setPreviewMode(true)

            vm.setDailyBriefingEnabled(false)
            vm.setEveningSummaryEnabled(false)
            vm.setWeeklySummaryEnabled(false)
            vm.setOverloadAlertsEnabled(false)
            vm.setStreakAlertsEnabled(false)
            vm.setReengagementEnabled(false)
            vm.setTaskRemindersEnabled(false)
            vm.setTimerAlertsEnabled(false)
            vm.setMedicationRemindersEnabled(false)
            vm.setWidgetThemeFollowsApp(false)
            vm.setStartOfDay(hour = 7, minute = 30)
            advanceUntilIdle()

            coVerify(exactly = 0) { notificationPreferences.setDailyBriefingEnabled(any()) }
            coVerify(exactly = 0) { notificationPreferences.setEveningSummaryEnabled(any()) }
            coVerify(exactly = 0) { notificationPreferences.setWeeklySummaryEnabled(any()) }
            coVerify(exactly = 0) { notificationPreferences.setWeeklyTaskSummaryEnabled(any()) }
            coVerify(exactly = 0) { notificationPreferences.setOverloadAlertsEnabled(any()) }
            coVerify(exactly = 0) { notificationPreferences.setStreakAlertsEnabled(any()) }
            coVerify(exactly = 0) { notificationPreferences.setReengagementEnabled(any()) }
            coVerify(exactly = 0) { notificationPreferences.setTaskRemindersEnabled(any()) }
            coVerify(exactly = 0) { notificationPreferences.setTimerAlertsEnabled(any()) }
            coVerify(exactly = 0) { notificationPreferences.setMedicationRemindersEnabled(any()) }
            coVerify(exactly = 0) { themePreferences.setWidgetThemeOverride(any()) }
            coVerify(exactly = 0) { taskBehaviorPreferences.setStartOfDay(any(), any()) }
        }

    @Test
    fun `preview mode skips completeOnboarding writes`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.setPreviewMode(true)

        vm.completeOnboarding()
        advanceUntilIdle()

        coVerify(exactly = 0) { onboardingPreferences.setOnboardingCompleted(any<Long>()) }
        coVerify(exactly = 0) { tourCardPreferences.markEligible() }
        coVerify(exactly = 0) { canonicalOnboardingSync.writeCompletedAt(any(), any()) }
        coVerify(exactly = 0) { selfCareRepository.seedSelfCareSteps(any(), any()) }
    }

    @Test
    fun `non-preview mode still propagates writes (sanity check)`() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.setThemeMode("dark")
        advanceUntilIdle()

        coVerify(exactly = 1) { themePreferences.setThemeMode("dark") }
    }

    // ── Preview-friendly UI: visible StateFlows must reflect admin taps even
    //     though DataStore writes are gated. The bug this guards against:
    //     before this fix the Switch / Slider / Clock controls on the admin
    //     "Show Tutorial" replay were bound to upstream-only StateFlows, so
    //     a tap fired the gated setter, the setter no-op'd, the StateFlow
    //     never re-emitted, and the thumb stayed put. Admins reported
    //     "none of the switches in onboarding work."

    @Test
    fun `preview mode setSelfCareEnabled flips visible state without writing`() =
        runTest(dispatcher) {
            val vm = newViewModel()
            vm.setPreviewMode(true)
            val before = vm.selfCareEnabled.value

            vm.setSelfCareEnabled(!before)
            advanceUntilIdle()

            assertEquals(!before, vm.selfCareEnabled.value)
            coVerify(exactly = 0) { habitListPreferences.setSelfCareEnabled(any()) }
        }

    @Test
    fun `preview mode setAdhdMode flips visible state without writing`() =
        runTest(dispatcher) {
            val vm = newViewModel()
            vm.setPreviewMode(true)
            val before = vm.adhdMode.value

            vm.setAdhdMode(!before)
            advanceUntilIdle()

            assertEquals(!before, vm.adhdMode.value)
            coVerify(exactly = 0) { ndPreferencesDataStore.setAdhdMode(any()) }
        }

    @Test
    fun `preview mode setForgivenessStreaksEnabled flips visible state without writing`() =
        runTest(dispatcher) {
            val vm = newViewModel()
            vm.setPreviewMode(true)

            vm.setForgivenessStreaksEnabled(false)
            advanceUntilIdle()

            assertFalse(vm.forgivenessStreaksEnabled.value)
            coVerify(exactly = 0) { userPreferencesDataStore.setForgivenessPrefs(any()) }
        }

    @Test
    fun `preview mode setStreakMaxMissedDays moves slider without writing`() =
        runTest(dispatcher) {
            val vm = newViewModel()
            vm.setPreviewMode(true)

            vm.setStreakMaxMissedDays(7)
            advanceUntilIdle()

            assertEquals(7, vm.streakMaxMissedDays.value)
            coVerify(exactly = 0) { habitListPreferences.setStreakMaxMissedDays(any()) }
        }

    @Test
    fun `preview mode setStartOfDay moves clock hands without writing`() =
        runTest(dispatcher) {
            val vm = newViewModel()
            vm.setPreviewMode(true)

            vm.setStartOfDay(hour = 7, minute = 30)
            advanceUntilIdle()

            assertEquals(7, vm.startOfDayHour.value)
            assertEquals(30, vm.startOfDayMinute.value)
            coVerify(exactly = 0) { taskBehaviorPreferences.setStartOfDay(any(), any()) }
        }

    @Test
    fun `preview mode setThemeMode updates picker selection without writing`() =
        runTest(dispatcher) {
            val vm = newViewModel()
            vm.setPreviewMode(true)

            vm.setThemeMode("dark")
            advanceUntilIdle()

            assertEquals("dark", vm.themeMode.value)
            coVerify(exactly = 0) { themePreferences.setThemeMode(any()) }
        }

    // ── Mental-Health-First § G6 — tuning step ──────────────────────────────

    @Test
    fun `toggleTuningOption adds and removes selections`() {
        val vm = newViewModel()
        assertTrue(vm.tuningSelections.value.isEmpty())

        vm.toggleTuningOption(OnboardingPreferenceMapper.TuningOption.LOSE_TRACK_OF_TIME)
        assertEquals(
            setOf(OnboardingPreferenceMapper.TuningOption.LOSE_TRACK_OF_TIME),
            vm.tuningSelections.value
        )

        vm.toggleTuningOption(OnboardingPreferenceMapper.TuningOption.LOW_ENERGY_DAYS)
        assertEquals(
            setOf(
                OnboardingPreferenceMapper.TuningOption.LOSE_TRACK_OF_TIME,
                OnboardingPreferenceMapper.TuningOption.LOW_ENERGY_DAYS
            ),
            vm.tuningSelections.value
        )

        vm.toggleTuningOption(OnboardingPreferenceMapper.TuningOption.LOSE_TRACK_OF_TIME)
        assertEquals(
            setOf(OnboardingPreferenceMapper.TuningOption.LOW_ENERGY_DAYS),
            vm.tuningSelections.value
        )
    }

    @Test
    fun `toggling NONE_OF_THESE clears the other selections`() {
        val vm = newViewModel()
        vm.toggleTuningOption(OnboardingPreferenceMapper.TuningOption.OVERWHELMED_BY_LONG_LISTS)
        vm.toggleTuningOption(OnboardingPreferenceMapper.TuningOption.LOSE_TRACK_OF_TIME)
        assertEquals(2, vm.tuningSelections.value.size)

        vm.toggleTuningOption(OnboardingPreferenceMapper.TuningOption.NONE_OF_THESE)
        assertEquals(
            setOf(OnboardingPreferenceMapper.TuningOption.NONE_OF_THESE),
            vm.tuningSelections.value
        )
    }

    @Test
    fun `toggling another option after NONE_OF_THESE clears NONE_OF_THESE`() {
        val vm = newViewModel()
        vm.toggleTuningOption(OnboardingPreferenceMapper.TuningOption.NONE_OF_THESE)
        assertEquals(
            setOf(OnboardingPreferenceMapper.TuningOption.NONE_OF_THESE),
            vm.tuningSelections.value
        )

        vm.toggleTuningOption(OnboardingPreferenceMapper.TuningOption.OVER_POLISH)
        // NONE_OF_THESE should be gone; OVER_POLISH alone.
        assertEquals(
            setOf(OnboardingPreferenceMapper.TuningOption.OVER_POLISH),
            vm.tuningSelections.value
        )
    }

    @Test
    fun `applyTuningSelections with skip true never writes`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.toggleTuningOption(OnboardingPreferenceMapper.TuningOption.LOSE_TRACK_OF_TIME)

        vm.applyTuningSelections(skip = true)
        advanceUntilIdle()

        coVerify(exactly = 0) { ndPreferencesDataStore.setAdhdMode(any()) }
        coVerify(exactly = 0) { ndPreferencesDataStore.setCheckInIntervalMinutes(any()) }
        coVerify(exactly = 0) { userPreferencesDataStore.setForgivenessPrefs(any()) }
        coVerify(exactly = 0) { userPreferencesDataStore.setCompactMode(any()) }
        coVerify(exactly = 0) { onboardingPreferences.setRestDayPrimed(any()) }
    }

    @Test
    fun `applyTuningSelections with empty selection is a no-op`() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.applyTuningSelections(skip = false)
        advanceUntilIdle()

        coVerify(exactly = 0) { ndPreferencesDataStore.setAdhdMode(any()) }
        coVerify(exactly = 0) { ndPreferencesDataStore.setCalmMode(any()) }
        coVerify(exactly = 0) { ndPreferencesDataStore.setFocusReleaseMode(any()) }
        coVerify(exactly = 0) { userPreferencesDataStore.setCompactMode(any()) }
        coVerify(exactly = 0) { onboardingPreferences.setRestDayPrimed(any()) }
    }

    @Test
    fun `applyTuningSelections with NONE_OF_THESE is a no-op`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.toggleTuningOption(OnboardingPreferenceMapper.TuningOption.NONE_OF_THESE)

        vm.applyTuningSelections(skip = false)
        advanceUntilIdle()

        coVerify(exactly = 0) { ndPreferencesDataStore.setAdhdMode(any()) }
        coVerify(exactly = 0) { userPreferencesDataStore.setCompactMode(any()) }
        coVerify(exactly = 0) { onboardingPreferences.setRestDayPrimed(any()) }
    }

    @Test
    fun `applyTuningSelections writes check-in cadence for lose-track-of-time and leaves adhdMode untouched`() =
        runTest(dispatcher) {
            // Post-`fix/onboarding-tuning-mode-cascade` (audit findings #2 + #6):
            // TuningPage no longer cascades into parent ND-mode flags.
            // BrainModePage owns `adhdMode`; TuningPage only sets the
            // cadence sub-knob.
            val vm = newViewModel()
            vm.toggleTuningOption(OnboardingPreferenceMapper.TuningOption.LOSE_TRACK_OF_TIME)

            vm.applyTuningSelections(skip = false)
            advanceUntilIdle()

            coVerify(exactly = 1) { ndPreferencesDataStore.setCheckInIntervalMinutes(25) }
            // Parent ND-mode flags are intentionally NOT written.
            coVerify(exactly = 0) { ndPreferencesDataStore.setAdhdMode(any()) }
            coVerify(exactly = 0) { ndPreferencesDataStore.setCalmMode(any()) }
            coVerify(exactly = 0) { ndPreferencesDataStore.setFocusReleaseMode(any()) }
            coVerify(exactly = 0) { userPreferencesDataStore.setCompactMode(any()) }
        }

    @Test
    fun `applyTuningSelections writes rest-day priming for low-energy and leaves forgiveness untouched`() =
        runTest(dispatcher) {
            // Post-`fix/onboarding-tuning-mode-cascade` (audit finding #6):
            // HabitsPage owns `ForgivenessPrefs.enabled`; TuningPage only
            // sets the rest-day priming flag.
            val vm = newViewModel()
            vm.toggleTuningOption(OnboardingPreferenceMapper.TuningOption.LOW_ENERGY_DAYS)

            vm.applyTuningSelections(skip = false)
            advanceUntilIdle()

            coVerify(exactly = 1) { onboardingPreferences.setRestDayPrimed(true) }
            // Forgiveness pref is intentionally NOT written.
            coVerify(exactly = 0) { userPreferencesDataStore.setForgivenessPrefs(any()) }
        }

    @Test
    fun `applyTuningSelections writes compact mode for overwhelmed-by-long-lists`() =
        runTest(dispatcher) {
            val vm = newViewModel()
            vm.toggleTuningOption(OnboardingPreferenceMapper.TuningOption.OVERWHELMED_BY_LONG_LISTS)

            vm.applyTuningSelections(skip = false)
            advanceUntilIdle()

            coVerify(exactly = 1) { userPreferencesDataStore.setCompactMode(true) }
            coVerify(exactly = 0) { ndPreferencesDataStore.setAdhdMode(any()) }
            coVerify(exactly = 0) { ndPreferencesDataStore.setCalmMode(any()) }
            coVerify(exactly = 0) { ndPreferencesDataStore.setFocusReleaseMode(any()) }
        }

    @Test
    fun `applyTuningSelections writes reduce-animations and muted-palette for fewer-animations and leaves calmMode untouched`() =
        runTest(dispatcher) {
            // Post-`fix/onboarding-tuning-mode-cascade` (audit finding #2):
            // BrainModePage owns `calmMode`; TuningPage only sets the two
            // sub-flags.
            val vm = newViewModel()
            vm.toggleTuningOption(
                OnboardingPreferenceMapper.TuningOption.FEWER_ANIMATIONS_QUIETER_COLORS
            )

            vm.applyTuningSelections(skip = false)
            advanceUntilIdle()

            coVerify(exactly = 1) { ndPreferencesDataStore.setReduceAnimations(true) }
            coVerify(exactly = 1) { ndPreferencesDataStore.setMutedColorPalette(true) }
            coVerify(exactly = 0) { ndPreferencesDataStore.setCalmMode(any()) }
        }

    @Test
    fun `applyTuningSelections writes good-enough timers for over-polish and leaves focusReleaseMode untouched`() =
        runTest(dispatcher) {
            // Post-`fix/onboarding-tuning-mode-cascade` (audit finding #2):
            // BrainModePage owns `focusReleaseMode`; TuningPage only sets
            // the good-enough-timer sub-flag.
            val vm = newViewModel()
            vm.toggleTuningOption(OnboardingPreferenceMapper.TuningOption.OVER_POLISH)

            vm.applyTuningSelections(skip = false)
            advanceUntilIdle()

            coVerify(exactly = 1) { ndPreferencesDataStore.setGoodEnoughTimersEnabled(true) }
            coVerify(exactly = 0) { ndPreferencesDataStore.setFocusReleaseMode(any()) }
        }

    // ── Regression: TuningPage must not override BrainMode / Forgiveness opt-outs ──
    //
    // These tests pin findings #2 + #6 of the onboarding overlap audit
    // (`docs/audits/ONBOARDING_OVERLAP_AUDIT.md`). Each one mirrors the
    // failure scenario the audit identified: the user explicitly opts OUT
    // of a parent flag on an earlier page, then picks the matching tuning
    // option on TuningPage. After the fix, `applyTuningSelections` must
    // NOT touch the parent flag — only the sub-flag(s) TuningPage uniquely
    // owns. If a future refactor re-adds the cascade write, these tests
    // turn red.

    @Test
    fun `regression #2 - LOSE_TRACK_OF_TIME never writes adhdMode parent flag`() =
        runTest(dispatcher) {
            val vm = newViewModel()
            // User picks the matching tuning option on page 10 after opting
            // out of adhdMode on BrainModePage (page 9). We can't observe
            // the BrainModePage write directly here — we just verify the
            // post-fix invariant: setAdhdMode is not called.
            vm.toggleTuningOption(OnboardingPreferenceMapper.TuningOption.LOSE_TRACK_OF_TIME)

            vm.applyTuningSelections(skip = false)
            advanceUntilIdle()

            coVerify(exactly = 0) { ndPreferencesDataStore.setAdhdMode(any()) }
        }

    @Test
    fun `regression #2 - FEWER_ANIMATIONS_QUIETER_COLORS never writes calmMode parent flag`() =
        runTest(dispatcher) {
            val vm = newViewModel()
            vm.toggleTuningOption(
                OnboardingPreferenceMapper.TuningOption.FEWER_ANIMATIONS_QUIETER_COLORS
            )

            vm.applyTuningSelections(skip = false)
            advanceUntilIdle()

            coVerify(exactly = 0) { ndPreferencesDataStore.setCalmMode(any()) }
        }

    @Test
    fun `regression #2 - OVER_POLISH never writes focusReleaseMode parent flag`() =
        runTest(dispatcher) {
            val vm = newViewModel()
            vm.toggleTuningOption(OnboardingPreferenceMapper.TuningOption.OVER_POLISH)

            vm.applyTuningSelections(skip = false)
            advanceUntilIdle()

            coVerify(exactly = 0) { ndPreferencesDataStore.setFocusReleaseMode(any()) }
        }

    @Test
    fun `regression #6 - LOW_ENERGY_DAYS never writes ForgivenessPrefs parent flag`() =
        runTest(dispatcher) {
            val vm = newViewModel()
            vm.toggleTuningOption(OnboardingPreferenceMapper.TuningOption.LOW_ENERGY_DAYS)

            vm.applyTuningSelections(skip = false)
            advanceUntilIdle()

            coVerify(exactly = 0) { userPreferencesDataStore.setForgivenessPrefs(any()) }
        }

    @Test
    fun `regression #2 + #6 - selecting every option still never touches parent flags`() =
        runTest(dispatcher) {
            // The "kitchen-sink" multi-select case. Even if the user picks
            // every tuning option, none of the four parent flags get
            // written by TuningPage.
            val vm = newViewModel()
            vm.toggleTuningOption(OnboardingPreferenceMapper.TuningOption.OVERWHELMED_BY_LONG_LISTS)
            vm.toggleTuningOption(OnboardingPreferenceMapper.TuningOption.LOSE_TRACK_OF_TIME)
            vm.toggleTuningOption(OnboardingPreferenceMapper.TuningOption.LOW_ENERGY_DAYS)
            vm.toggleTuningOption(
                OnboardingPreferenceMapper.TuningOption.FEWER_ANIMATIONS_QUIETER_COLORS
            )
            vm.toggleTuningOption(OnboardingPreferenceMapper.TuningOption.OVER_POLISH)

            vm.applyTuningSelections(skip = false)
            advanceUntilIdle()

            coVerify(exactly = 0) { ndPreferencesDataStore.setAdhdMode(any()) }
            coVerify(exactly = 0) { ndPreferencesDataStore.setCalmMode(any()) }
            coVerify(exactly = 0) { ndPreferencesDataStore.setFocusReleaseMode(any()) }
            coVerify(exactly = 0) { userPreferencesDataStore.setForgivenessPrefs(any()) }
        }

    @Test
    fun `preview mode skips applyTuningSelections writes`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.setPreviewMode(true)
        vm.toggleTuningOption(OnboardingPreferenceMapper.TuningOption.LOSE_TRACK_OF_TIME)
        vm.toggleTuningOption(OnboardingPreferenceMapper.TuningOption.LOW_ENERGY_DAYS)

        vm.applyTuningSelections(skip = false)
        advanceUntilIdle()

        coVerify(exactly = 0) { ndPreferencesDataStore.setAdhdMode(any()) }
        coVerify(exactly = 0) { ndPreferencesDataStore.setCheckInIntervalMinutes(any()) }
        coVerify(exactly = 0) { userPreferencesDataStore.setForgivenessPrefs(any()) }
        coVerify(exactly = 0) { onboardingPreferences.setRestDayPrimed(any()) }
    }
}
