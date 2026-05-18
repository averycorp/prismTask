package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.HabitLastCompletion
import com.averycorp.prismtask.data.local.dao.HabitLogDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.database.DatabaseTransactionRunner
import com.averycorp.prismtask.data.local.entity.HabitCompletionEntity
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.HabitLogEntity
import com.averycorp.prismtask.data.preferences.HabitListPreferences
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.remote.SyncTracker
import com.averycorp.prismtask.domain.automation.AutomationEvent
import com.averycorp.prismtask.domain.automation.AutomationEventBus
import com.averycorp.prismtask.domain.usecase.ForgivenessConfig
import com.averycorp.prismtask.domain.usecase.HabitForgivenessResolver
import com.averycorp.prismtask.domain.usecase.StreakCalculator
import com.averycorp.prismtask.notifications.HabitReminderScheduler
import com.averycorp.prismtask.util.DayBoundary
import com.averycorp.prismtask.widget.WidgetUpdateManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

data class HabitWithStatus(
    val habit: HabitEntity,
    val isCompletedToday: Boolean,
    val currentStreak: Int,
    val completionsThisWeek: Int,
    val completionsToday: Int = if (isCompletedToday) 1 else 0,
    val dailyTarget: Int = 1,
    val isBookedThisPeriod: Boolean = false,
    val bookedTasksThisPeriod: Int = 0,
    val previousPeriodCompletions: Int = 0,
    val previousPeriodMet: Boolean = false,
    val lastLogDate: Long? = null,
    val logCount: Int = 0,
    /**
     * True when the user long-pressed the habit circle to skip today. Today
     * is treated as "kept" for the forgiveness streak (no grace burned) and
     * the circle renders in a distinct skipped style.
     */
    val isSkippedToday: Boolean = false
)

/** Outcome of a long-press "skip today" gesture. */
sealed class HabitSkipResult {
    object Skipped : HabitSkipResult()

    /**
     * Cap rejection: [usedSkips] skips already exist in the trailing 7-day
     * window vs. [cap] allowed. ViewModel surfaces a snackbar explaining why
     * the gesture didn't take.
     */
    data class CapReached(val cap: Int, val usedSkips: Int) : HabitSkipResult()
    object HabitMissing : HabitSkipResult()
}

