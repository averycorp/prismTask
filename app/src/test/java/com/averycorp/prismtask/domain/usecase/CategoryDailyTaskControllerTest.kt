package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.converter.RecurrenceConverter
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.model.RecurrenceRule
import com.averycorp.prismtask.domain.model.RecurrenceType
import io.mockk.captureNullable
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Unit tests for [CategoryDailyTaskController]. The controller bridges
 * the Daily Essentials toggle UI to TaskRepository: it spawns a single
 * recurring DAILY task per category and reaps it when the toggle flips
 * back off.
 */
class CategoryDailyTaskControllerTest {
    private val taskRepository: TaskRepository = mockk(relaxed = true)
    private val controller = CategoryDailyTaskController(taskRepository)

    @Test
    fun ensure_createsRecurringDailyTask_whenNoExistingId() = runTest {
        val titleSlot = slot<String>()
        val ruleSlot = slot<String?>()
        coEvery {
            taskRepository.addTask(
                title = capture(titleSlot),
                recurrenceRule = captureNullable(ruleSlot)
            )
        } returns 42L

        val id = controller.ensureDailyTask(label = "Music Practice", emoji = "🎵", existingId = null)

        assertEquals(42L, id)
        assertEquals("🎵 Music Practice", titleSlot.captured)
        val rule = RecurrenceConverter.fromJson(ruleSlot.captured!!)
        assertNotNull(rule)
        assertEquals(RecurrenceType.DAILY, rule!!.type)
    }

    @Test
    fun ensure_reusesExistingId_whenTaskStillPresent() = runTest {
        coEvery { taskRepository.getTaskByIdOnce(7L) } returns stubTask(id = 7L)

        val id = controller.ensureDailyTask(label = "Math 101", emoji = "📚", existingId = 7L)

        assertEquals(7L, id)
        coVerify(exactly = 0) {
            taskRepository.addTask(title = any(), recurrenceRule = any())
        }
    }

    @Test
    fun ensure_respawns_whenExistingIdRefersToDeletedTask() = runTest {
        coEvery { taskRepository.getTaskByIdOnce(99L) } returns null
        coEvery {
            taskRepository.addTask(title = any(), recurrenceRule = any())
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
            taskRepository.addTask(title = capture(titleSlot), recurrenceRule = any())
        } returns 1L

        controller.ensureDailyTask(label = "Reading", emoji = "  ", existingId = null)

        assertEquals("Reading", titleSlot.captured)
    }

    @Test
    fun ensure_fallsBackToDefaultLabel_whenLabelBlank() = runTest {
        val titleSlot = slot<String>()
        coEvery {
            taskRepository.addTask(title = capture(titleSlot), recurrenceRule = any())
        } returns 1L

        controller.ensureDailyTask(label = "   ", emoji = "✨", existingId = null)

        assertEquals("✨ Daily Task", titleSlot.captured)
    }

    private fun stubTask(id: Long): TaskEntity = TaskEntity(
        id = id,
        title = "stub",
        recurrenceRule = RecurrenceConverter.toJson(RecurrenceRule(type = RecurrenceType.DAILY))
    )
}
