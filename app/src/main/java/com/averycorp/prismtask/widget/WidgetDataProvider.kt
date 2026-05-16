package com.averycorp.prismtask.widget

import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.averycorp.prismtask.data.local.database.PrismTaskDatabase
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.preferences.AdvancedTuningPreferences
import com.averycorp.prismtask.data.preferences.CustomBrainModePreferences
import com.averycorp.prismtask.data.preferences.NdPreferencesDataStore
import com.averycorp.prismtask.data.preferences.ProductivityWidgetThresholds
import com.averycorp.prismtask.data.preferences.effectiveNdPreferencesFlow
import com.averycorp.prismtask.data.preferences.taskBehaviorDataStore
import com.averycorp.prismtask.data.preferences.themePrefsDataStore
import com.averycorp.prismtask.util.DayBoundary
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val DAY_START_HOUR_KEY = intPreferencesKey("day_start_hour")
private val DAY_START_MINUTE_KEY = intPreferencesKey("day_start_minute")
private val ACCENT_COLOR_KEY = stringPreferencesKey("accent_color")
private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")

private suspend fun Context.readDayStartHour(): Int =
    taskBehaviorDataStore.data.map { it[DAY_START_HOUR_KEY] ?: 0 }.first()

private suspend fun Context.readDayStartMinute(): Int =
    taskBehaviorDataStore.data.map { it[DAY_START_MINUTE_KEY] ?: 0 }.first()

/** Reads the user's configured accent color hex for use in widgets. */
suspend fun Context.readAccentColor(): String =
    themePrefsDataStore.data.map { it[ACCENT_COLOR_KEY] ?: "#2563EB" }.first()

/** Reads the user's theme mode preference (system/light/dark). */
suspend fun Context.readThemeMode(): String =
    themePrefsDataStore.data.map { it[THEME_MODE_KEY] ?: "system" }.first()

data class WidgetTaskRow(
    val id: Long,
    val title: String,
    val priority: Int,
    val dueDate: Long?,
    val isCompleted: Boolean,
    val isOverdue: Boolean
)

data class TodayWidgetData(
    val totalTasks: Int,
    val completedTasks: Int,
    val tasks: List<WidgetTaskRow>,
    val totalHabits: Int,
    val completedHabits: Int,
    val habitIcons: List<String>,
    val productivityScore: Int
)

data class HabitWidgetData(
    val habits: List<HabitWidgetItem>,
    val longestStreak: Int
)

data class HabitWidgetItem(
    val id: Long,
    val name: String,
    val icon: String,
    val streak: Int,
    val isCompletedToday: Boolean,
    val last7Days: List<Boolean> = emptyList()
)

data class UpcomingWidgetData(
    val overdue: List<WidgetTaskRow>,
    val today: List<WidgetTaskRow>,
    val tomorrow: List<WidgetTaskRow>,
    val dayAfter: List<WidgetTaskRow>
) {
    val totalCount: Int get() = overdue.size + today.size + tomorrow.size + dayAfter.size
}

data class ProductivityWidgetData(
    val score: Int,
    val completed: Int,
    val total: Int,
    val trendPoints: Int
)

data class TemplateShortcut(
    val id: Long,
    val name: String,
    val icon: String
)

/**
 * Snapshot of a single project for the [ProjectWidget]. `nextDueTaskTitle`
 * is only populated when the project has no upcoming milestones — the
 * widget falls back to it per Phase 3 spec.
 */
data class EisenhowerWidgetData(
    val q1: EisenhowerQuadrantSummary,
    val q2: EisenhowerQuadrantSummary,
    val q3: EisenhowerQuadrantSummary,
    val q4: EisenhowerQuadrantSummary
) {
    val total: Int get() = q1.count + q2.count + q3.count + q4.count
}

data class EisenhowerQuadrantSummary(
    val count: Int,
    val topTaskTitle: String?,
    // Priority + due date for the top task so the widget can render the
    // same priority dot + due-date hint the in-app CompactTaskCard shows.
    // Both null when [topTaskTitle] is null or the underlying field was unset.
    val topTaskPriority: Int? = null,
    val topTaskDueDate: Long? = null
)

data class InboxWidgetData(
    val items: List<InboxWidgetItem>
)

