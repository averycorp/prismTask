package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.util.DayBoundary
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates a one-off Today task per habit that has `createDailyTask = true`,
 * provided the habit is scheduled for the current logical day and no
 * non-archived task already exists for it on that day. Idempotent — calling
 * twice on the same day produces at most one task per habit.
 *
 * Called from [com.averycorp.prismtask.workers.DailyResetWorker] at start of
 * day, and from `TodayViewModel.init` so users who launch the app after the
 * boundary still see their habit tasks immediately.
 */
@Singleton
class HabitDailyTaskGenerator
@Inject
constructor(
    private val habitDao: HabitDao,
    private val taskDao: TaskDao,
    private val completionDao: HabitCompletionDao,
    private val taskRepository: TaskRepository,
    private val taskBehaviorPreferences: TaskBehaviorPreferences
) {
    suspend fun ensureTasksForToday(now: Long = System.currentTimeMillis()): Int {
        val dayStartHour = taskBehaviorPreferences.getDayStartHour().first()
        val startOfDay = DayBoundary.startOfCurrentDay(dayStartHour, now)
        val endOfDay = startOfDay + DayBoundary.DAY_MILLIS
        val todayLocal = DayBoundary.currentLocalDate(dayStartHour, now)
        val todayLocalString = todayLocal.toString()

        val habits = habitDao.getActiveHabitsOnce()
        var created = 0
        for (habit in habits) {
            if (!habit.createDailyTask) continue
            if (!isScheduledOn(habit, todayLocal)) continue

            val target = if (habit.frequencyPeriod == "daily") habit.targetFrequency else 1
            val completionCount = completionDao.getCompletionCountForDateLocalOnce(
                habit.id,
                todayLocalString
            )
            if (completionCount >= target) continue

            val existing = taskDao.getLatestHabitTaskForDayOnce(habit.id, startOfDay, endOfDay)
            if (existing != null) continue

            taskRepository.insertTask(
                TaskEntity(
                    title = habit.name,
                    description = habit.description,
                    dueDate = startOfDay,
                    sourceHabitId = habit.id,
                    createdAt = now,
                    updatedAt = now
                )
            )
            created++
        }
        return created
    }

    private fun isScheduledOn(habit: HabitEntity, date: LocalDate): Boolean {
        val days = parseActiveDays(habit.activeDays)
        return when (habit.frequencyPeriod) {
            "daily" -> days.isEmpty() || date.dayOfWeek in days
            "weekly" -> days.isNotEmpty() && date.dayOfWeek in days
            else -> false
        }
    }

    private fun parseActiveDays(json: String?): Set<DayOfWeek> {
        if (json.isNullOrBlank()) return emptySet()
        return try {
            json.trim('[', ']')
                .split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .mapNotNull { calendarDayToDayOfWeek(it) }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun calendarDayToDayOfWeek(calendarDay: Int): DayOfWeek? = when (calendarDay) {
        Calendar.SUNDAY -> DayOfWeek.SUNDAY
        Calendar.MONDAY -> DayOfWeek.MONDAY
        Calendar.TUESDAY -> DayOfWeek.TUESDAY
        Calendar.WEDNESDAY -> DayOfWeek.WEDNESDAY
        Calendar.THURSDAY -> DayOfWeek.THURSDAY
        Calendar.FRIDAY -> DayOfWeek.FRIDAY
        Calendar.SATURDAY -> DayOfWeek.SATURDAY
        else -> null
    }

    @Suppress("unused")
    private fun localDateFromMillis(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
}
