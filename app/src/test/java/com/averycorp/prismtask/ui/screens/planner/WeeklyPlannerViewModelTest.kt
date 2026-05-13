package com.averycorp.prismtask.ui.screens.planner

import com.averycorp.prismtask.data.billing.UserTier
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.remote.api.DayPlanResponse
import com.averycorp.prismtask.data.remote.api.PlannedTaskResponse
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.remote.api.UnscheduledTaskResponse
import com.averycorp.prismtask.data.remote.api.WeeklyPlanResponse
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek

/**
 * Unit tests for [WeeklyPlannerViewModel] cloud_id resolution layer.
 *
 * Resolution-path: planned tasks whose Firestore doc id maps to a local
 * row land in the day plan with the local Long id.
 *
 * Unresolved-path: planned tasks with no local row are demoted into the
 * weekly plan's unscheduled list with reason "Not synced to this device".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WeeklyPlannerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var api: PrismTaskApi
    private lateinit var taskRepository: TaskRepository
    private lateinit var proFeatureGate: ProFeatureGate
    private lateinit var taskBehaviorPreferences: TaskBehaviorPreferences

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        api = mockk()
        taskRepository = mockk(relaxed = true)
        proFeatureGate = mockk(relaxed = true)
        every { proFeatureGate.userTier } returns MutableStateFlow(UserTier.PRO)
        every { proFeatureGate.hasAccess(ProFeatureGate.AI_WEEKLY_PLAN) } returns true
        taskBehaviorPreferences = mockk(relaxed = true)
        every { taskBehaviorPreferences.getFirstDayOfWeek() } returns flowOf(DayOfWeek.MONDAY)
        coEvery { taskRepository.getIdByCloudId("cloud-1") } returns 1L
        coEvery { taskRepository.getIdByCloudId("cloud-2") } returns 2L
        coEvery { taskRepository.getIdByCloudId("cloud-5") } returns 5L
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() = WeeklyPlannerViewModel(api, taskRepository, proFeatureGate, taskBehaviorPreferences)

    @Test
    fun generatePlan_resolvesCloudIdsToLocalLongs() = runTest(dispatcher) {
        coEvery { api.getWeeklyPlan(any()) } returns WeeklyPlanResponse(
            plan = mapOf(
                "Monday" to DayPlanResponse(
                    date = "2026-05-04",
                    tasks = listOf(
                        PlannedTaskResponse("cloud-1", "Write report", "9:00", 60, "Morning focus")
                    ),
                    totalHours = 1.0,
                    calendarEvents = emptyList(),
                    habits = emptyList()
                )
            ),
            unscheduled = listOf(
                UnscheduledTaskResponse("cloud-5", "Defer task", "Low priority")
            ),
            weekSummary = "Light week",
            tips = emptyList()
        )

        val vm = newViewModel()
        vm.generatePlan()
        advanceUntilIdle()

        val plan = vm.plan.value
        assertNotNull(plan)
        assertEquals(1, plan!!.days.size)
        assertEquals(1, plan.days[0].tasks.size)
        assertEquals(1L, plan.days[0].tasks[0].taskId)
        assertEquals(1, plan.unscheduled.size)
        assertEquals(5L, plan.unscheduled[0].taskId)
    }

    @Test
    fun generatePlan_demotesUnresolvedPlannedTasksIntoUnscheduled() = runTest(dispatcher) {
        coEvery { taskRepository.getIdByCloudId("cloud-99") } returns null
        coEvery { api.getWeeklyPlan(any()) } returns WeeklyPlanResponse(
            plan = mapOf(
                "Monday" to DayPlanResponse(
                    date = "2026-05-04",
                    tasks = listOf(
                        PlannedTaskResponse("cloud-1", "Write report", "9:00", 60, "Morning focus"),
                        PlannedTaskResponse("cloud-99", "Cross-device task", "10:00", 30, "Created on phone")
                    ),
                    totalHours = 1.5,
                    calendarEvents = emptyList(),
                    habits = emptyList()
                )
            ),
            unscheduled = emptyList(),
            weekSummary = "Mixed sync",
            tips = emptyList()
        )

        val vm = newViewModel()
        vm.generatePlan()
        advanceUntilIdle()

        val plan = vm.plan.value
        assertNotNull(plan)
        // Resolved task lands on Monday with local Long id.
        assertEquals(1, plan!!.days[0].tasks.size)
        assertEquals(1L, plan.days[0].tasks[0].taskId)
        // Unresolved task demoted into unscheduled with null taskId.
        assertEquals(1, plan.unscheduled.size)
        assertNull(plan.unscheduled[0].taskId)
        assertEquals("Cross-device task", plan.unscheduled[0].title)
        assertEquals("Not synced to this device", plan.unscheduled[0].reason)
        assertTrue(plan.unscheduled[0].taskId == null)
    }
}
