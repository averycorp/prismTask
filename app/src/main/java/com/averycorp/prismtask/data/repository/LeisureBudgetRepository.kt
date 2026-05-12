package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.LeisureActivityDao
import com.averycorp.prismtask.data.local.dao.LeisureSessionDao
import com.averycorp.prismtask.data.local.entity.HabitCompletionEntity
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.LeisureActivityEntity
import com.averycorp.prismtask.data.local.entity.LeisureSessionEntity
import com.averycorp.prismtask.data.preferences.LeisureBudgetPreferences
import com.averycorp.prismtask.data.preferences.LeisureBudgetSnapshot
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.remote.SyncTracker
import com.averycorp.prismtask.domain.model.LeisureCategory
import com.averycorp.prismtask.domain.model.LeisureSessionSource
import com.averycorp.prismtask.domain.usecase.LeisureSampler
import com.averycorp.prismtask.domain.usecase.LeisureScorer
import com.averycorp.prismtask.util.DayBoundary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Leisure Budget v2.0 repository.
 *
 * Replaces the v1.x [com.averycorp.prismtask.data.repository.LeisureRepository]
 * slot-pick model with the session-log + minute-budget model. Owns the
 * three tables ([LeisureActivityDao], [LeisureSessionDao]) + the
 * settings DataStore ([LeisureBudgetPreferences]).
 *
 * The repo keeps the "Leisure" meta-habit auto-completion behaviour
 * the v1.x repo had — when daily-target minutes are reached, the
 * meta-habit fires for the day. Subtle change: instead of "all enabled
 * slots done", the trigger is now "minutes-logged ≥ target". The
 * meta-habit row stays valid for downstream consumers
 * (habit-correlation analytics, streaks UI).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LeisureBudgetRepository