data class InboxWidgetItem(
    val id: Long,
    val title: String,
    val ageLabel: String,
    val priority: Int
)

data class StatsSparklineWidgetData(
    val thisWeek: List<Int>,
    val lastWeek: List<Int>,
    val total: Int,
    val lastTotal: Int,
    val deltaPct: Int,
    val up: Boolean
)

data class StreakCalendarWidgetData(
    val intensities: List<Int>,
    val activeDays: Int,
    val longestStreak: Int,
    val weeks: Int
)

data class MedicationWidgetData(
    val slots: List<MedicationWidgetSlot>,
    val totalDoses: Int,
    val takenDoses: Int,
    val nextSlotIndex: Int,
    /**
     * Lowest projected days-remaining across every refill row, or null if
     * the user hasn't configured any refill metadata. [MedicationWidget]
     * surfaces a badge when this drops below the user-configured
     * `reminderDaysBefore` threshold; the widget treats null as "no
     * warning to render".
     */
    val lowestRefillDaysRemaining: Int? = null,
    val lowestRefillMedicationName: String? = null
) {
    val nextSlot: MedicationWidgetSlot? get() = slots.getOrNull(nextSlotIndex)
    val hasRefillWarning: Boolean
        get() = lowestRefillDaysRemaining != null && lowestRefillDaysRemaining <= 7
}

data class MedicationWidgetSlot(
    val slotId: Long,
    val name: String,
    val time: String,
    val tier: MedicationWidgetTier,
    val taken: Int,
    val total: Int,
    val active: Boolean
)

enum class MedicationWidgetTier { ESSENTIAL, PRESCRIPTION, COMPLETE, SKIPPED }

data class ProjectWidgetData(
    val projectId: Long,
    val name: String,
    val icon: String,
    val themeColorHex: String,
    val status: String,
    val milestoneProgress: Float,
    val completedMilestones: Int,
    val totalMilestones: Int,
    val upcomingMilestoneTitle: String?,
    val nextDueTaskTitle: String?,
    val totalTasks: Int,
    val openTasks: Int,
    val streak: Int,
    val daysSinceActivity: Int?
)

object WidgetDataProvider {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetDatabaseEntryPoint {
        fun database(): PrismTaskDatabase
        fun advancedTuningPreferences(): AdvancedTuningPreferences
        fun ndPreferencesDataStore(): NdPreferencesDataStore
        fun customBrainModePreferences(): CustomBrainModePreferences
    }

    // Reuse the Hilt-provided singleton DB so we share the same
    // InvalidationTracker and avoid opening a second Room instance per
    // widget refresh (which races with the app's singleton on close()
    // and can fail during migration windows).
    private fun getDb(context: Context): PrismTaskDatabase =
        entryPoint(context).database()

    private fun entryPoint(context: Context): WidgetDatabaseEntryPoint =
        EntryPointAccessors
            .fromApplication(context.applicationContext, WidgetDatabaseEntryPoint::class.java)

    /** Reads the user's productivity widget thresholds from advanced tuning prefs. */
    suspend fun getProductivityWidgetThresholds(context: Context): ProductivityWidgetThresholds =
        entryPoint(context).advancedTuningPreferences()
            .getProductivityWidgetThresholds()
            .first()

    /**
     * Reads the user's effective `quietMode` flag (base NdPreferences
     * with the active [com.averycorp.prismtask.data.preferences
     * .CustomBrainMode] overlay applied). Used by widget empty-state
     * rendering (E4) to decide whether to show a celebratory emoji or
     * fall back to a neutral glyph — a user with quietMode forced on
     * via an active custom mode sees the neutral glyph immediately,
     * without having to toggle the base setting.
     */
    suspend fun getQuietMode(context: Context): Boolean =
        try {
            val ep = entryPoint(context)
            ep.ndPreferencesDataStore()
                .effectiveNdPreferencesFlow(ep.customBrainModePreferences())
                .first()
                .quietMode
        } catch (_: Exception) {
            false
        }

    private fun TaskEntity.toRow(startOfDay: Long): WidgetTaskRow = WidgetTaskRow(
        id = id,
        title = title,
        priority = priority,
        dueDate = dueDate,
        isCompleted = isCompleted,
        isOverdue = !isCompleted && (dueDate ?: Long.MAX_VALUE) < startOfDay
    )

