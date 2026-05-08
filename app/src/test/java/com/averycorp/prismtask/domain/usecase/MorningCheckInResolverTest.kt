package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.repository.HabitWithStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class MorningCheckInResolverTest {
    private val resolver = MorningCheckInResolver()
    private val zone: ZoneId = ZoneId.of("UTC")

    private fun millis(year: Int, month: Int, day: Int, hour: Int): Long =
        LocalDateTime
            .of(year, month, day, hour, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun task(id: Long, priority: Int = 0, dueDate: Long? = null, completed: Boolean = false) =
        TaskEntity(
            id = id,
            title = "task $id",
            priority = priority,
            dueDate = dueDate,
            isCompleted = completed
        )

    private fun habit(id: Long) = HabitWithStatus(
        habit = HabitEntity(id = id, name = "Habit $id"),
        isCompletedToday = false,
        currentStreak = 0,
        completionsThisWeek = 0
    )

    @Test
    fun `disabled config returns empty plan`() {
        val plan = resolver.plan(
            tasks = emptyList(),
            habits = listOf(habit(1)),
            config = MorningCheckInConfig(enabled = false),
            todayStart = millis(2026, 4, 11, 0)
        )
        assertTrue(plan.steps.isEmpty())
        assertTrue(plan.topTasks.isEmpty())
        assertTrue(plan.todayHabits.isEmpty())
    }

    @Test
    fun `top tasks picked by priority then due date`() {
        val todayStart = millis(2026, 4, 11, 0)
        val tasks = listOf(
            task(1, priority = 1, dueDate = todayStart + 3600_000),
            task(2, priority = 4, dueDate = todayStart + 3600_000),
            task(3, priority = 3, dueDate = todayStart + 1000),
            task(4, priority = 2, dueDate = todayStart + 1000),
            task(5, priority = 0, dueDate = todayStart + 1000)
        )
        val plan = resolver.plan(
            tasks = tasks,
            habits = emptyList(),
            config = MorningCheckInConfig(),
            todayStart = todayStart
        )
        assertEquals(3, plan.topTasks.size)
        // Priority 4 first, then 3, then 2.
        assertEquals(4, plan.topTasks[0].priority)
        assertEquals(3, plan.topTasks[1].priority)
        assertEquals(2, plan.topTasks[2].priority)
    }

    @Test
    fun `completed tasks excluded from top tasks`() {
        val todayStart = millis(2026, 4, 11, 0)
        val tasks = listOf(
            task(1, priority = 4, dueDate = todayStart + 100, completed = true),
            task(2, priority = 1, dueDate = todayStart + 100)
        )
        val plan = resolver.plan(
            tasks = tasks,
            habits = emptyList(),
            config = MorningCheckInConfig(),
            todayStart = todayStart
        )
        assertEquals(1, plan.topTasks.size)
        assertEquals(2L, plan.topTasks[0].id)
    }

    @Test
    fun `habits step hidden when no habits`() {
        val plan = resolver.plan(
            tasks = emptyList(),
            habits = emptyList(),
            config = MorningCheckInConfig(),
            todayStart = millis(2026, 4, 11, 0)
        )
        assertFalse(plan.steps.contains(CheckInStep.HABITS))
    }

    @Test
    fun `medications step can be disabled via config`() {
        val plan = resolver.plan(
            tasks = emptyList(),
            habits = listOf(habit(1)),
            config = MorningCheckInConfig(includeMedications = false),
            todayStart = millis(2026, 4, 11, 0)
        )
        assertFalse(plan.steps.contains(CheckInStep.MEDICATIONS))
    }

    @Test
    fun `all enabled steps appear in default order`() {
        val plan = resolver.plan(
            tasks = emptyList(),
            habits = listOf(habit(1)),
            config = MorningCheckInConfig(),
            todayStart = millis(2026, 4, 11, 0)
        )
        assertEquals(
            listOf(
                CheckInStep.MOOD_ENERGY,
                CheckInStep.MEDICATIONS,
                CheckInStep.TOP_TASKS,
                CheckInStep.HABITS,
                CheckInStep.BALANCE,
                CheckInStep.CALENDAR
            ),
            plan.steps
        )
    }
}
