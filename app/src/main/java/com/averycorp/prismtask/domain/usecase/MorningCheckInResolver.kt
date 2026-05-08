package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.repository.HabitWithStatus

/**
 * A single step in the Morning Check-In guided flow (v1.4.0 V4).
 *
 * Steps are rendered as pages in a HorizontalPager. They carry only
 * the metadata needed to decide whether to show the step — the
 * actual body for each step is a dedicated composable that consumes
 * its data from the relevant repository.
 */
enum class CheckInStep {
    MOOD_ENERGY, // Optional V7 entry at the top
    MEDICATIONS,
    TOP_TASKS,
    HABITS,
    BALANCE, // Pro — burnout gauge + category bar
    CALENDAR // Free — today's events
}

/**
 * Configuration that drives which steps appear. Sourced from the
 * user's preferences and feature flags. Defaults match the vision
 * deck: all steps visible, 11am prompt threshold.
 */
data class MorningCheckInConfig(
    val enabled: Boolean = true,
    val includeMoodEnergy: Boolean = true,
    val includeMedications: Boolean = true,
    val includeTopTasks: Boolean = true,
    val includeHabits: Boolean = true,
    val includeBalance: Boolean = true,
    val includeCalendar: Boolean = true
)

/**
 * Snapshot of which check-in steps should appear and the task / habit
 * data each step renders. The Today-screen banner-visibility decision
 * lives in [MorningCheckInBannerDecider]; this plan describes only the
 * body of the guided flow once the user opens it.
 */
data class CheckInPlan(
    val steps: List<CheckInStep>,
    val topTasks: List<TaskEntity>,
    val todayHabits: List<HabitWithStatus>
)

/**
 * Pure-function planner for the morning check-in (v1.4.0 V4).
 *
 * Doesn't touch any state — feed it the raw snapshot of today's tasks,
 * habits, and config, and it returns a ready-to-render [CheckInPlan].
 * Repositories and ViewModels wire this up with their own Flows.
 */
class MorningCheckInResolver {
    fun plan(
        tasks: List<TaskEntity>,
        habits: List<HabitWithStatus>,
        config: MorningCheckInConfig,
        todayStart: Long
    ): CheckInPlan {
        if (!config.enabled) {
            return CheckInPlan(
                steps = emptyList(),
                topTasks = emptyList(),
                todayHabits = emptyList()
            )
        }

        // Top 3 tasks due today by highest urgency proxy (priority then due date).
        val topTasks = tasks
            .filter { !it.isCompleted && it.archivedAt == null && it.dueDate != null && it.dueDate < todayStart + DAY }
            .sortedWith(
                compareByDescending<TaskEntity> { it.priority }
                    .thenBy { it.dueDate ?: Long.MAX_VALUE }
            ).take(3)

        val steps = buildList {
            if (config.includeMoodEnergy) add(CheckInStep.MOOD_ENERGY)
            if (config.includeMedications) add(CheckInStep.MEDICATIONS)
            if (config.includeTopTasks) add(CheckInStep.TOP_TASKS)
            if (config.includeHabits && habits.isNotEmpty()) add(CheckInStep.HABITS)
            if (config.includeBalance) add(CheckInStep.BALANCE)
            if (config.includeCalendar) add(CheckInStep.CALENDAR)
        }

        return CheckInPlan(
            steps = steps,
            topTasks = topTasks,
            todayHabits = habits
        )
    }

    companion object {
        private const val DAY: Long = 24L * 60 * 60 * 1000
    }
}