@Inject
constructor(
    private val activityDao: LeisureActivityDao,
    private val sessionDao: LeisureSessionDao,
    private val habitDao: HabitDao,
    private val habitCompletionDao: HabitCompletionDao,
    private val preferences: LeisureBudgetPreferences,
    private val taskBehaviorPreferences: TaskBehaviorPreferences,
    private val sampler: LeisureSampler,
    private val scorer: LeisureScorer,
    private val syncTracker: SyncTracker
) {
    companion object {
        const val LEISURE_META_HABIT_NAME = "Leisure"
    }

    data class TodayProgress(
        val minutesLogged: Int,
        val targetMinutes: Int,
        val score: LeisureScorer.LeisureScore,
        val suggestion: LeisureActivityEntity?,
        val refreshesConsumed: Int,
        val refreshLimit: Int
    ) {
        val canRefresh: Boolean get() = refreshesConsumed < refreshLimit
    }

    fun getActivities(): Flow<List<LeisureActivityEntity>> = activityDao.getAll()

    suspend fun getActivitiesOnce(): List<LeisureActivityEntity> =
        activityDao.getAllOnce()

    suspend fun upsertActivity(activity: LeisureActivityEntity): Long {
        val id = activityDao.insert(activity.copy(updatedAt = System.currentTimeMillis()))
        if (activity.id == 0L) {
            syncTracker.trackCreate(id, "leisure_activity")
        } else {
            syncTracker.trackUpdate(activity.id, "leisure_activity")
        }
        return id
    }

    suspend fun deleteActivity(id: Long) {
        activityDao.deleteById(id)
        syncTracker.trackDelete(id, "leisure_activity")
    }

    suspend fun setActivityEnabled(id: Long, enabled: Boolean) {
        val current = activityDao.getById(id) ?: return
        activityDao.update(current.copy(enabled = enabled, updatedAt = System.currentTimeMillis()))
        syncTracker.trackUpdate(id, "leisure_activity")
    }

    /**
     * Insert a session. Updates the activity's `last_completed_at` (for
     * recency-weighted sampling) and the "Leisure" meta-habit
     * completion when the daily target is hit.
     */
    suspend fun logSession(
        activityId: Long?,
        category: LeisureCategory,
        durationMinutes: Int,
        loggedAt: Long = System.currentTimeMillis(),
        source: LeisureSessionSource
    ): Long {
        val id = sessionDao.insert(
            LeisureSessionEntity(
                activityId = activityId,
                category = category.name,
                durationMinutes = durationMinutes.coerceAtLeast(1),
                loggedAt = loggedAt,
                source = source.name
            )
        )
        syncTracker.trackCreate(id, "leisure_session")
        if (activityId != null) {
            activityDao.touchLastCompletedAt(activityId, loggedAt)
            syncTracker.trackUpdate(activityId, "leisure_activity")
        }
        syncMetaHabitForDate(loggedAt)
        return id
    }

    /** Reactive feed of recent sessions, newest first. */
    fun observeRecentSessions(limit: Int = 100): Flow<List<LeisureSessionEntity>> =
        sessionDao.getRecent(limit)

    /**
     * Delete a previously logged session. Re-evaluates the leisure
     * meta-habit completion for the day the session belonged to so the
     * streak/analytics stay consistent with the new minutes total.
     */
    suspend fun deleteSession(sessionId: Long) {
        val existing = sessionDao.getById(sessionId) ?: return
        sessionDao.deleteById(sessionId)
        syncTracker.trackDelete(sessionId, "leisure_session")
        syncMetaHabitForDate(existing.loggedAt)
    }

    /**
     * Re-stamp a logged session's [loggedAt] timestamp. Re-evaluates the
     * meta-habit for both the old and new dates so the streak responds
     * when an entry crosses a day boundary. Calling twice when the move
     * stays within the same SoD day is a no-op on the second pass since
     * the worker is idempotent.
     */
    suspend fun updateSessionTime(sessionId: Long, newLoggedAt: Long) {
        val existing = sessionDao.getById(sessionId) ?: return
        if (existing.loggedAt == newLoggedAt) return
        sessionDao.update(existing.copy(loggedAt = newLoggedAt))
        syncTracker.trackUpdate(sessionId, "leisure_session")
        syncMetaHabitForDate(existing.loggedAt)
        syncMetaHabitForDate(newLoggedAt)
    }

    private suspend fun syncMetaHabitForDate(loggedAt: Long) {
        val hour = taskBehaviorPreferences.getDayStartHour().first()
        val startOfDay = DayBoundary.startOfCurrentDay(hour, loggedAt)
        val endOfDay = startOfDay + DayBoundary.DAY_MILLIS
        val localDate = DayBoundary.currentLocalDateString(hour, loggedAt)
        val minutes = sessionDao.getMinutesInRangeOnce(startOfDay, endOfDay)
        val snapshot = preferences.observeSnapshot().first()
        val target = snapshot.targetForDate(DayBoundary.currentLocalDate(hour, loggedAt))
        val habit = getOrCreateLeisureHabit()
        val alreadyCompleted = habitCompletionDao.isCompletedOnDateLocalOnce(habit.id, localDate)
        val targetHit = target > 0 && minutes >= target
        if (targetHit && !alreadyCompleted) {
            habitCompletionDao.insert(
                HabitCompletionEntity(
                    habitId = habit.id,
                    completedDate = startOfDay,
                    completedAt = System.currentTimeMillis(),
                    completedDateLocal = localDate
                )
            )
        } else if (!targetHit && alreadyCompleted) {
            habitCompletionDao.deleteByHabitAndDateLocal(habit.id, localDate)
        }
    }

    private suspend fun getOrCreateLeisureHabit(): HabitEntity {
        val existing = habitDao.getHabitByName(LEISURE_META_HABIT_NAME)
        if (existing != null) return existing
        val id = habitDao.insert(
            HabitEntity(
                name = LEISURE_META_HABIT_NAME,
                description = "Hit your daily leisure budget",
                icon = "🎉",
                color = "#8B5CF6",
                category = "Leisure",
                targetFrequency = 1,
                frequencyPeriod = "daily",
                isBuiltIn = true,
                templateKey = "builtin_leisure",
                showStreak = true
            )
        )
        return habitDao.getHabitByIdOnce(id) ?: error("Habit not found after insert")
    }

    suspend fun ensureHabitExists() {
        getOrCreateLeisureHabit()
    }

    /** Observe today's minutes-logged via DayBoundary-respecting hour. */
    fun observeMinutesLoggedToday(): Flow<Int> =
        taskBehaviorPreferences.getDayStartHour().flatMapLatest { hour ->
            val start = DayBoundary.startOfCurrentDay(hour)
            sessionDao.getMinutesInRange(start, start + DayBoundary.DAY_MILLIS)
        }

    /**
     * Today progress with current suggestion. Calling this re-rolls the
     * suggestion only when [forceResample] is true — UI consumers should
     * keep a stable suggestion until the user taps refresh.
     */
    suspend fun computeTodayProgress(forceResample: Boolean = false): TodayProgress {
        val hour = taskBehaviorPreferences.getDayStartHour().first()
        val startOfDay = DayBoundary.startOfCurrentDay(hour)
        val endOfDay = startOfDay + DayBoundary.DAY_MILLIS
        val localDate = DayBoundary.currentLocalDate(hour)
        val snapshot = preferences.observeSnapshot().first()
        val sessions = sessionDao.getInRangeOnce(startOfDay, endOfDay)
        val minutesLogged = sessions.sumOf { it.durationMinutes }
        val target = snapshot.targetForDate(localDate)
        // Streak is computed elsewhere (LeisureScoreSection ViewModel)
        // via DailyForgivenessStreakCore. Pass 0 here when the UI hasn't
        // wired the streak yet; the Today card surfaces score breakdown
        // separately from the streak badge.
        val score = scorer.scoreDay(sessions, target, currentStreakDays = 0)
        val candidates = activityDao.getEnabledInCategoriesOnce(
            snapshot.enabledCategories.map { it.name }
        )
        val suggestion = if (candidates.isEmpty()) null else sampler.pick(candidates, snapshot.enabledCategories)
        val refreshesConsumed = preferences.readRefreshesConsumed(localDate.toString())
        return TodayProgress(
            minutesLogged = minutesLogged,
            targetMinutes = target,
            score = score,
            suggestion = if (forceResample) suggestion else suggestion,
            refreshesConsumed = refreshesConsumed,
            refreshLimit = snapshot.refreshLimit
        )
    }

    /**
     * Consume one refresh and return the next suggestion. Returns null when
     * the daily cap is hit or the pool is empty.
     */
    suspend fun refreshSuggestion(): LeisureActivityEntity? {
        val hour = taskBehaviorPreferences.getDayStartHour().first()
        val localDate = DayBoundary.currentLocalDate(hour).toString()
        val snapshot = preferences.observeSnapshot().first()
        val consumed = preferences.readRefreshesConsumed(localDate)
        if (consumed >= snapshot.refreshLimit) return null
        preferences.incrementRefreshesConsumed(localDate)
        val candidates = activityDao.getEnabledInCategoriesOnce(
            snapshot.enabledCategories.map { it.name }
        )
        return if (candidates.isEmpty()) null else sampler.pick(candidates, snapshot.enabledCategories)
    }

    /**
     * Reactive sparkline data — last 7 days of minutes-logged-per-day
     * for the dashboard section.
     */
    fun observeSparklineLast7Days(): Flow<List<Int>> =
        taskBehaviorPreferences.getDayStartHour().flatMapLatest { hour ->
            val startOfToday = DayBoundary.startOfCurrentDay(hour)
            val startOfWindow = startOfToday - 6L * DayBoundary.DAY_MILLIS
            val endOfWindow = startOfToday + DayBoundary.DAY_MILLIS
            combine(
                preferences.observeSnapshot(),
                sessionDao.getInRange(startOfWindow, endOfWindow)
            ) { _, sessions ->
                (0..6).map { offset ->
                    val dayStart = startOfWindow + offset * DayBoundary.DAY_MILLIS
                    val dayEnd = dayStart + DayBoundary.DAY_MILLIS
                    sessions
                        .filter { it.loggedAt in dayStart until dayEnd }
                        .sumOf { it.durationMinutes }
                }
            }
        }

    /**
     * Reactive activity-dates for streak calculation — days in [windowDays]
     * where minutes-logged ≥ effectiveTarget.
     */
    suspend fun targetHitDatesInWindow(windowDays: Int): Set<java.time.LocalDate> {
        val hour = taskBehaviorPreferences.getDayStartHour().first()
        val snapshot = preferences.observeSnapshot().first()
        val startOfToday = DayBoundary.startOfCurrentDay(hour)
        val startOfWindow = startOfToday - (windowDays - 1).toLong() * DayBoundary.DAY_MILLIS
        val endOfWindow = startOfToday + DayBoundary.DAY_MILLIS
        val sessions = sessionDao.getInRangeOnce(startOfWindow, endOfWindow)
        val hits = mutableSetOf<java.time.LocalDate>()
        for (offset in 0 until windowDays) {
            val dayStart = startOfWindow + offset * DayBoundary.DAY_MILLIS
            val dayEnd = dayStart + DayBoundary.DAY_MILLIS
            val date = DayBoundary.currentLocalDate(hour, dayStart)
            val target = snapshot.targetForDate(date)
            if (target <= 0) continue
            val minutes = sessions.filter { it.loggedAt in dayStart until dayEnd }.sumOf { it.durationMinutes }
            if (minutes >= target) hits.add(date)
        }
        return hits
    }

    fun observeSettings(): Flow<LeisureBudgetSnapshot> = preferences.observeSnapshot()

    fun observeSparklineWithSettings(): Flow<Pair<List<Int>, LeisureBudgetSnapshot>> =
        observeSparklineLast7Days().map { sparkline ->
            sparkline to preferences.observeSnapshot().first()
        }
}
