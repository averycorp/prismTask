package com.averycorp.prismtask.ui.screens.habits

import com.averycorp.prismtask.data.preferences.AdvancedTuningPreferences
import com.averycorp.prismtask.data.preferences.BuiltInSortOrders
import com.averycorp.prismtask.data.preferences.HabitListPreferences
import com.averycorp.prismtask.data.preferences.SelfCareTierDefaults
import com.averycorp.prismtask.data.repository.DailyCourseProgress
import com.averycorp.prismtask.data.repository.HabitRepository
import com.averycorp.prismtask.data.repository.SchoolworkRepository
import com.averycorp.prismtask.data.repository.SelfCareRepository
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
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
 * Unit tests for [HabitListViewModel]. Focuses on the command methods —
 * toggling completion, logging activity, booking, deleting — that forward
 * to the repository layer. The reactive flow graph is mocked with empty
 * emissions so the VM can construct without a real data source.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HabitListViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var habitRepository: HabitRepository
    private lateinit var selfCareRepository: SelfCareRepository
    private lateinit var schoolworkRepository: SchoolworkRepository
    private lateinit var habitListPreferences: HabitListPreferences
    private lateinit var advancedTuningPreferences: AdvancedTuningPreferences
    private lateinit var gson: Gson

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        habitRepository = mockk(relaxed = true)
        selfCareRepository = mockk(relaxed = true)
        schoolworkRepository = mockk(relaxed = true)
        habitListPreferences = mockk(relaxed = true)
        advancedTuningPreferences = mockk(relaxed = true)
        gson = Gson()

        coEvery { advancedTuningPreferences.getSelfCareTierDefaults() } returns flowOf(SelfCareTierDefaults())

        coEvery { habitRepository.getHabitsWithFullStatus() } returns flowOf(emptyList())
        coEvery { selfCareRepository.getTodayLog(any()) } returns flowOf(null)
        coEvery { selfCareRepository.getSteps(any()) } returns flowOf(emptyList())
        coEvery { schoolworkRepository.getDailyCourseProgress() } returns flowOf(DailyCourseProgress(0, 0))
        coEvery { habitListPreferences.getBuiltInSortOrders() } returns flowOf(
            BuiltInSortOrders(
                HabitListPreferences.DEFAULT_MORNING_ORDER,
                HabitListPreferences.DEFAULT_BEDTIME_ORDER,
                HabitListPreferences.DEFAULT_MEDICATION_ORDER,
                HabitListPreferences.DEFAULT_SCHOOL_ORDER,
                HabitListPreferences.DEFAULT_LEISURE_ORDER,
                HabitListPreferences.DEFAULT_HOUSEWORK_ORDER
            )
        )
        coEvery { habitListPreferences.isSelfCareEnabled() } returns flowOf(true)
        coEvery { habitListPreferences.isMedicationEnabled() } returns flowOf(true)
        coEvery { habitListPreferences.isSchoolEnabled() } returns flowOf(true)
        coEvery { habitListPreferences.isLeisureEnabled() } returns flowOf(true)
        coEvery { habitListPreferences.isHouseworkEnabled() } returns flowOf(true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() =
        HabitListViewModel(
            habitRepository,
            selfCareRepository,
            schoolworkRepository,
            habitListPreferences,
            advancedTuningPreferences,
            gson
        )

    @Test
    fun onToggleCompletion_currentlyCompleteRoutesToUncomplete() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.onToggleCompletion(habitId = 7L, isFullyCompleted = true)
        advanceUntilIdle()
        coVerify { habitRepository.uncompleteHabit(7L, any()) }
    }

    @Test
    fun onToggleCompletion_currentlyIncompleteRoutesToComplete() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.onToggleCompletion(habitId = 7L, isFullyCompleted = false)
        advanceUntilIdle()
        coVerify { habitRepository.completeHabit(7L, any(), any()) }
    }

    @Test
    fun onDecrementCompletion_alwaysUncompletes() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.onDecrementCompletion(42L)
        advanceUntilIdle()
        coVerify { habitRepository.uncompleteHabit(42L, any()) }
    }

    @Test
    fun completeWithNotes_forwardsNotesToRepository() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.completeWithNotes(42L, "felt great")
        advanceUntilIdle()
        coVerify { habitRepository.completeHabit(42L, any(), "felt great") }
    }

    @Test
    fun onDeleteHabit_delegatesToRepository() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.onDeleteHabit(42L)
        advanceUntilIdle()
        coVerify { habitRepository.deleteHabit(42L) }
    }

    @Test
    fun onSetBooked_forwardsBookingFields() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.onSetBooked(habitId = 5L, isBooked = true, bookedDate = 999L, bookedNote = "doctor")
        advanceUntilIdle()
        coVerify {
            habitRepository.setBooked(
                habitId = 5L,
                isBooked = true,
                bookedDate = 999L,
                bookedNote = "doctor"
            )
        }
    }

    @Test
    fun onLogActivity_forwardsLogFields() = runTest(dispatcher) {
        val vm = newViewModel()
        advanceUntilIdle()
        vm.onLogActivity(habitId = 5L, date = 1_700_000_000_000L, notes = "done")
        advanceUntilIdle()
        coVerify {
            habitRepository.logActivity(
                habitId = 5L,
                date = 1_700_000_000_000L,
                notes = "done"
            )
        }
    }
}
