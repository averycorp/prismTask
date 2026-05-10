package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.repository.TaskRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals

class HabitDailyTaskGeneratorTest {
    private lateinit var habitDao: HabitDao
    private lateinit var taskDao: TaskDao
    private lateinit var completionDao: HabitCompletionDao
    private lateinit var taskRepository: TaskRepository
    private lateinit var taskBehaviorPreferences: TaskBehaviorPreferences
    private lateinit var generator: HabitDailyTaskGenerator

    @Before
    fun setUp() {
        habitDao = mockk(relaxed = true)
        taskDao = mockk(relaxed = true)
        completionDao = mockk(relaxed = true)
        taskRepository = mockk(relaxed = true)
        taskBehaviorPreferences = mockk(relaxed = true)
        every { taskBehaviorPreferences.getDayStartHour() } returns flowOf(0)
        coEvery { taskRepository.insertTask(any()) } returns 99L
        generator = HabitDailyTaskGenerator(
            habitDao = habitDao,
            taskDao = taskDao,
            completionDao = completionDao,
            taskRepository = taskRepository,
            taskBehaviorPreferences = taskBehaviorPreferences
        )
    }

    @Test
    fun ensureTasksForToday_skipsHabitsWithoutCreateDailyTaskFlag() = runBlocking {
        coEvery { habitDao.getActiveHabitsOnce() } returns listOf(
            habit(id = 1, createDailyTask = false)
        )

        val created = generator.ensureTasksForToday()

        assertEquals(0, created)
        coVerify(exactly = 0) { taskRepository.insertTask(any()) }
    }

    @Test
    fun ensureTasksForToday_skipsWhenAlreadyCompletedToday() = runBlocking {
        coEvery { habitDao.getActiveHabitsOnce() } returns listOf(
            habit(id = 1, createDailyTask = true)
        )
        coEvery { completionDao.getCompletionCountForDateLocalOnce(1, any()) } returns 1
        coEvery { taskDao.getLatestHabitTaskForDayOnce(any(), any(), any()) } returns null

        val created = generator.ensureTasksForToday()

        assertEquals(0, created)
        coVerify(exactly = 0) { taskRepository.insertTask(any()) }
    }

    @Test
    fun ensureTasksForToday_skipsWhenTaskAlreadyExistsForDay() = runBlocking {
        coEvery { habitDao.getActiveHabitsOnce() } returns listOf(
            habit(id = 1, createDailyTask = true)
        )
        coEvery { completionDao.getCompletionCountForDateLocalOnce(1, any()) } returns 0
        coEvery { taskDao.getLatestHabitTaskForDayOnce(1, any(), any()) } returns
            TaskEntity(id = 5, title = "x", sourceHabitId = 1)

        val created = generator.ensureTasksForToday()

        assertEquals(0, created)
        coVerify(exactly = 0) { taskRepository.insertTask(any()) }
    }

    @Test
    fun ensureTasksForToday_createsTaskForScheduledDailyHabit() = runBlocking {
        coEvery { habitDao.getActiveHabitsOnce() } returns listOf(
            habit(id = 1, name = "Stretch", createDailyTask = true)
        )
        coEvery { completionDao.getCompletionCountForDateLocalOnce(1, any()) } returns 0
        coEvery { taskDao.getLatestHabitTaskForDayOnce(1, any(), any()) } returns null

        val created = generator.ensureTasksForToday()

        assertEquals(1, created)
        coVerify(exactly = 1) {
            taskRepository.insertTask(
                match { it.sourceHabitId == 1L && it.title == "Stretch" && it.dueDate != null }
            )
        }
    }

    @Test
    fun ensureTasksForToday_skipsNonRecurringFrequencies() = runBlocking {
        coEvery { habitDao.getActiveHabitsOnce() } returns listOf(
            habit(id = 1, frequencyPeriod = "monthly", createDailyTask = true)
        )

        val created = generator.ensureTasksForToday()

        assertEquals(0, created)
        coVerify(exactly = 0) { taskRepository.insertTask(any()) }
    }

    private fun habit(
        id: Long,
        name: String = "habit",
        frequencyPeriod: String = "daily",
        createDailyTask: Boolean = false,
        activeDays: String? = null
    ) = HabitEntity(
        id = id,
        name = name,
        frequencyPeriod = frequencyPeriod,
        createDailyTask = createDailyTask,
        activeDays = activeDays
    )
}