    suspend fun getTodayData(
        context: Context,
        now: Long = System.currentTimeMillis(),
        maxTasks: Int = 8
    ): TodayWidgetData {
        val db = getDb(context)
        val dayStartHour = context.readDayStartHour()
        val dayStartMinute = context.readDayStartMinute()
        val startOfDay = DayBoundary.startOfCurrentDay(
            dayStartHour = dayStartHour,
            now = now,
            dayStartMinute = dayStartMinute
        )
        val startOfDayLocal = epochToLocalDateString(startOfDay)
        val endOfDay = startOfDay + DayBoundary.DAY_MILLIS
        val taskDao = db.taskDao()
        val todayTasks = taskDao.getTodayTasksOnce(startOfDay, endOfDay)
        val overdueTasks = taskDao.getOverdueRootTasksOnce(startOfDay)
        val allTasks = overdueTasks + todayTasks
        val completedToday = taskDao.getCompletedTodayOnce(startOfDay)
        val habitDao = db.habitDao()
        val habits = habitDao.getActiveHabitsOnce()
        val completionDao = db.habitCompletionDao()
        val completedHabits = habits.count { completionDao.isCompletedOnDateLocalOnce(it.id, startOfDayLocal) }
        val totalForScore = allTasks.size + completedToday.size + habits.size
        val completedForScore = completedToday.size + completedHabits
        val productivityScore = if (totalForScore > 0) {
            ((completedForScore * 100f) / totalForScore).toInt().coerceIn(0, 100)
        } else {
            0
        }
        val taskFetchCap = maxTasks.coerceIn(1, 20)
        return TodayWidgetData(
            totalTasks = allTasks.size + completedToday.size,
            completedTasks = completedToday.size,
            tasks = (completedToday + allTasks).take(taskFetchCap).map { it.toRow(startOfDay) },
            totalHabits = habits.size,
            completedHabits = completedHabits,
            habitIcons = habits.take(6).map { it.icon },
            productivityScore = productivityScore
        )
    }

    suspend fun getHabitData(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): HabitWidgetData {
        val db = getDb(context)
        val habitDao = db.habitDao()
        val completionDao = db.habitCompletionDao()
        val habits = habitDao.getActiveHabitsOnce()
        val dayStartHour = context.readDayStartHour()
        val dayStartMinute = context.readDayStartMinute()
        val startOfDay = DayBoundary.startOfCurrentDay(
            dayStartHour = dayStartHour,
            now = now,
            dayStartMinute = dayStartMinute
        )
        val startOfDayLocal = epochToLocalDate(startOfDay)
        var longestStreak = 0
        val items = habits.take(12).map { habit ->
            val isCompleted = completionDao.isCompletedOnDateLocalOnce(habit.id, startOfDayLocal.toString())
            val streak = computeCurrentStreak(completionDao, habit.id, startOfDayLocal)
            if (streak > longestStreak) longestStreak = streak
            val last7Days = (6 downTo 0).map { daysAgo ->
                completionDao.isCompletedOnDateLocalOnce(
                    habit.id,
                    startOfDayLocal.minusDays(daysAgo.toLong()).toString()
                )
            }
            HabitWidgetItem(habit.id, habit.name, habit.icon, streak, isCompleted, last7Days)
        }
        return HabitWidgetData(habits = items, longestStreak = longestStreak)
    }

    private suspend fun computeCurrentStreak(
        completionDao: com.averycorp.prismtask.data.local.dao.HabitCompletionDao,
        habitId: Long,
        startOfDayLocal: LocalDate
    ): Int {
        var streak = 0
        var date = startOfDayLocal
        val todayDone = completionDao.isCompletedOnDateLocalOnce(habitId, date.toString())
        if (!todayDone) date = date.minusDays(1)
        while (completionDao.isCompletedOnDateLocalOnce(habitId, date.toString())) {
            streak++
            date = date.minusDays(1)
            if (streak > 365) break
        }
        return streak
    }

