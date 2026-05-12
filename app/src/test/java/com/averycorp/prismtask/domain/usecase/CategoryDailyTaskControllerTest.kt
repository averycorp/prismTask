package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.util.DayBoundary
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CategoryDailyTaskController]. The controller bridges
 * the Daily Essentials toggle UI to TaskRepository: it spawns a single
 * task due today per category and reaps it when the toggle flips back
 * off. The task is non-recurring so completing it does not carry the
 * item forward into the next day.
 */
class CategoryDailyTaskControllerTest {
    private val taskRepository: TaskRepository = mockk(relaxed = true)
    private val taskBehaviorPreferences: TaskBehaviorPreferences =
        mockk<TaskBehaviorPreferences>(relaxed = true).also {
            every { it.getDayStartHour() } returns flowOf(0)
        }
    private val controller = CategoryDailyTaskController(taskRepository, taskBehaviorPreferences)

    @Test
    fun ensure_createsTaskDueToday_whenNoExistingId() = runTest {
        val titleSlot = slot<String>()
        val dueDateSlot = slot<Long?>()
        val ruleSlot = slot<String?>()
        coEvery {
            taskRepository.addTask(
                title = capture(titleSlot),
                dueDate = captureNullable(dueDateSlot),
                recurrenceRule = captureNullable(ruleSlot)
            )
        } returns 42L

        val id = controller.ensureDailyTask(label = "Music Practice", emoji = "🎵", existingId = null)

        assertEquals(42L, id)
        assertEquals("🎵 Music Practice", titleSlot.captured)
        val expectedStart = DayBoundary.startOfCurrentDay(0)
        assertNotNull("Daily task must have a due date set to today", dueDateSlot.captured)
        // Allow up to a one-second window for the boundary timestamp the
        // controller resolves vs. the one resolved here, since both call
        // System.currentTimeMillis() independently.
        val captured = dueDateSlot.captured!!
        assertTrue(
            "Due date should be at start of today; expected≈$expectedStart got=$captured",
            kotlin.math.abs(captured - expectedStart) < 1_000L
        )
        assertNull(
            "Daily task must be non-recurring so it does not carry over to the next day",
            ruleSlot.captured
        )
    }

    @Test
    fun ensure_reusesExistingId_whenTaskStillPresent() = runTest {
        coEvery { taskRepository.getTaskByIdOnce(7L) } returns stubTask(id = 7L)

        val id = controller.ensureDailyTask(label = "Math 101", emoji = "📚", existingId = 7L)

        assertEquals(7L, id)
        coVerify(exactly = 0) {
            taskRepository.addTask(title = any(), dueDate = any(), recurrenceRule = any())
        }
    }

    @Test
    fun ensure_respawns_whenExistingIdRefersToDeletedTask() = runTest {
        coEvery { taskRepository.getTaskByIdOnce(99L) } returns null
        coEvery {
            taskRepository.addTask(title = any(), dueDate = any(), recurrenceRule = any())
        } returns 100L

        val id = controller.ensureDailyTask(label = "Math 101", emoji = "📚", existingId = 99L)

        assertEquals(100L, id)
    }

    @Test
    fun remove_deletesTask_whenItExists() = runTest {
        coEvery { taskRepository.getTaskByIdOnce(5L) } returns stubTask(id = 5L)

        controller.removeDailyTask(5L)

        coVerify(exactly = 1) { taskRepository.deleteTask(5L) }
    }

    @Test
    fun remove_isNoOp_whenIdIsNull() = runTest {
        controller.removeDailyTask(null)

        coVerify(exactly = 0) { taskRepository.deleteTask(any()) }
        coVerify(exactly = 0) { taskRepository.getTaskByIdOnce(any()) }
    }

    @Test
    fun remove_isNoOp_whenTaskAlreadyGone() = runTest {
        coEvery { taskRepository.getTaskByIdOnce(11L) } returns null

        controller.removeDailyTask(11L)

        coVerify(exactly = 0) { taskRepository.deleteTask(any()) }
    }

    @Test
    fun ensure_omitsEmoji_whenBlank() = runTest {
        val titleSlot = slot<String>()
        coEvery {
            taskRepository.addTask(title = capture(titleSlot), dueDate = any(), recurrenceRule = any())
        } returns 1L

        controller.ensureDailyTask(label = "Reading", emoji = "  ", existingId = null)

        assertEquals("Reading", titleSlot.captured)
    }

    @Test
    fun ensure_fallsBackToDefaultLabel_whenLabelBlank() = runTest {
        val titleSlot = slot<String>()
        coEvery {
            taskRepository.addTask(title = capture(titleSlot), dueDate = any(), recurrenceRule = any())
        } returns 1L

        controller.ensureDailyTask(label = "   ", emoji = "✨", existingId = null)

        assertEquals("✨ Daily Task", titleSlot.captured)
    }

    private fun stubTask(id: Long): TaskEntity = TaskEntity(
        id = id,
        title = "stub"
    )
}
