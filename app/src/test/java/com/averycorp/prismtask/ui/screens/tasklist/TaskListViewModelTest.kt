package com.averycorp.prismtask.ui.screens.tasklist

import com.averycorp.prismtask.core.time.LocalDateFlow
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.preferences.SortPreferences
import com.averycorp.prismtask.data.preferences.StartOfDay
import com.averycorp.prismtask.data.preferences.SwipePrefs
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.preferences.UrgencyWeights
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.repository.AttachmentRepository
import com.averycorp.prismtask.data.repository.ProjectRepository
import com.averycorp.prismtask.data.repository.TagRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.usecase.AiUrgencyResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * Unit tests for [TaskListViewModel] focused on the state-machine around
 * multi-select, sort changes, and toggled flags / archive actions. The
 * large reactive flow graph is stubbed with empty flows so the VM
 * constructs without blocking on any particular data source.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskListViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var taskRepository: TaskRepository
    private lateinit var projectRepository: ProjectRepository
    private lateinit var tagRepository: TagRepository
    private lateinit var savedFilterRepository: com.averycorp.prismtask.data.repository.SavedFilterRepository
    private lateinit var attachmentRepository: AttachmentRepository
    private lateinit var taskBehaviorPreferences: TaskBehaviorPreferences
    private lateinit var sortPreferences: SortPreferences
    private lateinit var userPreferencesDataStore: UserPreferencesDataStore
    private lateinit var localDateFlow: LocalDateFlow
    private lateinit var aiUrgencyResolver: AiUrgencyResolver

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        taskRepository = mockk(relaxed = true)
        projectRepository = mockk(relaxed = true)
        tagRepository = mockk(relaxed = true)
        savedFilterRepository = mockk(relaxed = true)
        attachmentRepository = mockk(relaxed = true)
        taskBehaviorPreferences = mockk(relaxed = true)
        sortPreferences = mockk(relaxed = true)
        userPreferencesDataStore = mockk(relaxed = true)
        aiUrgencyResolver = mockk(relaxed = true)
        // Default behavior: AI urgency disabled -> resolver returns local
        // scores for whichever tasks the VM passes in. Tests that exercise
        // the AI path explicitly override this stub.
        coEvery { aiUrgencyResolver.resolveScores(any(), any(), any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            val tasks = firstArg<List<TaskEntity>>()
            tasks.associate { it.id to 0f }
        }
        // Mock LocalDateFlow so observe() returns a single-emission flow.
        // (Real production flow re-emits forever via internal `delay`; that
        // would keep `runTest`'s scheduler busy. SoD-boundary regression is
        // gated structurally by TaskListDayBoundaryFlowTest.)
        localDateFlow = mockk(relaxed = true)
        every { localDateFlow.observe(any()) } returns
            flowOf(java.time.LocalDate.parse("2026-04-26"))
        every { localDateFlow.observeIsoString(any()) } returns flowOf("2026-04-26")

        coEvery { taskRepository.getIncompleteRootTasks() } returns flowOf(emptyList())
        coEvery { taskRepository.getAllTasks() } returns flowOf(emptyList())
        coEvery { taskRepository.getSubtasks(any()) } returns flowOf(emptyList())
        coEvery { projectRepository.getAllProjects() } returns flowOf(emptyList())
        coEvery { tagRepository.getAllTags() } returns flowOf(emptyList())
        coEvery { tagRepository.getTagsForTask(any()) } returns flowOf(emptyList())
        coEvery { savedFilterRepository.getAll() } returns flowOf(emptyList())
        coEvery { attachmentRepository.getAttachmentCount(any()) } returns flowOf(0)
        coEvery { taskBehaviorPreferences.getDayStartHour() } returns flowOf(0)
        coEvery { taskBehaviorPreferences.getStartOfDay() } returns flowOf(StartOfDay(0, 0, false))
        coEvery { taskBehaviorPreferences.getDefaultSort() } returns flowOf("DUE_DATE")
        coEvery { taskBehaviorPreferences.getDefaultViewMode() } returns flowOf("UPCOMING")
        coEvery { taskBehaviorPreferences.getUrgencyWeights() } returns flowOf(UrgencyWeights())
        coEvery { sortPreferences.observeSortMode(any()) } returns flowOf(SortPreferences.SortModes.DUE_DATE)
        coEvery { sortPreferences.getSortModeOrNull(any()) } returns null
        coEvery { userPreferencesDataStore.swipeFlow } returns flowOf(SwipePrefs())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() = TaskListViewModel(
        taskRepository,
        projectRepository,
        tagRepository,
        savedFilterRepository,
        attachmentRepository,
        taskBehaviorPreferences,
        sortPreferences,
        userPreferencesDataStore,
        localDateFlow,
        aiUrgencyResolver
    )

    @Test
    fun viewModel_constructsAndLoads() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()
        // isLoading transitions to false after the first emission of root tasks.
        assertFalse(vm.isLoading.value)
    }

    /**
     * Regression — Crashlytics NPE on Tasks-tab cold open.
     *
     * The bug: `observeAiUrgencyScores()` was invoked from the first init
     * block before `allRootTasks` and `_currentSort` were initialized.
     * Production `viewModelScope` runs on `Main.immediate`, so the launched
     * coroutine ran synchronously and `combine(...)` saw nulls →
     * `CombineKt.combineInternal` NPE on `Flow.collect`.
     *
     * `StandardTestDispatcher` (lazy) hid the bug because launches don't run
     * until `advanceUntilIdle`. `UnconfinedTestDispatcher` mirrors
     * `Main.immediate`'s eager-dispatch behaviour and reproduces the crash
     * deterministically.
     */
    @Test
    fun viewModel_constructsWithoutNpe_underEagerDispatch() {
        val eager = UnconfinedTestDispatcher()
        Dispatchers.resetMain()
        Dispatchers.setMain(eager)
        try {
            // Smoke: construction itself must not throw under eager dispatch.
            val vm = newViewModel()
            assertEquals(SortOption.DUE_DATE, vm.currentSort.value)
        } finally {
            Dispatchers.resetMain()
            Dispatchers.setMain(dispatcher)
        }
    }

    @Test
    fun onToggleFlag_delegatesToRepository() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.onToggleFlag(42L)
        advanceUntilIdle()
        coVerify { taskRepository.toggleFlag(42L) }
    }

    @Test
    fun onArchiveTask_delegatesToRepository() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.onArchiveTask(42L)
        advanceUntilIdle()
        coVerify { taskRepository.archiveTask(42L) }
    }

    @Test
    fun onEnterMultiSelect_setsModeWithInitialTaskSelected() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.onEnterMultiSelect(7L)
        assertTrue(vm.isMultiSelectMode.value)
        assertEquals(setOf(7L), vm.selectedTaskIds.value)
    }

    @Test
    fun onToggleTaskSelection_addsAndRemovesIds() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.onEnterMultiSelect(1L)
        vm.onToggleTaskSelection(2L)
        vm.onToggleTaskSelection(3L)
        assertEquals(setOf(1L, 2L, 3L), vm.selectedTaskIds.value)

        // Toggling an already-selected id removes it.
        vm.onToggleTaskSelection(2L)
        assertEquals(setOf(1L, 3L), vm.selectedTaskIds.value)
    }

    @Test
    fun onExitMultiSelect_clearsModeAndSelection() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.onEnterMultiSelect(1L)
        vm.onToggleTaskSelection(2L)
        vm.onExitMultiSelect()

        assertFalse(vm.isMultiSelectMode.value)
        assertTrue(vm.selectedTaskIds.value.isEmpty())
    }

    @Test
    fun onDeselectAll_clearsSelectionWithoutLeavingMultiSelect() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()

        vm.onEnterMultiSelect(1L)
        vm.onToggleTaskSelection(2L)
        vm.onDeselectAll()

        assertTrue(vm.selectedTaskIds.value.isEmpty())
        assertTrue(
            "Deselect all should keep multi-select mode active",
            vm.isMultiSelectMode.value
        )
    }

    /**
     * Regression: "From Earlier shows things due today" when SoD > 0.
     *
     * A timeless task created via the "Today" date picker / NLP "today"
     * stores dueDate at calendar midnight (00:00 local). The bucketing
     * compare must use calendar midnight too — using the SoD-anchored
     * projection (`startOfToday` set to `today.atTime(sodHour, sodMinute)`)
     * sat *after* 00:00 and pushed every such task into Overdue / "From
     * Earlier".
     *
     * This test mocks SoD=4am and a single task whose dueDate is calendar
     * midnight of the mocked logical date. It must land in Today, not
     * Overdue.
     */
    @Test
    fun groupedTasks_timelessTaskOnTodayLandsInTodayWhenSoDIsNonZero() = runTest(dispatcher) {
        val logicalDate = java.time.LocalDate.parse("2026-04-26")
        val midnightMillis = logicalDate
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        coEvery { taskBehaviorPreferences.getStartOfDay() } returns
            flowOf(StartOfDay(hour = 4, minute = 0, hasBeenSet = true))
        coEvery { taskBehaviorPreferences.getDayStartHour() } returns flowOf(4)
        every { localDateFlow.observe(any()) } returns flowOf(logicalDate)
        every { localDateFlow.observeIsoString(any()) } returns flowOf(logicalDate.toString())

        val timelessTodayTask = TaskEntity(
            id = 99L,
            title = "Buy milk",
            dueDate = midnightMillis,
            parentTaskId = null
        )
        coEvery { taskRepository.getAllTasks() } returns flowOf(listOf(timelessTodayTask))
        coEvery { taskRepository.getIncompleteRootTasks() } returns flowOf(listOf(timelessTodayTask))

        val vm = newViewModel()
        // groupedTasks is gated by SharingStarted.WhileSubscribed — `.value`
        // alone returns the initial empty map without activating the upstream.
        // Launch a collector in backgroundScope so the combine actually fires.
        val emissions = mutableListOf<Map<String, List<TaskEntity>>>()
        backgroundScope.launch { vm.groupedTasks.collect { emissions.add(it) } }
        advanceUntilIdle()

        val grouped = emissions.lastOrNull() ?: emptyMap()
        assertFalse(
            "Timeless task at 00:00 must NOT bucket to Overdue when SoD=4 — " +
                "this is the 'From Earlier shows things due today' regression. " +
                "Found buckets: ${grouped.keys}",
            grouped["Overdue"].orEmpty().any { it.id == 99L }
        )
        assertTrue(
            "Timeless task at 00:00 with logical-date = today must bucket to Today. " +
                "Found buckets: ${grouped.keys}, Today contents: ${grouped["Today"]?.map { it.id }}",
            grouped["Today"].orEmpty().any { it.id == 99L }
        )
    }

    /**
     * Archived projects must not appear in the top-of-screen [visibleProjects]
     * chip row. The unfiltered [projects] flow still emits all projects so
     * task-card name lookups for tasks belonging to an archived project keep
     * working.
     */
    @Test
    fun visibleProjects_excludesArchivedProjects() = runTest(dispatcher) {
        val active = ProjectEntity(id = 1L, name = "Active Project", status = "ACTIVE")
        val completed = ProjectEntity(id = 2L, name = "Completed Project", status = "COMPLETED")
        val archived = ProjectEntity(id = 3L, name = "Archived Project", status = "ARCHIVED")
        coEvery { projectRepository.getAllProjects() } returns
            flowOf(listOf(active, completed, archived))

        val vm = newViewModel()
        val visibleEmissions = mutableListOf<List<ProjectEntity>>()
        val allEmissions = mutableListOf<List<ProjectEntity>>()
        backgroundScope.launch { vm.visibleProjects.collect { visibleEmissions.add(it) } }
        backgroundScope.launch { vm.projects.collect { allEmissions.add(it) } }
        advanceUntilIdle()

        val visible = visibleEmissions.lastOrNull() ?: emptyList()
        val all = allEmissions.lastOrNull() ?: emptyList()
        assertEquals(
            "visibleProjects must contain only non-archived projects",
            listOf(1L, 2L),
            visible.map { it.id }
        )
        assertEquals(
            "projects must still expose all rows (incl. archived) for name lookups",
            listOf(1L, 2L, 3L),
            all.map { it.id }
        )
    }
}
