package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.TaskCompletionDao
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.data.local.entity.TaskCompletionEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.remote.SyncTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class TaskCompletionStats(
    val totalCompleted: Int = 0,
    val completionsByDate: Map<LocalDate, Int> = emptyMap(),
    val completionsByDayOfWeek: Map<DayOfWeek, Int> = emptyMap(),
    val completionsByHour: Map<Int, Int> = emptyMap(),
    val avgDaysToComplete: Double? = null,
    val overdueRate: Double? = null,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val completionRate7Day: Double = 0.0,
    val completionRate30Day: Double = 0.0,
    val bestDay: DayOfWeek? = null,
    val worstDay: DayOfWeek? = null,
    val peakHour: Int? = null
)

@Singleton
class TaskCompletionRepository
@Inject
constructor(private val taskCompletionDao: TaskCompletionDao, private val syncTracker: SyncTracker) {
    /**
     * Records a completion entry for [task]. When the parent carries a
     * recurrence rule, [spawnedRecurrenceId] is the id of the next-instance
     * row that was just inserted alongside this completion — stored on the
     * completion row so a later `TaskRepository.uncompleteTask` can find
     * and roll back the spawn even on the toggle-uncomplete path (no Undo
     * snackbar). Audit:
     * `docs/audits/RECURRING_TASKS_DUPLICATE_DAILY_AUDIT.md` (Item 2).
     */
    suspend fun recordCompletion(
        task: TaskEntity,
        tags: List<TagEntity>,
        spawnedRecurrenceId: Long? = null
    ): Long {
        val now = System.currentTimeMillis()
        val todayMidnight = normalizeToMidnight(now)
        val wasOverdue = task.dueDate != null && task.dueDate < todayMidnight
        val daysToComplete = computeDaysToComplete(task.createdAt, now)

        val completion = TaskCompletionEntity(
            taskId = task.id,
            projectId = task.projectId,
            completedDate = todayMidnight,
            completedAtTime = now,
            priority = task.priority,
            wasOverdue = wasOverdue,
            daysToComplete = daysToComplete,
            tags = if (tags.isNotEmpty()) tags.joinToString(",") { it.name } else null,
            spawnedRecurrenceId = spawnedRecurrenceId
        )
        val completionId = taskCompletionDao.insert(completion)
        syncTracker.trackCreate(completionId, "task_completion")
        return completionId
    }

    /**
     * Returns the most recent completion entry for [taskId] (or null if the
     * task has never been completed). Used by `uncompleteTask` to look up
     * the spawned next-instance link when the caller didn't provide one.
     */
    suspend fun getLatestCompletionForTask(taskId: Long): TaskCompletionEntity? =
        taskCompletionDao.getLatestCompletionForTask(taskId)

    /** Deletes a single completion entry by id, mirroring it through sync. */
    suspend fun deleteCompletionById(completionId: Long) {
        syncTracker.trackDelete(completionId, "task_completion")
        taskCompletionDao.deleteById(completionId)
    }

    suspend fun deleteCompletionsForTask(taskId: Long) {
        val completions = taskCompletionDao.getAllCompletionsOnce().filter { it.taskId == taskId }
        for (completion in completions) {
            syncTracker.trackDelete(completion.id, "task_completion")
        }
        taskCompletionDao.deleteByTaskId(taskId)
    }

    fun getCompletionStats(days: Int): Flow<TaskCompletionStats> {
        val now = System.currentTimeMillis()
        val startDate = normalizeToMidnight(now - TimeUnit.DAYS.toMillis(days.toLong()))
        val endDate = normalizeToMidnight(now) + TimeUnit.DAYS.toMillis(1) - 1

        return taskCompletionDao.getCompletionsInRange(startDate, endDate).map { completions ->
            buildStats(completions, days)
        }
    }

    fun getProjectStats(projectId: Long, days: Int): Flow<TaskCompletionStats> {
        val now = System.currentTimeMillis()
        val startDate = normalizeToMidnight(now - TimeUnit.DAYS.toMillis(days.toLong()))
        val endDate = normalizeToMidnight(now) + TimeUnit.DAYS.toMillis(1) - 1

        return taskCompletionDao.getCompletionsByProject(projectId, startDate, endDate).map { completions ->
            buildStats(completions, days)
        }
    }

    suspend fun getAllCompletionsOnce(): List<TaskCompletionEntity> =
        taskCompletionDao.getAllCompletionsOnce()

    private fun buildStats(completions: List<TaskCompletionEntity>, days: Int): TaskCompletionStats {
        if (completions.isEmpty()) {
            return TaskCompletionStats()
        }

        val today = LocalDate.now()
        val startDate = today.minusDays(days.toLong() - 1)

        // Completions by date
        val completionsByDate = mutableMapOf<LocalDate, Int>()
        var date = startDate
        while (!date.isAfter(today)) {
            completionsByDate[date] = 0
            date = date.plusDays(1)
        }
        for (c in completions) {
            val d = Instant
                .ofEpochMilli(c.completedDate)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            completionsByDate[d] = (completionsByDate[d] ?: 0) + 1
        }

        // Completions by day of week
        val completionsByDayOfWeek = mutableMapOf<DayOfWeek, Int>()
        for (c in completions) {
            val d = Instant
                .ofEpochMilli(c.completedDate)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            val dow = d.dayOfWeek
            completionsByDayOfWeek[dow] = (completionsByDayOfWeek[dow] ?: 0) + 1
        }

        // Completions by hour
        val completionsByHour = mutableMapOf<Int, Int>()
        for (c in completions) {
            val hour = Instant
                .ofEpochMilli(c.completedAtTime)
                .atZone(ZoneId.systemDefault())
                .hour
            completionsByHour[hour] = (completionsByHour[hour] ?: 0) + 1
        }

        // Average days to complete
        val daysValues = completions.mapNotNull { it.daysToComplete }
        val avgDaysToComplete = if (daysValues.isNotEmpty()) daysValues.average() else null

        // Overdue rate
        val overdueCount = completions.count { it.wasOverdue }
        val overdueRate = if (completions.isNotEmpty()) {
            overdueCount.toDouble() / completions.size * 100.0
        } else {
            null
        }

        // Streaks
        val datesWithCompletions = completions
            .map { Instant.ofEpochMilli(it.completedDate).atZone(ZoneId.systemDefault()).toLocalDate() }
            .toSet()
            .sorted()
        val currentStreak = calculateCurrentStreak(datesWithCompletions, today)
        val longestStreak = calculateLongestStreak(datesWithCompletions)

        // Completion rates
        val last7Days = completions.count {
            val d = Instant.ofEpochMilli(it.completedDate).atZone(ZoneId.systemDefault()).toLocalDate()
            !d.isBefore(today.minusDays(6))
        }
        val last30Days = completions.count {
            val d = Instant.ofEpochMilli(it.completedDate).atZone(ZoneId.systemDefault()).toLocalDate()
            !d.isBefore(today.minusDays(29))
        }
        val completionRate7Day = last7Days.toDouble() / 7.0
        val completionRate30Day = last30Days.toDouble() / 30.0

        // Best/worst day
        val bestDay = if (completionsByDayOfWeek.isNotEmpty()) {
            completionsByDayOfWeek.maxByOrNull { it.value }?.key
        } else {
            null
        }
        val worstDay = if (completionsByDayOfWeek.isNotEmpty()) {
            DayOfWeek.entries.minByOrNull { completionsByDayOfWeek[it] ?: 0 }
        } else {
            null
        }

        // Peak hour
        val peakHour = if (completionsByHour.isNotEmpty()) {
            completionsByHour.maxByOrNull { it.value }?.key
        } else {
            null
        }

        return TaskCompletionStats(
            totalCompleted = completions.size,
            completionsByDate = completionsByDate,
            completionsByDayOfWeek = completionsByDayOfWeek,
            completionsByHour = completionsByHour,
            avgDaysToComplete = avgDaysToComplete,
            overdueRate = overdueRate,
            currentStreak = currentStreak,
            longestStreak = longestStreak,
            completionRate7Day = completionRate7Day,
            completionRate30Day = completionRate30Day,
            bestDay = bestDay,
            worstDay = worstDay,
            peakHour = peakHour
        )
    }

    companion object {
        fun normalizeToMidnight(epochMillis: Long): Long {
            val localDate = Instant
                .ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            return localDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }

        fun computeDaysToComplete(createdAt: Long, completedAt: Long): Int? {
            if (createdAt <= 0) return null
            val diffMillis = completedAt - createdAt
            return TimeUnit.MILLISECONDS
                .toDays(diffMillis)
                .toInt()
                .coerceAtLeast(0)
        }

        fun calculateCurrentStreak(sortedDates: List<LocalDate>, today: LocalDate): Int {
            if (sortedDates.isEmpty()) return 0
            val dateSet = sortedDates.toSet()
            if (!dateSet.contains(today)) return 0

            var streak = 0
            var current = today
            while (dateSet.contains(current)) {
                streak++
                current = current.minusDays(1)
            }
            return streak
        }

        fun calculateLongestStreak(sortedDates: List<LocalDate>): Int {
            if (sortedDates.isEmpty()) return 0
            val uniqueSorted = sortedDates.toSet().sorted()
            var longest = 1
            var current = 1
            for (i in 1 until uniqueSorted.size) {
                if (uniqueSorted[i] == uniqueSorted[i - 1].plusDays(1)) {
                    current++
                    if (current > longest) longest = current
                } else {
                    current = 1
                }
            }
            return longest
        }
    }
}