@Singleton
class HabitRepository
@Inject
constructor(
    private val transactionRunner: DatabaseTransactionRunner,
    private val habitDao: HabitDao,
    private val completionDao: HabitCompletionDao,
    private val habitLogDao: HabitLogDao,
    private val taskDao: TaskDao,
    private val syncTracker: SyncTracker,
    private val medicationReminderScheduler: HabitReminderScheduler,
    private val taskBehaviorPreferences: TaskBehaviorPreferences,
    private val habitListPreferences: HabitListPreferences,
    private val widgetUpdateManager: WidgetUpdateManager,
    private val automationEventBus: AutomationEventBus,
    private val userPreferencesDataStore: UserPreferencesDataStore
) {
    private suspend fun currentDayStartHour(): Int = taskBehaviorPreferences.getDayStartHour().first()

    private suspend fun normalizeForToday(timestamp: Long): Long =
        DayBoundary.normalizeToDayStart(timestamp, currentDayStartHour())

    private fun epochToLocalDateString(timestamp: Long): String =
        java.time.Instant.ofEpochMilli(timestamp)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toString()

    fun getAllHabits(): Flow<List<HabitEntity>> = habitDao.getAllHabits()

    fun getActiveHabits(): Flow<List<HabitEntity>> = habitDao.getActiveHabits()

    fun getHabitById(id: Long): Flow<HabitEntity?> = habitDao.getHabitById(id)

    suspend fun getHabitByIdOnce(id: Long): HabitEntity? = habitDao.getHabitByIdOnce(id)

    suspend fun addHabit(habit: HabitEntity): Long {
        val now = System.currentTimeMillis()
        val id = habitDao.insert(habit.copy(createdAt = now, updatedAt = now))
        syncTracker.trackCreate(id, "habit")
        widgetUpdateManager.updateHabitWidgets()
        return id
    }

    suspend fun updateHabit(habit: HabitEntity) {
        // Mark built-in habits as user-modified the first time they're edited
        // through the user-facing path. The diff/approve UI uses this as a
        // heuristic to default field-level overwrites to unchecked. System
        // writes (sync, reconciler, BuiltInUpdateDetector.applyUpdate) go
        // through habitDao directly and intentionally bypass this flag.
        val now = System.currentTimeMillis()
        val markedHabit = if (habit.isBuiltIn && !habit.isUserModified) {
            habit.copy(isUserModified = true, updatedAt = now)
        } else {
            habit.copy(updatedAt = now)
        }
        habitDao.update(markedHabit)
        syncTracker.trackUpdate(habit.id, "habit")
        widgetUpdateManager.updateHabitWidgets()
    }

    suspend fun deleteHabit(id: Long) {
        medicationReminderScheduler.cancelAll(id)
        medicationReminderScheduler.cancelFollowUp(id)
        syncTracker.trackDelete(id, "habit")
        habitDao.deleteById(id)
        widgetUpdateManager.updateHabitWidgets()
    }

    /**
     * Forgiveness-aware streak result for a single habit (v1.4.0 V5).
     *
     * Returns [com.averycorp.prismtask.domain.usecase.StreakResult] so the
     * caller gets both the strict count and the resilient count with the
     * configured grace window. Daily habits use the forgiving walk;
     * other frequencies fall back to strict (see
     * [com.averycorp.prismtask.domain.usecase.StreakCalculator.calculateResilientStreak]).
     *
     * If [config] is left null the resolver reads the global forgiveness flow
     * and lets [HabitForgivenessResolver] apply any per-habit overrides. The
     * project-streak callers that need the global directly should pass an
     * explicit [ForgivenessConfig] (e.g. `ForgivenessConfig.DEFAULT`).
     */
    suspend fun getResilientStreak(
        habitId: Long,
        config: ForgivenessConfig? = null
    ): com.averycorp.prismtask.domain.usecase.StreakResult? {
        val habit = habitDao.getHabitByIdOnce(habitId) ?: return null
        val completions = completionDao.getCompletionsForHabitOnce(habitId)
        val skippedDates = completionDao.getSkippedLocalDatesForHabitOnce(habitId)
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            .toSet()
        val resolved = if (config != null) {
            config
        } else {
            val globalPrefs = userPreferencesDataStore.forgivenessFlow.first()
            HabitForgivenessResolver.resolveForgivenessConfig(
                habit,
                ForgivenessConfig(
                    enabled = globalPrefs.enabled,
                    gracePeriodDays = globalPrefs.gracePeriodDays,
                    allowedMisses = globalPrefs.allowedMisses
                )
            )
        }
        return StreakCalculator.calculateResilientStreak(
            completions,
            habit,
            config = resolved,
            restDays = skippedDates
        )
    }

    suspend fun archiveHabit(id: Long) {
        val habit = habitDao.getHabitByIdOnce(id) ?: return
        medicationReminderScheduler.cancelAll(id)
        medicationReminderScheduler.cancelFollowUp(id)
        habitDao.update(habit.copy(isArchived = true, updatedAt = System.currentTimeMillis()))
        syncTracker.trackUpdate(id, "habit")
        widgetUpdateManager.updateHabitWidgets()
    }

    suspend fun unarchiveHabit(id: Long) {
        val habit = habitDao.getHabitByIdOnce(id) ?: return
        habitDao.update(habit.copy(isArchived = false, updatedAt = System.currentTimeMillis()))
        syncTracker.trackUpdate(id, "habit")
        widgetUpdateManager.updateHabitWidgets()
    }

    suspend fun completeHabit(habitId: Long, date: Long, notes: String? = null) {
        val normalizedDate = normalizeForToday(date)
        val normalizedLocalDate = epochToLocalDateString(normalizedDate)
        val now = System.currentTimeMillis()

        // Atomically read the existing count and insert the new completion so two
        // near-simultaneous taps cannot both see `count == target - 1` and both insert.
        data class CompletionResult(val newId: Long?, val habit: HabitEntity?, val newCount: Int)

        val result = transactionRunner.withTransaction {
            val habit = habitDao.getHabitByIdOnce(habitId)
            val hasMedInterval = habit?.reminderIntervalMillis != null
            val timesPerDay = habit?.reminderTimesPerDay ?: 1
            val target = if (habit?.frequencyPeriod == "daily") habit.targetFrequency else 1
            val currentCount = completionDao.getCompletionCountForDateLocalOnce(habitId, normalizedLocalDate)

            val cap = if (hasMedInterval) timesPerDay else target
            if (currentCount >= cap) {
                CompletionResult(null, habit, currentCount)
            } else {
                val newId = completionDao.insert(
                    HabitCompletionEntity(
                        habitId = habitId,
                        completedDate = normalizedDate,
                        completedAt = now,
                        notes = notes?.trim()?.ifEmpty { null },
                        completedDateLocal = normalizedLocalDate
                    )
                )
                CompletionResult(newId, habit, currentCount + 1)
            }
        }

        if (result.newId == null) return

        // Cancel any pending follow-up now that the habit is completed
        medicationReminderScheduler.cancelFollowUp(habitId)
        syncTracker.trackCreate(result.newId, "habit_completion")
        automationEventBus.emit(
            AutomationEvent.HabitCompleted(habitId = habitId, date = normalizedLocalDate)
        )
        widgetUpdateManager.updateHabitWidgets()

        // Two-way sync: when a habit linked to a "Create daily to-do" task is
        // marked complete, mirror onto the auto-generated task for today.
        // Writes through TaskDao directly so TaskRepository.completeTask's
        // habit-side sync (which would re-enter this method) is skipped.
        result.habit?.takeIf { it.createDailyTask }?.let { h ->
            val dayWindowStart = DayBoundary.normalizeToDayStart(now, currentDayStartHour())
            val dayWindowEnd = dayWindowStart + DayBoundary.DAY_MILLIS
            val linkedTask = taskDao.getLatestHabitTaskForDayOnce(h.id, dayWindowStart, dayWindowEnd)
            if (linkedTask != null && !linkedTask.isCompleted) {
                taskDao.markCompleted(linkedTask.id, now)
                syncTracker.trackUpdate(linkedTask.id, "task")
                widgetUpdateManager.updateTaskWidgets()
            }
        }

        val habit = result.habit
        if (habit?.reminderIntervalMillis != null && result.newCount < habit.reminderTimesPerDay) {
            medicationReminderScheduler.scheduleNext(
                habitId,
                habit.name,
                habit.description,
                now,
                habit.reminderIntervalMillis,
                doseNumber = result.newCount + 1,
                totalDoses = habit.reminderTimesPerDay
            )
        }
    }

    /**
     * Long-press gesture target: declare today off for this habit.
     *
     * - Deletes any completion rows on today (partial multi-daily counts are
     *   discarded — the user is saying "I'm done with this for today").
     * - Inserts a skip marker (`is_skipped = 1`). The forgiveness streak
     *   folds the date into the kept-day bucket via [getSkippedLocalDatesForHabitOnce].
     * - Enforces the global per-habit cap from [HabitListPreferences.getSkipCapPerWeek];
     *   a cap of 0 disables enforcement.
     *
     * Returns [HabitSkipResult] describing what happened so the ViewModel can
     * snackbar a cap rejection.
     */
    suspend fun skipHabitForToday(habitId: Long): HabitSkipResult {
        val habit = habitDao.getHabitByIdOnce(habitId) ?: return HabitSkipResult.HabitMissing
        val now = System.currentTimeMillis()
        val normalizedDate = normalizeForToday(now)
        val normalizedLocalDate = epochToLocalDateString(normalizedDate)

        val cap = habitListPreferences.getSkipCapPerWeek().first()
        if (cap > 0) {
            val windowDays = com.averycorp.prismtask.data.preferences.HabitListPreferences
                .SKIP_CAP_WINDOW_DAYS
            val windowStart = LocalDate.parse(normalizedLocalDate)
                .minusDays((windowDays - 1).toLong())
                .toString()
            val alreadySkipped = completionDao.isSkippedOnDateLocalOnce(habitId, normalizedLocalDate)
            if (!alreadySkipped) {
                val priorSkips = completionDao.getSkipCountForHabitInLocalRangeOnce(
                    habitId,
                    windowStart,
                    normalizedLocalDate
                )
                if (priorSkips >= cap) {
                    return HabitSkipResult.CapReached(cap = cap, usedSkips = priorSkips)
                }
            }
        }

        val skipId = transactionRunner.withTransaction {
            completionDao.deleteByHabitAndDateLocal(habitId, normalizedLocalDate)
            val existing = completionDao.getSkipByHabitAndDateLocal(habitId, normalizedLocalDate)
            if (existing != null) {
                existing.id
            } else {
                completionDao.insert(
                    HabitCompletionEntity(
                        habitId = habitId,
                        completedDate = normalizedDate,
                        completedAt = now,
                        completedDateLocal = normalizedLocalDate,
                        isSkipped = true
                    )
                )
            }
        }

        medicationReminderScheduler.cancelAll(habit.id)
        medicationReminderScheduler.cancelFollowUp(habit.id)
        syncTracker.trackCreate(skipId, "habit_completion")
        widgetUpdateManager.updateHabitWidgets()
        return HabitSkipResult.Skipped
    }

    /** Undoes a prior [skipHabitForToday]; safe to call when no skip exists. */
    suspend fun unskipHabitForToday(habitId: Long) {
        val now = System.currentTimeMillis()
        val normalizedDate = normalizeForToday(now)
        val normalizedLocalDate = epochToLocalDateString(normalizedDate)
        val existing = completionDao.getSkipByHabitAndDateLocal(habitId, normalizedLocalDate)
            ?: return
        syncTracker.trackDelete(existing.id, "habit_completion")
        completionDao.deleteSkipByHabitAndDateLocal(habitId, normalizedLocalDate)
        widgetUpdateManager.updateHabitWidgets()
    }

    suspend fun uncompleteHabit(habitId: Long, date: Long) {
        val normalizedDate = normalizeForToday(date)
        val normalizedLocalDate = epochToLocalDateString(normalizedDate)
        val completion = completionDao.getLatestByHabitAndDateLocal(habitId, normalizedLocalDate)
        if (completion != null) {
            syncTracker.trackDelete(completion.id, "habit_completion")
        }
        completionDao.deleteLatestByHabitAndDateLocal(habitId, normalizedLocalDate)
        widgetUpdateManager.updateHabitWidgets()

        // Two-way sync: roll back the auto-generated daily task's completion.
        habitDao.getHabitByIdOnce(habitId)?.takeIf { it.createDailyTask }?.let { h ->
            val now = System.currentTimeMillis()
            val dayWindowStart = DayBoundary.normalizeToDayStart(now, currentDayStartHour())
            val dayWindowEnd = dayWindowStart + DayBoundary.DAY_MILLIS
            val linkedTask = taskDao.getLatestHabitTaskForDayOnce(h.id, dayWindowStart, dayWindowEnd)
            if (linkedTask != null && linkedTask.isCompleted) {
                taskDao.markIncomplete(linkedTask.id, now)
                syncTracker.trackUpdate(linkedTask.id, "task")
                widgetUpdateManager.updateTaskWidgets()
            }
        }

        // Reschedule from previous completion or cancel if none remain
        val habit = habitDao.getHabitByIdOnce(habitId)
        if (habit?.reminderIntervalMillis != null) {
            val previousCompletion = completionDao.getLastCompletionOnce(habitId)
            val timesPerDay = habit.reminderTimesPerDay
            val newCount = completionDao.getCompletionCountForDateLocalOnce(habitId, normalizedLocalDate)
            if (previousCompletion != null && newCount < timesPerDay) {
                medicationReminderScheduler.scheduleNext(
                    habitId,
                    habit.name,
                    habit.description,
                    previousCompletion.completedAt,
                    habit.reminderIntervalMillis,
                    doseNumber = newCount + 1,
                    totalDoses = timesPerDay
                )
            } else {
                medicationReminderScheduler.cancel(habitId)
            }
        }
    }

    fun getCompletionsForHabit(habitId: Long): Flow<List<HabitCompletionEntity>> =
        completionDao.getCompletionsForHabit(habitId)

    suspend fun getCompletionsForHabitOnce(habitId: Long): List<HabitCompletionEntity> =
        completionDao.getCompletionsForHabitOnce(habitId)

    fun getCompletionsInRange(habitId: Long, startDate: Long, endDate: Long): Flow<List<HabitCompletionEntity>> =
        completionDao.getCompletionsInRange(habitId, startDate, endDate)

    fun getAllCompletionsInRange(startDate: Long, endDate: Long): Flow<List<HabitCompletionEntity>> =
        completionDao.getAllCompletionsInRange(startDate, endDate)

    fun getLastCompletionDatesPerHabit(): Flow<List<HabitLastCompletion>> =
        completionDao.getLastCompletionDatesPerHabit()

    suspend fun getAllHabitsOnce(): List<HabitEntity> = habitDao.getAllHabitsOnce()

    suspend fun getByTemplateKeyOnce(key: String): HabitEntity? =
        habitDao.getByTemplateKeyOnce(key)

    suspend fun getAllCategories(): List<String> = habitDao.getAllCategories()

    suspend fun updateSortOrders(habits: List<HabitEntity>) {
        habitDao.updateAll(habits)
        habits.forEach { habit -> syncTracker.trackUpdate(habit.id, "habit") }
    }

    // --- Bookable habit log methods ---

    fun getLogsForHabit(habitId: Long): Flow<List<HabitLogEntity>> =
        habitLogDao.getLogsForHabit(habitId)

    suspend fun logActivity(habitId: Long, date: Long, notes: String?): Long {
        val log = HabitLogEntity(
            habitId = habitId,
            date = date,
            notes = notes?.trim()?.ifEmpty { null }
        )
        val (logId, habitTouched) = transactionRunner.withTransaction {
            val newLogId = habitLogDao.insertLog(log)
            val habit = habitDao.getHabitByIdOnce(habitId)
            if (habit != null) {
                val updated = if (habit.isBooked) {
                    habit.copy(
                        isBooked = false,
                        bookedDate = null,
                        bookedNote = null,
                        updatedAt = System.currentTimeMillis()
                    )
                } else {
                    habit.copy(updatedAt = System.currentTimeMillis())
                }
                habitDao.update(updated)
            }
            newLogId to (habit != null)
        }
        syncTracker.trackCreate(logId, "habit_log")
        if (habitTouched) syncTracker.trackUpdate(habitId, "habit")
        widgetUpdateManager.updateHabitWidgets()
        return logId
    }

    suspend fun setBooked(habitId: Long, isBooked: Boolean, bookedDate: Long?, bookedNote: String?) {
        val habit = habitDao.getHabitByIdOnce(habitId) ?: return
        habitDao.update(
            habit.copy(
                isBooked = isBooked,
                bookedDate = if (isBooked) bookedDate else null,
                bookedNote = if (isBooked) bookedNote?.trim()?.ifEmpty { null } else null,
                updatedAt = System.currentTimeMillis()
            )
        )
        syncTracker.trackUpdate(habitId, "habit")
    }

    suspend fun getLastLogDate(habitId: Long): Long? =
        habitLogDao.getLastLog(habitId)?.date

    suspend fun deleteLog(log: HabitLogEntity) {
        syncTracker.trackDelete(log.id, "habit_log")
        habitLogDao.deleteLog(log)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun getHabitsWithTodayStatus(): Flow<List<HabitWithStatus>> =
        taskBehaviorPreferences.getDayStartHour().flatMapLatest { dayStartHour ->
            val todayLocalString = DayBoundary.currentLocalDateString(dayStartHour)
            combine(
                habitDao.getActiveHabits(),
                completionDao.getCompletionsForDateLocal(todayLocalString),
                completionDao.getSkipsForDateLocal(todayLocalString)
            ) { habits, todayCompletions, todaySkips ->
                val countByHabit = todayCompletions.groupBy { it.habitId }.mapValues { it.value.size }
                val skippedHabitIds = todaySkips.map { it.habitId }.toSet()
                habits.map { habit ->
                    val target = if (habit.reminderIntervalMillis != null) {
                        habit.reminderTimesPerDay
                    } else if (habit.frequencyPeriod == "daily") {
                        habit.targetFrequency
                    } else {
                        1
                    }
                    val count = countByHabit[habit.id] ?: 0
                    val isSkippedToday = habit.id in skippedHabitIds
                    HabitWithStatus(
                        habit = habit,
                        isCompletedToday = count >= target || isSkippedToday,
                        currentStreak = 0,
                        completionsThisWeek = 0,
                        completionsToday = count,
                        dailyTarget = target,
                        isSkippedToday = isSkippedToday
                    )
                }
            }
        }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun getHabitsWithFullStatus(): Flow<List<HabitWithStatus>> =
        combine(
            taskBehaviorPreferences.getDayStartHour(),
            taskBehaviorPreferences.getFirstDayOfWeek()
        ) { dsh, fdow -> dsh to fdow }.flatMapLatest { (dayStartHour, firstDayOfWeek) ->
            val calendarDow = firstDayOfWeek.toCalendarDayOfWeek()
            val today = DayBoundary.startOfCurrentDay(dayStartHour)
            val todayLocalString = DayBoundary.currentLocalDateString(dayStartHour)
            val weekStart = getWeekStart(today, calendarDow)
            val weekEnd = getWeekEnd(today, calendarDow)
            val todayLocal = LocalDate.now()

            combine(
                habitDao.getActiveHabits(),
                completionDao.getCompletionsForDateLocal(todayLocalString),
                completionDao.getSkipsForDateLocal(todayLocalString),
                habitLogDao.getAllLogs(),
                habitListPreferences.getStreakMaxMissedDays()
            ) { habits, todayCompletions, todaySkips, allLogs, streakMaxMissedDays ->
                val countByHabit = todayCompletions.groupBy { it.habitId }.mapValues { it.value.size }
                val skippedHabitIds = todaySkips.map { it.habitId }.toSet()
                val logsByHabit = allLogs.groupBy { it.habitId }
                habits.map { habit ->
                    val completions = completionDao.getCompletionsForHabitOnce(habit.id)
                    val skippedDates = completionDao.getSkippedLocalDatesForHabitOnce(habit.id)
                        .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
                        .toSet()
                    val target = if (habit.reminderIntervalMillis != null) {
                        habit.reminderTimesPerDay
                    } else if (habit.frequencyPeriod == "daily") {
                        habit.targetFrequency
                    } else {
                        1
                    }
                    val count = countByHabit[habit.id] ?: 0
                    val isSkippedToday = habit.id in skippedHabitIds

                    val periodStart: Long
                    val periodEnd: Long
                    when (habit.frequencyPeriod) {
                        "fortnightly" -> {
                            periodStart = getFortnightStart(today, calendarDow)
                            periodEnd = getFortnightEnd(today, calendarDow)
                        }
                        "monthly" -> {
                            periodStart = getMonthStart(today)
                            periodEnd = getMonthEnd(today)
                        }
                        "bimonthly" -> {
                            periodStart = getBimonthStart(today)
                            periodEnd = getBimonthEnd(today)
                        }
                        "quarterly" -> {
                            periodStart = getQuarterStart(today)
                            periodEnd = getQuarterEnd(today)
                        }
                        else -> {
                            periodStart = weekStart
                            periodEnd = weekEnd
                        }
                    }

                    val periodCompletions = completions
                        .filter { it.completedDate in periodStart..periodEnd }
                        .groupBy { it.completedDate }
                        .count { (_, dayCompletions) -> dayCompletions.size >= target }

                    // For non-daily habits, "completed today" means the period target is met
                    val isCompleted = when (habit.frequencyPeriod) {
                        "daily" -> count >= target || isSkippedToday
                        else -> periodCompletions >= habit.targetFrequency
                    }

                    // Fold skipped dates into the completion list as synthetic "met" rows
                    // so the strict streak walk treats them as kept days. Each skip
                    // contributes exactly enough entries to clear the daily target.
                    val zoneId = java.time.ZoneId.systemDefault()
                    val completionsForStreak = if (skippedDates.isEmpty()) {
                        completions
                    } else {
                        val synthetic = skippedDates.flatMap { date ->
                            val epoch = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
                            List(target.coerceAtLeast(1)) {
                                com.averycorp.prismtask.data.local.entity.HabitCompletionEntity(
                                    habitId = habit.id,
                                    completedDate = epoch,
                                    completedAt = epoch,
                                    completedDateLocal = date.toString()
                                )
                            }
                        }
                        completions + synthetic
                    }

                    // Previous-period completion tracking
                    val (prevStart, prevEnd) = previousPeriodBounds(habit.frequencyPeriod, periodStart)
                    val previousCount = if (habit.trackPreviousPeriod && prevStart != null && prevEnd != null) {
                        completions
                            .filter { it.completedDate in prevStart..prevEnd }
                            .groupBy { it.completedDate }
                            .count { (_, dayCompletions) -> dayCompletions.size >= target }
                    } else {
                        0
                    }
                    val previousMet = habit.trackPreviousPeriod && previousCount >= habit.targetFrequency

                    // Booking tracking: tasks scoped to this habit scheduled within the current period
                    val bookedTasks = if (habit.trackBooking) {
                        taskDao.getTasksForHabitInRangeOnce(habit.id, periodStart, periodEnd).size
                    } else {
                        0
                    }
                    val isBooked = habit.trackBooking && bookedTasks > 0

                    // Bookable habit: fetch last log date and count from the observed logs flow
                    // so that adding/removing a log immediately refreshes the "Last done" display.
                    val habitLogs = if (habit.isBookable) logsByHabit[habit.id].orEmpty() else emptyList()
                    val lastLog = habitLogs.maxByOrNull { it.date }
                    val logTotal = habitLogs.size

                    HabitWithStatus(
                        habit = habit,
                        isCompletedToday = isCompleted,
                        currentStreak = StreakCalculator.calculateCurrentStreak(
                            completionsForStreak,
                            habit,
                            todayLocal,
                            HabitForgivenessResolver.resolveMaxMissedDays(
                                habit,
                                streakMaxMissedDays
                            ),
                            firstDayOfWeek
                        ),
                        completionsThisWeek = periodCompletions,
                        completionsToday = count,
                        dailyTarget = target,
                        isBookedThisPeriod = isBooked,
                        bookedTasksThisPeriod = bookedTasks,
                        previousPeriodCompletions = previousCount,
                        previousPeriodMet = previousMet,
                        lastLogDate = lastLog?.date,
                        logCount = logTotal,
                        isSkippedToday = isSkippedToday
                    )
                }
            }
        }

    /**
     * Returns the [start, end] inclusive bounds of the period immediately preceding
     * the one that contains [currentPeriodStart]. Returns (null, null) for "daily"
     * or unknown periods — previous-period tracking only applies to recurring habits.
     */
    private fun previousPeriodBounds(frequencyPeriod: String, currentPeriodStart: Long): Pair<Long?, Long?> {
        val cal = Calendar.getInstance()
        return when (frequencyPeriod) {
            "weekly" -> {
                cal.timeInMillis = currentPeriodStart
                cal.add(Calendar.WEEK_OF_YEAR, -1)
                val start = cal.timeInMillis
                cal.add(Calendar.WEEK_OF_YEAR, 1)
                cal.add(Calendar.MILLISECOND, -1)
                start to cal.timeInMillis
            }
            "fortnightly" -> {
                cal.timeInMillis = currentPeriodStart
                cal.add(Calendar.WEEK_OF_YEAR, -2)
                val start = cal.timeInMillis
                cal.add(Calendar.WEEK_OF_YEAR, 2)
                cal.add(Calendar.MILLISECOND, -1)
                start to cal.timeInMillis
            }
            "monthly" -> {
                cal.timeInMillis = currentPeriodStart
                cal.add(Calendar.MONTH, -1)
                val start = cal.timeInMillis
                cal.add(Calendar.MONTH, 1)
                cal.add(Calendar.MILLISECOND, -1)
                start to cal.timeInMillis
            }
            "bimonthly" -> {
                cal.timeInMillis = currentPeriodStart
                cal.add(Calendar.MONTH, -2)
                val start = cal.timeInMillis
                cal.add(Calendar.MONTH, 2)
                cal.add(Calendar.MILLISECOND, -1)
                start to cal.timeInMillis
            }
            "quarterly" -> {
                cal.timeInMillis = currentPeriodStart
                cal.add(Calendar.MONTH, -3)
                val start = cal.timeInMillis
                cal.add(Calendar.MONTH, 3)
                cal.add(Calendar.MILLISECOND, -1)
                start to cal.timeInMillis
            }
            else -> null to null
        }
    }

    companion object {
        /** Returns the last day of the week for a given first day. */
        private fun lastDayOfWeek(firstDay: Int): Int = ((firstDay + 5) % 7) + 1

        fun normalizeToMidnight(timestamp: Long): Long {
            val cal = Calendar.getInstance()
            cal.timeInMillis = timestamp
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        fun getWeekStart(today: Long, firstDayOfWeek: Int = Calendar.MONDAY): Long {
            val cal = Calendar.getInstance()
            cal.firstDayOfWeek = firstDayOfWeek
            cal.timeInMillis = today
            cal.set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (cal.timeInMillis > today) cal.add(Calendar.WEEK_OF_YEAR, -1)
            return cal.timeInMillis
        }

        fun getWeekEnd(today: Long, firstDayOfWeek: Int = Calendar.MONDAY): Long {
            val cal = Calendar.getInstance()
            cal.firstDayOfWeek = firstDayOfWeek
            cal.timeInMillis = today
            val lastDay = lastDayOfWeek(firstDayOfWeek)
            cal.set(Calendar.DAY_OF_WEEK, lastDay)
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            if (cal.timeInMillis < today) cal.add(Calendar.WEEK_OF_YEAR, 1)
            return cal.timeInMillis
        }

        fun getFortnightStart(today: Long, firstDayOfWeek: Int = Calendar.MONDAY): Long {
            val cal = Calendar.getInstance()
            cal.firstDayOfWeek = firstDayOfWeek
            cal.timeInMillis = today
            cal.set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (cal.timeInMillis > today) cal.add(Calendar.WEEK_OF_YEAR, -1)
            // Use ISO week number to determine fortnight boundary (odd weeks start a fortnight)
            val weekNum = cal.get(Calendar.WEEK_OF_YEAR)
            if (weekNum % 2 == 0) cal.add(Calendar.WEEK_OF_YEAR, -1)
            return cal.timeInMillis
        }

        fun getFortnightEnd(today: Long, firstDayOfWeek: Int = Calendar.MONDAY): Long {
            val start = getFortnightStart(today, firstDayOfWeek)
            val cal = Calendar.getInstance()
            cal.timeInMillis = start
            cal.add(Calendar.DAY_OF_YEAR, 13)
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            return cal.timeInMillis
        }

        fun getMonthStart(today: Long): Long {
            val cal = Calendar.getInstance()
            cal.timeInMillis = today
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        fun getBimonthStart(today: Long): Long {
            val cal = Calendar.getInstance()
            cal.timeInMillis = today
            // Align to 2-month periods starting from January (Jan-Feb, Mar-Apr, etc.)
            val month = cal.get(Calendar.MONTH) // 0-based
            val startMonth = if (month % 2 == 0) month else month - 1
            cal.set(Calendar.MONTH, startMonth)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        fun getBimonthEnd(today: Long): Long {
            val cal = Calendar.getInstance()
            cal.timeInMillis = getBimonthStart(today)
            cal.add(Calendar.MONTH, 2)
            cal.add(Calendar.DAY_OF_MONTH, -1)
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            return cal.timeInMillis
        }

        fun getQuarterStart(today: Long): Long {
            val cal = Calendar.getInstance()
            cal.timeInMillis = today
            val month = cal.get(Calendar.MONTH) // 0-based
            val startMonth = (month / 3) * 3
            cal.set(Calendar.MONTH, startMonth)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        fun getQuarterEnd(today: Long): Long {
            val cal = Calendar.getInstance()
            cal.timeInMillis = getQuarterStart(today)
            cal.add(Calendar.MONTH, 3)
            cal.add(Calendar.DAY_OF_MONTH, -1)
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            return cal.timeInMillis
        }

        fun getMonthEnd(today: Long): Long {
            val cal = Calendar.getInstance()
            cal.timeInMillis = today
            cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            return cal.timeInMillis
        }
    }
}

/** Converts a [java.time.DayOfWeek] to the [Calendar] DAY_OF_WEEK int. */
fun DayOfWeek.toCalendarDayOfWeek(): Int = when (this) {
    DayOfWeek.MONDAY -> Calendar.MONDAY
    DayOfWeek.TUESDAY -> Calendar.TUESDAY
    DayOfWeek.WEDNESDAY -> Calendar.WEDNESDAY
    DayOfWeek.THURSDAY -> Calendar.THURSDAY
    DayOfWeek.FRIDAY -> Calendar.FRIDAY
    DayOfWeek.SATURDAY -> Calendar.SATURDAY
    DayOfWeek.SUNDAY -> Calendar.SUNDAY
}
