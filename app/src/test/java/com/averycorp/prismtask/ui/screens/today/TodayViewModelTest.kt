package com.averycorp.prismtask.ui.screens.today

import app.cash.turbine.test
import com.averycorp.prismtask.core.time.LocalDateFlow
import com.averycorp.prismtask.data.preferences.AdvancedTuningPreferences
import com.averycorp.prismtask.data.preferences.CompletionCountMode
import com.averycorp.prismtask.data.preferences.DailyEssentialsPreferences
import com.averycorp.prismtask.data.preferences.DashboardPreferences
import com.averycorp.prismtask.data.preferences.HabitListPreferences
import com.averycorp.prismtask.data.preferences.MorningCheckInPreferences
import com.averycorp.prismtask.data.preferences.MorningCheckInPromptCutoff
import com.averycorp.prismtask.data.preferences.SortPreferences
import com.averycorp.prismtask.data.preferences.StartOfDay
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.preferences.TourCardPreferences
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.repository.CheckInLogRepository
import com.averycorp.prismtask.data.repository.HabitRepository
import com.averycorp.prismtask.data.repository.MedicationRefillRepository
import com.averycorp.prismtask.data.repository.ProjectRepository
import com.averycorp.prismtask.data.repository.SchoolworkRepository
import com.averycorp.prismtask.data.repository.SelfCareRepository
import com.averycorp.prismtask.data.repository.TagRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.data.repository.TaskTemplateRepository
import com.averycorp.prismtask.domain.usecase.DailyEssentialsUiState
import com.averycorp.prismtask.domain.usecase.DailyEssentialsUseCase
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import com.averycorp.prismtask.ui.coachmark.CoachmarkController
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Focused unit tests for [TodayViewModel]. TodayViewModel is a ViewModel-coordinator
 * that stitches together many flows; full-state coverage would require wiring up
 * a small universe of fakes. These tests exercise the action methods that delegate
 * to the repository/preference layer — which is where behavioral regressions have
 * historically landed — while stubbing the flows used in the init block so the VM
 * constructs cleanly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var taskRepository: TaskRepository
    private lateinit var tagRepository: TagRepository
    private lateinit var habitRepository: HabitRepository
    private lateinit var projectRepository: ProjectRepository
    private lateinit var templateRepository: TaskTemplateRepository
    private lateinit var dashboardPreferences: DashboardPreferences
    private lateinit var habitListPreferences: HabitListPreferences
    private lateinit var taskBehaviorPreferences: TaskBehaviorPreferences
    private lateinit var sortPreferences: SortPreferences
    private lateinit var proFeatureGate: ProFeatureGate
    private lateinit var userPreferencesDataStore: UserPreferencesDataStore
    private lateinit var checkInLogRepository: CheckInLogRepository
    private lateinit var medicationRefillRepository: MedicationRefillRepository
    private lateinit var morningCheckInPreferences: MorningCheckInPreferences
    private lateinit var advancedTuningPreferences: AdvancedTuningPreferences
    private lateinit var dailyEssentialsUseCase: DailyEssentialsUseCase
    private lateinit var dailyEssentialsPreferences: DailyEssentialsPreferences
    private lateinit var selfCareRepository: SelfCareRepository
    private lateinit var schoolworkRepository: SchoolworkRepository
    private lateinit var localDateFlow: LocalDateFlow
    private lateinit var tourCardPreferences: TourCardPreferences
    private lateinit var coachmarkController: CoachmarkController
    private lateinit var habitDailyTaskGenerator: com.averycorp.prismtask.domain.usecase.HabitDailyTaskGenerator
    private lateinit var restDayRepository: com.averycorp.prismtask.data.repository.RestDayRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        taskRepository = mockk(relaxed = true)
        tagRepository = mockk(relaxed = true)
        habitRepository = mockk(relaxed = true)
        projectRepository = mockk(relaxed = true)
        templateRepository = mockk(relaxed = true)
        dashboardPreferences = mockk(relaxed = true)
        habitListPreferences = mockk(relaxed = true)
        taskBehaviorPreferences = mockk(relaxed = true)
        sortPreferences = mockk(relaxed = true)
        proFeatureGate = mockk(relaxed = true)
        userPreferencesDataStore = mockk(relaxed = true)
        checkInLogRepository = mockk(relaxed = true)
        medicationRefillRepository = mockk(relaxed = true)
        morningCheckInPreferences = mockk(relaxed = true)
        advancedTuningPreferences = mockk(relaxed = true)
        dailyEssentialsUseCase = mockk(relaxed = true)
        dailyEssentialsPreferences = mockk(relaxed = true)
        selfCareRepository = mockk(relaxed = true)
        schoolworkRepository = mockk(relaxed = true)
        tourCardPreferences = mockk(relaxed = true)
        coEvery { tourCardPreferences.eligible() } returns flowOf(false)
        coEvery { tourCardPreferences.coachmarkCompleted() } returns flowOf(false)
        coEvery { tourCardPreferences.coachmarkDismissed() } returns flowOf(false)
        coEvery { tourCardPreferences.coachmarkStepIndex() } returns flowOf(0)
        coachmarkController = mockk(relaxed = true)
        habitDailyTaskGenerator = mockk(relaxed = true)
        restDayRepository = mockk(relaxed = true)
        every { restDayRepository.observeIsRestDayToday(any()) } returns flowOf(false)
        // Mock LocalDateFlow so `observe()` returns a single-emission flow that
        // completes. The real production flow re-emits forever via an internal
        // `delay(timeUntilNextBoundary)` loop — which would keep `runTest`'s
        // virtual scheduler busy forever in any test that constructs the VM.
        // The SoD-boundary regression is gated structurally by
        // TodayDayBoundaryFlowTest; these VM-level tests just need a flow that
        // emits one value and stops.
        localDateFlow = mockk(relaxed = true)
        every { localDateFlow.observe(any()) } returns
            flowOf(java.time.LocalDate.parse("2026-04-26"))
        every { localDateFlow.observeIsoString(any()) } returns flowOf("2026-04-26")
        coEvery { dailyEssentialsUseCase.observeToday() } returns flowOf(DailyEssentialsUiState.empty())

        coEvery { taskBehaviorPreferences.getDayStartHour() } returns flowOf(0)
        coEvery { taskBehaviorPreferences.getStartOfDay() } returns flowOf(StartOfDay(0, 0, false))
        coEvery { dashboardPreferences.getSectionOrder() } returns flowOf(DashboardPreferences.DEFAULT_ORDER)
        coEvery { dashboardPreferences.getHiddenSections() } returns flowOf(emptySet())
        coEvery { dashboardPreferences.getCollapsedSections() } returns flowOf(setOf("planned"))
        coEvery { dashboardPreferences.getProgressStyle() } returns flowOf("ring")
        coEvery { dashboardPreferences.getShowProgressPercentage() } returns flowOf(false)
        coEvery { dashboardPreferences.getRingAsCompletionArc() } returns flowOf(false)
        coEvery { dashboardPreferences.getCompletionCountMode() } returns
            flowOf(CompletionCountMode.TASKS_AND_HABITS)
        coEvery { selfCareRepository.getLogsForDate(any()) } returns flowOf(emptyList())
        coEvery { sortPreferences.observeSortMode(any()) } returns flowOf(SortPreferences.SortModes.DEFAULT)
        coEvery { habitListPreferences.isSelfCareEnabled() } returns flowOf(true)
        coEvery { habitListPreferences.isMedicationEnabled() } returns flowOf(true)
        coEvery { habitListPreferences.isSchoolEnabled() } returns flowOf(true)
        coEvery { habitListPreferences.isLeisureEnabled() } returns flowOf(true)
        coEvery { habitListPreferences.isHouseworkEnabled() } returns flowOf(true)
        coEvery { habitListPreferences.getTodaySkipAfterCompleteDays() } returns flowOf(0)
        coEvery { habitListPreferences.getTodaySkipBeforeScheduleDays() } returns flowOf(0)
        coEvery { habitRepository.getLastCompletionDatesPerHabit() } returns flowOf(emptyList())
        coEvery { projectRepository.getAllProjects() } returns flowOf(emptyList())
        coEvery { habitRepository.getHabitsWithTodayStatus() } returns flowOf(emptyList())
        coEvery { habitRepository.getHabitsWithFullStatus() } returns flowOf(emptyList())
        coEvery { taskRepository.getOverdueRootTasks(any()) } returns flowOf(emptyList())
        coEvery { taskRepository.getTodayTasks(any(), any()) } returns flowOf(emptyList())
        coEvery { taskRepository.getPlannedForToday(any(), any()) } returns flowOf(emptyList())
        coEvery { taskRepository.getCompletedToday(any()) } returns flowOf(emptyList())
        coEvery { taskRepository.getTasksNotInToday(any(), any()) } returns flowOf(emptyList())
        coEvery { checkInLogRepository.getMostRecentDate() } returns null
        coEvery { checkInLogRepository.observeAll() } returns flowOf(emptyList())
        coEvery { medicationRefillRepository.observeAll() } returns flowOf(emptyList())
        coEvery { morningCheckInPreferences.featureEnabled() } returns flowOf(true)
        coEvery { morningCheckInPreferences.bannerDismissedDate() } returns flowOf("")
        coEvery { advancedTuningPreferences.getMorningCheckInPromptCutoff() } returns
            flowOf(MorningCheckInPromptCutoff(latestHour = 11))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() = TodayViewModel(
        taskRepository,
        tagRepository,
        habitRepository,
        projectRepository,
        templateRepository,
        dashboardPreferences,
        habitListPreferences,
        taskBehaviorPreferences,
        sortPreferences,
        proFeatureGate,
        userPreferencesDataStore,
        checkInLogRepository,
        medicationRefillRepository,
        morningCheckInPreferences,
        advancedTuningPreferences,
        dailyEssentialsUseCase,
        dailyEssentialsPreferences,
        selfCareRepository,
        schoolworkRepository,
        localDateFlow,
        tourCardPreferences,
        coachmarkController,
        habitDailyTaskGenerator,
        restDayRepository,
        mockk(relaxed = true), // restDayPreferences
        mockk(relaxed = true), // billingManager
        mockk<com.averycorp.prismtask.domain.usecase.BalanceContributionsProvider>(relaxed = true).also {
            every { it.observe(any(), any(), any(), any()) } returns kotlinx.coroutines.flow.flowOf(
                com.averycorp.prismtask.domain.usecase.BalanceContributions.EMPTY
            )
        }
    )

    @Test
    fun viewModel_constructsWithoutCrashing() = runTest(dispatcher) {
        newViewModel()
        advanceUntilIdle()
    }

    @Test
    fun resumeTourVisible_isFalse_whenIneligible() = runTest(dispatcher) {
        coEvery { tourCardPreferences.eligible() } returns flowOf(false)
        coEvery { tourCardPreferences.coachmarkCompleted() } returns flowOf(false)
        coEvery { tourCardPreferences.coachmarkDismissed() } returns flowOf(false)
        coEvery { tourCardPreferences.coachmarkStepIndex() } returns flowOf(3)

        val vm = newViewModel()
        vm.resumeTourVisible.test {
            // The seed value is false; with all upstream sources cold-emitting
            // a single value, the only post-seed emission is the resolved
            // combine — also false here. Skip the seed and assert the upstream.
            advanceUntilIdle()
            assert(!expectMostRecentItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun resumeTourVisible_isFalse_whenStepIndexZero() = runTest(dispatcher) {
        coEvery { tourCardPreferences.eligible() } returns flowOf(true)
        coEvery { tourCardPreferences.coachmarkCompleted() } returns flowOf(false)
        coEvery { tourCardPreferences.coachmarkDismissed() } returns flowOf(false)
        coEvery { tourCardPreferences.coachmarkStepIndex() } returns flowOf(0)

        val vm = newViewModel()
        vm.resumeTourVisible.test {
            advanceUntilIdle()
            assert(!expectMostRecentItem()) {
                "resumeTourVisible should be false at stepIndex 0 (auto tryStart covers this)"
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun resumeTourVisible_isTrue_whenMidTour() = runTest(dispatcher) {
        coEvery { tourCardPreferences.eligible() } returns flowOf(true)
        coEvery { tourCardPreferences.coachmarkCompleted() } returns flowOf(false)
        coEvery { tourCardPreferences.coachmarkDismissed() } returns flowOf(false)
        coEvery { tourCardPreferences.coachmarkStepIndex() } returns flowOf(5)

        val vm = newViewModel()
        vm.resumeTourVisible.test {
            advanceUntilIdle()
            assert(expectMostRecentItem()) {
                "resumeTourVisible should be true when eligible & mid-tour"
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun resumeTourVisible_isFalse_whenCompleted() = runTest(dispatcher) {
        coEvery { tourCardPreferences.eligible() } returns flowOf(true)
        coEvery { tourCardPreferences.coachmarkCompleted() } returns flowOf(true)
        coEvery { tourCardPreferences.coachmarkDismissed() } returns flowOf(false)
        coEvery { tourCardPreferences.coachmarkStepIndex() } returns flowOf(5)

        val vm = newViewModel()
        vm.resumeTourVisible.test {
            advanceUntilIdle()
            assert(!expectMostRecentItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun resumeTour_invokesController() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.resumeTour()
        advanceUntilIdle()
        coVerify { coachmarkController.resume(any()) }
    }

    @Test
    fun onChangeSort_writesToSortPreferences() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.onChangeSort(SortPreferences.SortModes.PRIORITY)
        advanceUntilIdle()
        coVerify {
            sortPreferences.setSortMode(
                SortPreferences.ScreenKeys.TODAY,
                SortPreferences.SortModes.PRIORITY
            )
        }
    }

    @Test
    fun onToggleSectionCollapsed_flipsCollapseState() = runTest(dispatcher) {
        // Section starts collapsed per the seed above ("planned" is in the set).
        // Toggling should ask to expand it.
        val vm = newViewModel()
        advanceUntilIdle()
        vm.onToggleSectionCollapsed("planned")
        advanceUntilIdle()
        coVerify {
            dashboardPreferences.setSectionCollapsed(sectionKey = "planned", collapsed = false)
        }
    }

    @Test
    fun onToggleHabitCompletion_routesBetweenCompleteAndUncomplete() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.onToggleHabitCompletion(habitId = 7L, isCurrentlyCompleted = false)
        advanceUntilIdle()
        coVerify { habitRepository.completeHabit(7L, any(), any()) }

        vm.onToggleHabitCompletion(habitId = 7L, isCurrentlyCompleted = true)
        advanceUntilIdle()
        coVerify { habitRepository.uncompleteHabit(7L, any()) }
    }

    @Test
    fun onShowAndDismissPlanSheet_togglesStateFlow() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.onShowPlanSheet()
        assert(vm.showPlanSheet.value)

        vm.onDismissPlanSheet()
        assert(!vm.showPlanSheet.value)
    }

    @Test
    fun combinedCompleted_excludesSelfCareInTasksOnlyMode() = runTest(dispatcher) {
        val task = com.averycorp.prismtask.data.local.entity.TaskEntity(
            id = 1L,
            title = "task",
            isCompleted = true,
            completedAt = 0L
        )
        val selfCareLog = com.averycorp.prismtask.data.local.entity.SelfCareLogEntity(
            id = 1L,
            routineType = "morning",
            date = 0L,
            isComplete = true
        )
        coEvery { taskRepository.getCompletedToday(any()) } returns flowOf(listOf(task))
        coEvery { selfCareRepository.getLogsForDate(any()) } returns flowOf(listOf(selfCareLog))
        coEvery { dashboardPreferences.getCompletionCountMode() } returns
            flowOf(CompletionCountMode.TASKS_ONLY)

        val vm = newViewModel()
        vm.combinedCompleted.test {
            advanceUntilIdle()
            assert(expectMostRecentItem() == 1) {
                "TASKS_ONLY should ignore the completed self-care routine"
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun combinedCompleted_includesSelfCareInFullMode() = runTest(dispatcher) {
        val task = com.averycorp.prismtask.data.local.entity.TaskEntity(
            id = 1L,
            title = "task",
            isCompleted = true,
            completedAt = 0L
        )
        val morningLog = com.averycorp.prismtask.data.local.entity.SelfCareLogEntity(
            id = 1L,
            routineType = "morning",
            date = 0L,
            isComplete = true
        )
        val bedtimeLogIncomplete = com.averycorp.prismtask.data.local.entity.SelfCareLogEntity(
            id = 2L,
            routineType = "bedtime",
            date = 0L,
            isComplete = false
        )
        coEvery { taskRepository.getCompletedToday(any()) } returns flowOf(listOf(task))
        coEvery { selfCareRepository.getLogsForDate(any()) } returns
            flowOf(listOf(morningLog, bedtimeLogIncomplete))
        coEvery { dashboardPreferences.getCompletionCountMode() } returns
            flowOf(CompletionCountMode.TASKS_HABITS_AND_SELFCARE)

        val vm = newViewModel()
        vm.combinedCompleted.test {
            advanceUntilIdle()
            // 1 task + 0 habits + 1 completed self-care = 2 (incomplete bedtime ignored)
            assert(expectMostRecentItem() == 2) {
                "FULL mode should add only completed self-care routines"
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun selfCareTotalCount_dropsDisabledRoutines() = runTest(dispatcher) {
        // Self-care toggle off → morning + bedtime drop; medication on → +1;
        // housework off → no housework. Expect total = 1 (medication).
        coEvery { habitListPreferences.isSelfCareEnabled() } returns flowOf(false)
        coEvery { habitListPreferences.isMedicationEnabled() } returns flowOf(true)
        coEvery { habitListPreferences.isHouseworkEnabled() } returns flowOf(false)

        val vm = newViewModel()
        vm.selfCareTotalCount.test {
            advanceUntilIdle()
            assert(expectMostRecentItem() == 1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun onPlanForToday_setsPlannedDateOnEachTask() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.onPlanForToday(listOf(1L, 2L, 3L))
        advanceUntilIdle()

        coVerify { taskRepository.setPlanDate(1L, any()) }
        coVerify { taskRepository.setPlanDate(2L, any()) }
        coVerify { taskRepository.setPlanDate(3L, any()) }
    }
}