    suspend fun getUpcomingData(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): UpcomingWidgetData {
        val db = getDb(context)
        val dayStartHour = context.readDayStartHour()
        val dayStartMinute = context.readDayStartMinute()
        val startOfDay = DayBoundary.startOfCurrentDay(
            dayStartHour = dayStartHour,
            now = now,
            dayStartMinute = dayStartMinute
        )
        val endOfDay = startOfDay + DayBoundary.DAY_MILLIS
        val taskDao = db.taskDao()
        return UpcomingWidgetData(
            overdue = taskDao.getOverdueRootTasksOnce(startOfDay).take(3).map { it.toRow(startOfDay) },
            today = taskDao.getTodayTasksOnce(startOfDay, endOfDay).take(5).map { it.toRow(startOfDay) },
            tomorrow = taskDao.getTodayTasksOnce(endOfDay, endOfDay + DayBoundary.DAY_MILLIS).take(5).map { it.toRow(startOfDay) },
            dayAfter = taskDao.getTodayTasksOnce(endOfDay + DayBoundary.DAY_MILLIS, endOfDay + 2 * DayBoundary.DAY_MILLIS).take(5).map {
                it.toRow(startOfDay)
            }
        )
    }

    suspend fun getProductivityData(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): ProductivityWidgetData {
        val db = getDb(context)
        val dayStartHour = context.readDayStartHour()
        val dayStartMinute = context.readDayStartMinute()
        val startOfDay = DayBoundary.startOfCurrentDay(
            dayStartHour = dayStartHour,
            now = now,
            dayStartMinute = dayStartMinute
        )
        val endOfDay = startOfDay + DayBoundary.DAY_MILLIS
        val taskDao = db.taskDao()
        val todayTasks = taskDao.getTodayTasksOnce(startOfDay, endOfDay)
        val overdue = taskDao.getOverdueRootTasksOnce(startOfDay)
        val completed = taskDao.getCompletedTodayOnce(startOfDay)
        val totalTasks = todayTasks.size + overdue.size + completed.size
        val habitDao = db.habitDao()
        val completionDao = db.habitCompletionDao()
        val habits = habitDao.getActiveHabitsOnce()
        val startOfDayLocal = epochToLocalDateString(startOfDay)
        val completedHabits = habits.count { completionDao.isCompletedOnDateLocalOnce(it.id, startOfDayLocal) }
        val total = totalTasks + habits.size
        val done = completed.size + completedHabits
        val score = if (total > 0) ((done * 100f) / total).toInt().coerceIn(0, 100) else 0
        val yesterdayStart = startOfDay - DayBoundary.DAY_MILLIS
        val prevCompleted = taskDao.getCompletedTodayOnce(yesterdayStart).count { (it.completedAt ?: 0) < startOfDay }
        val prevScore = if (total > 0) ((prevCompleted * 100f) / total).toInt() else 0
        return ProductivityWidgetData(score = score, completed = done, total = total, trendPoints = score - prevScore)
    }

