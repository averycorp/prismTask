package com.averycorp.prismtask.ui.screens.coaching

import com.averycorp.prismtask.data.billing.BillingManager
import com.averycorp.prismtask.data.billing.UserTier
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.remote.api.CoachingResponse
import com.averycorp.prismtask.data.repository.CoachingRepository
import com.averycorp.prismtask.data.repository.CoachingResult
import com.averycorp.prismtask.data.repository.MoodEnergyRepository
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import com.averycorp.prismtask.util.DayBoundary
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Tests for the Today-screen "Energy Check-In" → mood log persistence
 * wire-up.
 *
 * Pre-fix, `onSelectEnergy` wrote only to [CoachingRepository]'s
 * single-key DataStore — yesterday's value was overwritten every day,
 * and the "View Trends" analytics screen (which reads from
 * [MoodEnergyRepository]) never saw the data. These tests pin the new
 * dual-write behavior so a future refactor can't silently regress it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoachingViewModelEnergyCheckInTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var coachingRepository: CoachingRepository
    private lateinit var proFeatureGate: ProFeatureGate
    private lateinit var billingManager: BillingManager
    private lateinit var moodEnergyRepository: MoodEnergyRepository
    private lateinit var taskBehaviorPreferences: TaskBehaviorPreferences

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coachingRepository = mockk(relaxed = true)
        coEvery { coachingRepository.getEnergyPlan(any(), any(), any(), any(), any()) } returns
            CoachingResult.Success(CoachingResponse("plan", null))
        proFeatureGate = mockk(relaxed = true)
        every { proFeatureGate.userTier } returns MutableStateFlow(UserTier.PRO)
        billingManager = mockk(relaxed = true)
        moodEnergyRepository = mockk(relaxed = true)
        coEvery { moodEnergyRepository.setEnergyForDate(any(), any(), any()) } returns 1L
        taskBehaviorPreferences = mockk(relaxed = true)
        every { taskBehaviorPreferences.getDayStartHour() } returns flowOf(DAY_START_HOUR)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() = CoachingViewModel(
        coachingRepository,
        proFeatureGate,
        billingManager,
        moodEnergyRepository,
        taskBehaviorPreferences
    )

    @Test
    fun onSelectEnergy_low_writesEnergy2ToMoodLog() = runTest(dispatcher) {
        val vm = newViewModel()
        val energySlot = slot<Int>()
        coEvery {
            moodEnergyRepository.setEnergyForDate(any(), capture(energySlot), any())
        } returns 1L

        vm.onSelectEnergy("low", emptyList(), 0, 0, 0)
        advanceUntilIdle()

        assertEquals(2, energySlot.captured)
    }

    @Test
    fun onSelectEnergy_medium_writesEnergy3ToMoodLog() = runTest(dispatcher) {
        val vm = newViewModel()
        val energySlot = slot<Int>()
        coEvery {
            moodEnergyRepository.setEnergyForDate(any(), capture(energySlot), any())
        } returns 1L

        vm.onSelectEnergy("medium", emptyList(), 0, 0, 0)
        advanceUntilIdle()

        assertEquals(3, energySlot.captured)
    }

    @Test
    fun onSelectEnergy_high_writesEnergy4ToMoodLog() = runTest(dispatcher) {
        val vm = newViewModel()
        val energySlot = slot<Int>()
        coEvery {
            moodEnergyRepository.setEnergyForDate(any(), capture(energySlot), any())
        } returns 1L

        vm.onSelectEnergy("high", emptyList(), 0, 0, 0)
        advanceUntilIdle()

        assertEquals(4, energySlot.captured)
    }

    @Test
    fun onSelectEnergy_writesDateAlignedToConfiguredDayStartHour() = runTest(dispatcher) {
        val vm = newViewModel()
        val dateSlot = slot<Long>()
        coEvery {
            moodEnergyRepository.setEnergyForDate(capture(dateSlot), any(), any())
        } returns 1L

        vm.onSelectEnergy("medium", emptyList(), 0, 0, 0)
        advanceUntilIdle()

        val expected = DayBoundary.startOfCurrentDay(DAY_START_HOUR)
        assertEquals(
            "Date must equal DayBoundary.startOfCurrentDay(${DAY_START_HOUR}) — UTC midnight is the legacy bug",
            expected,
            dateSlot.captured
        )
    }

    @Test
    fun onSelectEnergy_usesMorningTimeOfDay() = runTest(dispatcher) {
        val vm = newViewModel()
        val timeOfDaySlot = slot<String>()
        coEvery {
            moodEnergyRepository.setEnergyForDate(any(), any(), capture(timeOfDaySlot))
        } returns 1L

        vm.onSelectEnergy("low", emptyList(), 0, 0, 0)
        advanceUntilIdle()

        assertEquals("morning", timeOfDaySlot.captured)
    }

    @Test
    fun onSelectEnergy_stillCallsCoachingPreferences() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.onSelectEnergy("medium", emptyList(), 0, 0, 0)
        advanceUntilIdle()

        // The CoachingPreferences write gates the "should we show the
        // card today?" check — must not be replaced by the mood-log write.
        coVerify { coachingRepository.setTodayEnergyLevel("medium") }
    }

    private companion object {
        const val DAY_START_HOUR = 4
    }
}
