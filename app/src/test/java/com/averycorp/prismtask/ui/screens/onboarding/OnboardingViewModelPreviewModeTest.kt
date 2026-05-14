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
}