    /**
     * Snapshot of the configured project for [ProjectWidget]. Returns null
     * when the project doesn't exist (e.g. user deleted it after placing
     * the widget) — the widget renders an empty state in that case.
     */
    suspend fun getProjectData(
        context: Context,
        projectId: Long,
        now: Long = System.currentTimeMillis()
    ): ProjectWidgetData? {
        if (projectId <= 0) return null
        val db = getDb(context)
        val project = db.projectDao().getProjectByIdOnce(projectId) ?: return null
        val aggregate = db.projectDao().getAggregateRow(projectId)
        val milestoneTimestamps = db.milestoneDao().getCompletedTimestamps(projectId)
        val taskDates = db.projectDao().getTaskActivityDates(projectId)

        val activityDates = (taskDates + milestoneTimestamps)
            .map { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
            .toSet()
        val streak = com.averycorp.prismtask.domain.usecase.DailyForgivenessStreakCore
            .calculate(activityDates).resilientStreak

        val lastActivity = (taskDates + milestoneTimestamps).maxOrNull()
        val daysSince = lastActivity?.let {
            val diff = now - it
            if (diff <= 0) 0 else (diff / DayBoundary.DAY_MILLIS).toInt()
        }

        // Fallback: if the project has no upcoming milestones, surface the
        // earliest open task on the project as the widget's headline item.
        val upcomingMilestoneTitle = aggregate?.upcomingMilestoneTitle
        val nextDueTaskTitle = if (upcomingMilestoneTitle == null) {
            db.taskDao().getTasksByProjectOnce(projectId)
                .asSequence()
                .filter { !it.isCompleted && it.parentTaskId == null && it.archivedAt == null }
                .sortedWith(compareBy({ it.dueDate ?: Long.MAX_VALUE }, { -it.priority }))
                .firstOrNull()
                ?.title
        } else {
            null
        }

        return ProjectWidgetData(
            projectId = project.id,
            name = project.name,
            icon = project.icon,
            themeColorHex = project.themeColorKey ?: project.color,
            status = project.status,
            milestoneProgress = aggregate
                ?.takeIf { it.totalMilestones > 0 }
                ?.let { (it.completedMilestones.toFloat() / it.totalMilestones).coerceIn(0f, 1f) }
                ?: 0f,
            completedMilestones = aggregate?.completedMilestones ?: 0,
            totalMilestones = aggregate?.totalMilestones ?: 0,
            upcomingMilestoneTitle = upcomingMilestoneTitle,
            nextDueTaskTitle = nextDueTaskTitle,
            totalTasks = aggregate?.totalTasks ?: 0,
            openTasks = aggregate?.openTasks ?: 0,
            streak = streak,
            daysSinceActivity = daysSince
        )
    }

    /**
     * Eisenhower quadrant counts + the highest-priority task title in each
     * quadrant. Pulled from `tasks.eisenhower_quadrant` (Q1..Q4 codes); rows
     * with a null code roll up into [EisenhowerQuadrant.UNCLASSIFIED] which
     * the widget doesn't render — only the four canonical quadrants surface.
     */
    suspend fun getEisenhowerData(context: Context): EisenhowerWidgetData {
        val db = getDb(context)
        val tasks = db.taskDao().getIncompleteRootTasksOnce()
            .filter { it.archivedAt == null }
        fun summarize(code: String): EisenhowerQuadrantSummary {
            val matches = tasks.filter { it.eisenhowerQuadrant == code }
            val top = matches
                .sortedWith(compareByDescending<TaskEntity> { it.priority }.thenBy { it.dueDate ?: Long.MAX_VALUE })
                .firstOrNull()
            return EisenhowerQuadrantSummary(
                count = matches.size,
                topTaskTitle = top?.title,
                topTaskPriority = top?.priority,
                topTaskDueDate = top?.dueDate
            )
        }
        return EisenhowerWidgetData(
            q1 = summarize("Q1"),
            q2 = summarize("Q2"),
            q3 = summarize("Q3"),
            q4 = summarize("Q4")
        )
    }

    /**
     * Inbox snapshot for [InboxWidget]. Returns up to [limit] root tasks that
     * are unfiled (no project) and unscheduled (no due date), ordered most
     * recently captured first. Age labels are formatted relative to [now].
     */
    suspend fun getInboxData(
        context: Context,
        limit: Int = 6,
        now: Long = System.currentTimeMillis()
    ): InboxWidgetData {
        val db = getDb(context)
        val candidates = db.taskDao().getInboxCandidatesOnce(limit.coerceIn(1, 20))
        return InboxWidgetData(
            items = candidates.map {
                InboxWidgetItem(
                    id = it.id,
                    title = it.title,
                    ageLabel = formatRelativeAge(now - it.createdAt),
                    priority = it.priority
                )
            }
        )
    }

    /**
     * Per-day completion counts for the current week + previous week. Both
     * lists run oldest → today (length 7); today's bucket is the last entry.
     * `up` is true when this-week's total >= last-week's total. Used by
     * [StatsSparklineWidget] to render a 7-bar chart + ▲/▼ delta header.
     */
    suspend fun getStatsSparklineData(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): StatsSparklineWidgetData {
        val db = getDb(context)
        val dayStartHour = context.readDayStartHour()
        val dayStartMinute = context.readDayStartMinute()
        val startOfToday = DayBoundary.startOfCurrentDay(
            dayStartHour = dayStartHour,
            now = now,
            dayStartMinute = dayStartMinute
        )
        // 14 buckets covering [today - 13 days, today], aligned to user SoD.
        val startOfWindow = startOfToday - 13L * DayBoundary.DAY_MILLIS
        val endOfWindow = startOfToday + DayBoundary.DAY_MILLIS
        val rows = db.taskCompletionDao()
            .getCompletionCountByDate(startOfWindow, endOfWindow - 1)
            .first()
        // Map epoch-day-bucket → count. Room returns DateCount(date=epochMillis, count=N)
        // grouped by midnight UTC, but we need user-SoD buckets. Re-bucket in Kotlin.
        val bucketed = IntArray(14)
        rows.forEach { row ->
            val deltaDays = ((row.date - startOfWindow) / DayBoundary.DAY_MILLIS).toInt()
            if (deltaDays in 0..13) bucketed[deltaDays] += row.count
        }
        val lastWeek = bucketed.slice(0..6)
        val thisWeek = bucketed.slice(7..13)
        val total = thisWeek.sum()
        val lastTotal = lastWeek.sum()
        val deltaPct = if (lastTotal > 0) {
            (((total - lastTotal).toFloat() / lastTotal) * 100).toInt()
        } else if (total > 0) {
            100
        } else {
            0
        }
        return StatsSparklineWidgetData(
            thisWeek = thisWeek,
            lastWeek = lastWeek,
            total = total,
            lastTotal = lastTotal,
            deltaPct = deltaPct,
            up = total >= lastTotal
        )
    }

    /**
     * Heatmap data for [StreakCalendarWidget]. Returns one intensity bucket
     * (0..4) per day across the requested [weeks] window, ordered by week
     * then day-of-week. Buckets are derived from per-day completion counts:
     *  - 0: no completions
     *  - 1: 1 completion
     *  - 2: 2-3 completions
     *  - 3: 4-5 completions
     *  - 4: 6+ completions
     * Also returns the longest current streak (consecutive days with ≥1
     * completion ending at today, or just before today if today is empty).
     */
    suspend fun getStreakCalendarData(
        context: Context,
        weeks: Int = 12,
        now: Long = System.currentTimeMillis()
    ): StreakCalendarWidgetData {
        val totalDays = (weeks * 7).coerceAtLeast(7)
        val db = getDb(context)
        val dayStartHour = context.readDayStartHour()
        val dayStartMinute = context.readDayStartMinute()
        val startOfToday = DayBoundary.startOfCurrentDay(
            dayStartHour = dayStartHour,
            now = now,
            dayStartMinute = dayStartMinute
        )
        val startOfWindow = startOfToday - (totalDays - 1L) * DayBoundary.DAY_MILLIS
        val endOfWindow = startOfToday + DayBoundary.DAY_MILLIS
        val rows = db.habitCompletionDao()
            .getAllCompletionsInRange(startOfWindow, endOfWindow - 1)
            .first()
        val perDay = IntArray(totalDays)
        rows.forEach { row ->
            val deltaDays = ((row.completedDate - startOfWindow) / DayBoundary.DAY_MILLIS).toInt()
            if (deltaDays in 0 until totalDays) perDay[deltaDays] += 1
        }
        val intensities = perDay.map { count ->
            when {
                count <= 0 -> 0
                count == 1 -> 1
                count <= 3 -> 2
                count <= 5 -> 3
                else -> 4
            }
        }
        val activeDays = perDay.count { it > 0 }
        // Streak: walk back from today; if today empty, allow starting at yesterday.
        var longest = 0
        var idx = totalDays - 1
        if (idx >= 0 && perDay[idx] == 0) idx -= 1
        while (idx >= 0 && perDay[idx] > 0) {
            longest += 1
            idx -= 1
        }
        return StreakCalendarWidgetData(
            intensities = intensities,
            activeDays = activeDays,
            longestStreak = longest,
            weeks = weeks
        )
    }

    /**
     * Per-slot dose progress for today, used by [MedicationWidget]. Slots
     * are sorted by ideal time; each slot's `taken` count is the number of
     * non-synthetic doses with a `slot_key` matching the slot's name (case
     * insensitive) on today's local date. The "next" slot is the first
     * active slot whose ideal time hasn't passed *and* still has unfilled
     * doses; falls back to the first under-filled active slot.
     */
    suspend fun getMedicationData(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): MedicationWidgetData {
        val db = getDb(context)
        val slotDao = db.medicationSlotDao()
        val medDao = db.medicationDao()
        val doseDao = db.medicationDoseDao()
        val activeSlots = slotDao.getActiveOnce().sortedBy { it.idealTime }
        val activeMeds = medDao.getActiveOnce()
        val medIdToSlotIds: Map<Long, List<Long>> = activeMeds.associate { med ->
            med.id to slotDao.getSlotIdsForMedicationOnce(med.id)
        }
        val dayStartHour = context.readDayStartHour()
        val dayStartMinute = context.readDayStartMinute()
        val startOfToday = DayBoundary.startOfCurrentDay(
            dayStartHour = dayStartHour,
            now = now,
            dayStartMinute = dayStartMinute
        )
        val todayLocal = epochToLocalDateString(startOfToday)
        val doses = doseDao.getForDateOnce(todayLocal).filter { !it.isSyntheticSkip }
        val nowMinutes = ((now - startOfToday) / 60_000L).toInt()
        val slots = activeSlots.map { slot ->
            val medsInSlot = activeMeds.count { (medIdToSlotIds[it.id] ?: emptyList()).contains(slot.id) }
            val takenInSlot = doses.count { it.slotKey.equals(slot.name, ignoreCase = true) }
            val tier = when {
                !slot.isActive -> MedicationWidgetTier.SKIPPED
                medsInSlot > 0 && takenInSlot >= medsInSlot -> MedicationWidgetTier.COMPLETE
                slot.name.contains("prescription", ignoreCase = true) -> MedicationWidgetTier.PRESCRIPTION
                else -> MedicationWidgetTier.ESSENTIAL
            }
            MedicationWidgetSlot(
                slotId = slot.id,
                name = slot.name,
                time = slot.idealTime,
                tier = tier,
                taken = takenInSlot,
                total = medsInSlot,
                active = slot.isActive
            )
        }
        val totalDoses = slots.sumOf { it.total }
        val takenDoses = slots.sumOf { it.taken }
        val nextIndex = slots.indexOfFirst { slot ->
            slot.active && slot.taken < slot.total && minutesOfDay(slot.time) >= nowMinutes
        }.let {
            if (it >= 0) it else slots.indexOfFirst { s -> s.active && s.taken < s.total }
        }

        // Refill warning: surface the smallest projected days-remaining across
        // any refill row. RefillCalculator's exact projection lives in the
        // domain module; the widget only needs the headline value, so we use
        // the simple `pillCount / (pillsPerDose * dosesPerDay)` projection
        // inline. Anything <= 7 days is considered "warning-worthy" by the
        // widget; tighter thresholds are the in-app screen's job.
        val refills = try {
            db.medicationRefillDao().getAllOnce()
        } catch (_: Exception) {
            emptyList()
        }
        val lowestRefill = refills
            .mapNotNull { refill ->
                val perDay = (refill.pillsPerDose * refill.dosesPerDay).coerceAtLeast(1)
                val days = refill.pillCount / perDay
                if (days < 0) null else refill.medicationName to days
            }
            .minByOrNull { it.second }

        return MedicationWidgetData(
            slots = slots,
            totalDoses = totalDoses,
            takenDoses = takenDoses,
            nextSlotIndex = nextIndex,
            lowestRefillDaysRemaining = lowestRefill?.second,
            lowestRefillMedicationName = lowestRefill?.first
        )
    }

    /**
     * Logs an outstanding dose for every active medication wired to [slotId]
     * that does not yet have a non-synthetic dose on today's local date.
     * Used by [MarkDoseTakenFromWidgetAction]; mirrors the in-app
     * "mark slot taken" flow without going through SyncTracker — the next
     * full sync pass picks up the inserted rows by their `updatedAt`
     * watermark. The widget refresh is dispatched by the calling action.
     */
    suspend fun markMedicationSlotTaken(
        context: Context,
        slotId: Long,
        now: Long = System.currentTimeMillis()
    ) {
        if (slotId <= 0) return
        val db = getDb(context)
        val slot = db.medicationSlotDao().getByIdOnce(slotId) ?: return
        if (!slot.isActive) return
        val medIds = db.medicationSlotDao().getMedicationIdsForSlotOnce(slotId)
        if (medIds.isEmpty()) return
        val dayStartHour = context.readDayStartHour()
        val dayStartMinute = context.readDayStartMinute()
        val startOfToday = DayBoundary.startOfCurrentDay(
            dayStartHour = dayStartHour,
            now = now,
            dayStartMinute = dayStartMinute
        )
        val todayLocal = epochToLocalDateString(startOfToday)
        val doseDao = db.medicationDoseDao()
        val medDao = db.medicationDao()
        val existing = doseDao.getForDateOnce(todayLocal)
            .filter { !it.isSyntheticSkip && it.slotKey.equals(slot.name, ignoreCase = true) }
            .mapNotNull { it.medicationId }
            .toSet()
        for (medId in medIds) {
            if (medId in existing) continue
            // Only log for medications that are still active. Archived meds
            // can keep historical cross-ref rows; we should not synthesize
            // new doses for them.
            val med = medDao.getByIdOnce(medId) ?: continue
            if (med.isArchived) continue
            val dose = com.averycorp.prismtask.data.local.entity.MedicationDoseEntity(
                medicationId = medId,
                slotKey = slot.name,
                takenAt = now,
                takenDateLocal = todayLocal,
                note = "",
                createdAt = now,
                updatedAt = now
            )
            try {
                doseDao.insert(dose)
            } catch (_: Exception) {
                // duplicate cloud_id or transient — skip this med, keep going
            }
        }
    }

    private fun minutesOfDay(hhmm: String): Int {
        val parts = hhmm.split(":")
        if (parts.size < 2) return 0
        val h = parts[0].toIntOrNull() ?: 0
        val m = parts[1].toIntOrNull() ?: 0
        return h * 60 + m
    }

    /** "12m" / "2h" / "Yday" / "3d" age labels for the inbox widget. */
    internal fun formatRelativeAge(deltaMillis: Long): String {
        val minutes = deltaMillis / 60_000L
        return when {
            minutes < 1 -> "now"
            minutes < 60 -> "${minutes}m"
            minutes < 24 * 60 -> "${minutes / 60}h"
            minutes < 2 * 24 * 60 -> "Yday"
            else -> "${minutes / (24 * 60)}d"
        }
    }

    suspend fun getTopTemplates(context: Context, limit: Int = 3): List<TemplateShortcut> {
        val db = getDb(context)
        return db.taskTemplateDao().getAllTemplatesOnce().take(limit).map {
            TemplateShortcut(it.id, it.name, it.icon ?: "\uD83D\uDCCB")
        }
    }

    suspend fun toggleTaskCompletion(
        context: Context,
        taskId: Long,
        now: Long = System.currentTimeMillis()
    ) {
        val db = getDb(context)
        val taskDao = db.taskDao()
        val task = taskDao.getTaskByIdOnce(taskId) ?: return
        if (task.isCompleted) taskDao.markIncomplete(taskId, now) else taskDao.markCompleted(taskId, now)
    }

    suspend fun toggleHabitCompletion(
        context: Context,
        habitId: Long,
        now: Long = System.currentTimeMillis()
    ) {
        val db = getDb(context)
        val dayStartHour = context.readDayStartHour()
        val dayStartMinute = context.readDayStartMinute()
        val startOfDay = DayBoundary.startOfCurrentDay(
            dayStartHour = dayStartHour,
            now = now,
            dayStartMinute = dayStartMinute
        )
        val completionDao = db.habitCompletionDao()
        val startOfDayLocal = epochToLocalDateString(startOfDay)
        if (completionDao.isCompletedOnDateLocalOnce(habitId, startOfDayLocal)) {
            completionDao.deleteByHabitAndDateLocal(habitId, startOfDayLocal)
        } else {
            completionDao.insert(
                com.averycorp.prismtask.data.local.entity.HabitCompletionEntity(
                    habitId = habitId,
                    completedDate = startOfDay,
                    completedAt = now,
                    completedDateLocal = startOfDayLocal
                )
            )
        }
    }

    private fun epochToLocalDate(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun epochToLocalDateString(epochMillis: Long): String =
        epochToLocalDate(epochMillis).toString()
}
