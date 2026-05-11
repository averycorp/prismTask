package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.TaskEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single point on the burndown curve. [actualRemaining] is null for any
 * day after "today" — those days only show the ideal line.
 */
data class BurndownPoint(val date: LocalDate, val actualRemaining: Int?, val idealRemaining: Double)

data class ProjectBurndown(
    val points: List<BurndownPoint>,
    /** Tasks done in the last [VELOCITY_WINDOW_DAYS] days, divided by the window. */
    val velocityPerDay: Double,
    /** Projected completion date assuming current velocity holds. `null` if velocity is zero. */
    val projectedCompletion: LocalDate?,
    val totalTasks: Int,
    val remainingTasks: Int
) {
    companion object {
        const val VELOCITY_WINDOW_DAYS = 7
    }
}

/**
 * Computes a project burndown curve from its task list. Pure function —
 * no DB, no clock — so it's straightforward to fixture and test.
 *
 * Conventions:
 *  - The "start" date is [overrideStart], or the project's `start_date`
 *    column, or the earliest task `createdAt` (whichever is non-null,
 *    in that order).
 *  - The "end" date is [overrideEnd], or the project's `end_date`, or
 *    `start + 14 days` as a sensible default.
 *  - The actual line counts tasks NOT completed by EOD on the given
 *    day. The ideal line is a straight line from `totalTasks` at start
 *    to `0` at end.
 */
@Singleton
class ProjectBurndownComputer @Inject constructor() {

    fun compute(
        tasks: List<TaskEntity>,
        zone: ZoneId,
        today: LocalDate,
        projectStart: LocalDate?,
        projectEnd: LocalDate?
    ): ProjectBurndown? {
        if (tasks.isEmpty()) return null

        val resolvedStart = projectStart
            ?: tasks.mapNotNull { it.createdAt.takeIf { ts -> ts > 0L } }
                .minOrNull()
                ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            ?: today
        val resolvedEnd = projectEnd
            ?: resolvedStart.plusDays(DEFAULT_PROJECT_LENGTH_DAYS)

        if (!resolvedEnd.isAfter(resolvedStart)) return null

        val totalTasks = tasks.size
        val daySpan = resolvedEnd.toEpochDay() - resolvedStart.toEpochDay()
        val points = mutableListOf<BurndownPoint>()

        var cursor = resolvedStart
        while (!cursor.isAfter(resolvedEnd)) {
            val daysIn = cursor.toEpochDay() - resolvedStart.toEpochDay()
            val ideal = (totalTasks.toDouble() * (1.0 - daysIn.toDouble() / daySpan.toDouble()))
                .coerceAtLeast(0.0)
            val actual = if (cursor.isAfter(today)) {
                null
            } else {
                tasksRemainingAfter(tasks, cursor, zone)
            }
            points += BurndownPoint(
                date = cursor,
                actualRemaining = actual,
                idealRemaining = ideal
            )
            cursor = cursor.plusDays(1)
        }

        val velocityPerDay = computeVelocity(tasks = tasks, today = today, zone = zone)
        val remainingTasks = points.lastOrNull { it.actualRemaining != null }
            ?.actualRemaining
            ?: tasksRemainingAfter(tasks, today, zone)
        val projected = if (velocityPerDay > 0.0 && remainingTasks > 0) {
            today.plusDays(Math.ceil(remainingTasks / velocityPerDay).toLong())
        } else if (remainingTasks == 0) {
            today
        } else {
            null
        }

        return ProjectBurndown(
            points = points,
            velocityPerDay = velocityPerDay,
            projectedCompletion = projected,
            totalTasks = totalTasks,
            remainingTasks = remainingTasks
        )
    }

    private fun tasksRemainingAfter(
        tasks: List<TaskEntity>,
        endOfDay: LocalDate,
        zone: ZoneId
    ): Int {
        val cutoff = endOfDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return tasks.count { task ->
            val completedTs = task.completedAt
            !task.isCompleted || (completedTs != null && completedTs >= cutoff)
        }
    }

    private fun computeVelocity(
        tasks: List<TaskEntity>,
        today: LocalDate,
        zone: ZoneId
    ): Double {
        val windowStart = today.minusDays(ProjectBurndown.VELOCITY_WINDOW_DAYS.toLong() - 1)
        val startMs = windowStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMsExclusive = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val completedInWindow = tasks.count { task ->
            val ts = task.completedAt ?: return@count false
            task.isCompleted && ts in startMs until endMsExclusive
        }
        return completedInWindow.toDouble() / ProjectBurndown.VELOCITY_WINDOW_DAYS.toDouble()
    }

    companion object {
        const val DEFAULT_PROJECT_LENGTH_DAYS = 14L
    }
}
