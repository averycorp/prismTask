package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.dao.LeisureSessionDao
import com.averycorp.prismtask.data.local.entity.HabitCompletionEntity
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.LeisureSessionEntity
import com.averycorp.prismtask.data.repository.HabitRepository
import com.averycorp.prismtask.domain.model.LifeCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Window-resolved habit + leisure inputs for the Work-Life Balance Engine.
 *
 * `habits` and `leisure` are the per-row contributions the balance bar
 * counts (a SELF_CARE habit completion is one SELF_CARE unit; a leisure
 * session is one SELF_CARE unit). The flat `habitTimestamps` and
 * `leisureTimestamps` lists are the EASY-load contributions the cognitive
 * load bar counts. The trailing three fields are burnout-scorer secondary
 * signals (streak breaks, recent self-care habit completions, leisure
 * minutes in the rest-deficit window).
 */
data class BalanceContributions(
    val habits: List<HabitContribution>,
    val leisure: List<LeisureContribution>,
    val habitTimestamps: List<Long>,
    val leisureTimestamps: List<Long>,
    val habitStreakBreaks: Int,
    val selfCareHabitCompletionsRecent: Int,
    val leisureMinutesRecent: Int
) {
    companion object {
        val EMPTY = BalanceContributions(
            habits = emptyList(),
            leisure = emptyList(),
            habitTimestamps = emptyList(),
            leisureTimestamps = emptyList(),
            habitStreakBreaks = 0,
            selfCareHabitCompletionsRecent = 0,
            leisureMinutesRecent = 0
        )
    }
}

/**
 * Pulls habit completions and leisure sessions from the database, classifies
 * each habit into a [LifeCategory] via [LifeCategoryClassifier], and produces
 * the inputs [BalanceTracker], [CognitiveLoadBalanceTracker], and
 * [BurnoutScorer] need to factor habits and leisure into their outputs.
 *
 * Centralised here so the four call sites (Today screen, weekly report,
 * morning check-in, overload workers) stay thin and share one resolution
 * algorithm — in particular the habit-streak-break heuristic, which is the
 * one piece of logic that wasn't trivially derivable from a single query.
 */
@Singleton
class BalanceContributionsProvider @Inject constructor(
    private val habitRepository: HabitRepository,
    private val leisureSessionDao: LeisureSessionDao
) {
    /**
     * Reactive contributions stream. Emits whenever any underlying source
     * (habits, completions, leisure sessions) changes. [windowDays] caps how
     * far back to load contributions — 28 days matches the rolling window
     * used by both balance trackers. [restDeficitDays] is the lookback the
     * burnout scorer treats as "recently rested" (defaults to the same
     * 2-day window [BurnoutScorer] uses).
     */
    fun observe(
        windowDays: Int = 28,
        restDeficitDays: Int = 2,
        now: () -> Long = System::currentTimeMillis,
        classifier: LifeCategoryClassifier = LifeCategoryClassifier()
    ): Flow<BalanceContributions> {
        val nowMillis = now()
        val windowStart = nowMillis - windowDays.toLong() * DAY_MILLIS
        val windowEnd = nowMillis + DAY_MILLIS
        return combine(
            habitRepository.getAllHabits(),
            habitRepository.getAllCompletionsInRange(windowStart, windowEnd),
            leisureSessionDao.getInRange(windowStart, windowEnd)
        ) { habits, completions, sessions ->
            resolve(
                habits = habits,
                completions = completions,
                sessions = sessions,
                now = nowMillis,
                restDeficitDays = restDeficitDays,
                classifier = classifier
            )
        }
    }

    /**
     * One-shot snapshot for callers (workers, suspend-only paths) that don't
     * need reactive updates.
     */
    suspend fun snapshot(
        now: Long = System.currentTimeMillis(),
        windowDays: Int = 28,
        restDeficitDays: Int = 2,
        classifier: LifeCategoryClassifier = LifeCategoryClassifier()
    ): BalanceContributions = observe(windowDays, restDeficitDays, { now }, classifier).first()

    /**
     * Pure resolver — exposed so unit tests can drive it directly with
     * synthetic data and so workers that already have entities in hand can
     * skip the DAO round-trip.
     */
    fun resolve(
        habits: List<HabitEntity>,
        completions: List<HabitCompletionEntity>,
        sessions: List<LeisureSessionEntity>,
        now: Long,
        restDeficitDays: Int = 2,
        classifier: LifeCategoryClassifier = LifeCategoryClassifier()
    ): BalanceContributions {
        if (habits.isEmpty() && completions.isEmpty() && sessions.isEmpty()) {
            return BalanceContributions.EMPTY
        }
        val categoryByHabitId: Map<Long, LifeCategory> = habits.associate { habit ->
            habit.id to classifier.classify(habit.name, habit.description)
        }
        val habitContributions = completions.map { completion ->
            HabitContribution(
                completedAt = completion.completedAt,
                lifeCategory = categoryByHabitId[completion.habitId] ?: LifeCategory.UNCATEGORIZED
            )
        }
        val habitTimestamps = completions.map { it.completedAt }
        val leisureContributions = sessions.map { LeisureContribution(loggedAt = it.loggedAt) }
        val leisureTimestamps = sessions.map { it.loggedAt }

        val restCutoff = now - restDeficitDays.toLong() * DAY_MILLIS
        val selfCareHabitCompletionsRecent = completions.count { c ->
            c.completedAt >= restCutoff &&
                categoryByHabitId[c.habitId] == LifeCategory.SELF_CARE
        }
        val leisureMinutesRecent = sessions
            .filter { it.loggedAt >= restCutoff }
            .sumOf { it.durationMinutes }

        // Streak-break heuristic: a habit broke its streak when it had at
        // least one completion in the prior 7-14 day window but none in the
        // last 7 days. Cheap proxy that avoids re-running the full
        // forgiveness-aware streak calculator for every habit on every
        // balance recomputation; the scorer caps streakBreaks at 5 so a
        // little imprecision doesn't move the gauge.
        val lastWeekCutoff = now - 7L * DAY_MILLIS
        val twoWeeksCutoff = now - 14L * DAY_MILLIS
        val completionsByHabitId = completions.groupBy { it.habitId }
        val habitStreakBreaks = habits.count { habit ->
            val times = completionsByHabitId[habit.id].orEmpty()
            val hadPriorWeek = times.any { it.completedAt in twoWeeksCutoff until lastWeekCutoff }
            val hasLastWeek = times.any { it.completedAt >= lastWeekCutoff }
            hadPriorWeek && !hasLastWeek
        }

        return BalanceContributions(
            habits = habitContributions,
            leisure = leisureContributions,
            habitTimestamps = habitTimestamps,
            leisureTimestamps = leisureTimestamps,
            habitStreakBreaks = habitStreakBreaks,
            selfCareHabitCompletionsRecent = selfCareHabitCompletionsRecent,
            leisureMinutesRecent = leisureMinutesRecent
        )
    }

    companion object {
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
