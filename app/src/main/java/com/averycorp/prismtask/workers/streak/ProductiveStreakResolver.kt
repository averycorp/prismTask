package com.averycorp.prismtask.workers.streak

import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.preferences.ProductiveStreakPreferences
import com.averycorp.prismtask.data.repository.HabitRepository
import com.averycorp.prismtask.domain.usecase.ProductivityScoreCalculator
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Outcome returned by [ProductiveStreakResolver.resolveYesterday]. The
 * [day] is the day whose score was just evaluated; [brokenStreakLength]
 * is non-zero only when the call demoted an active run to zero (so the
 * caller can fire the empathetic notification once and only once).
 */
data class ProductiveStreakResolution(val day: LocalDate, val score: Int, val brokenStreakLength: Int, val streakAdvanced: Boolean)

/**
 * Computes yesterday's productivity score and updates
 * [ProductiveStreakPreferences] accordingly. Designed to run once per
 * day from [com.averycorp.prismtask.workers.DailyResetWorker] right
 * after the SoD boundary crosses, so "yesterday" maps onto the day
 * that just finished from the user's perspective.
 */
@Singleton
class ProductiveStreakResolver
@Inject
constructor(
    private val taskDao: TaskDao,
    private val habitRepository: HabitRepository,
    private val habitCompletionDao: HabitCompletionDao,
    private val productivityScoreCalculator: ProductivityScoreCalculator,
    private val productiveStreakPreferences: ProductiveStreakPreferences,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    suspend fun resolveYesterday(): ProductiveStreakResolution {
        val zone: ZoneId = clock.zone
        val today = LocalDate.now(clock)
        val yesterday = today.minusDays(1)
        val startMillis = yesterday.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillisExclusive = today.atStartOfDay(zone).toInstant().toEpochMilli()

        val tasks = taskDao.getTasksForAnalyticsRange(startMillis, endMillisExclusive).first()
        val habits = habitRepository.getActiveHabits().first()
        val habitCompletions = habitCompletionDao
            .getAllCompletionsInRange(startMillis, endMillisExclusive - 1).first()

        val response = productivityScoreCalculator.compute(
            startDate = yesterday,
            endDate = yesterday,
            zone = zone,
            tasks = tasks,
            activeHabitsCount = habits.size,
            habitCompletions = habitCompletions
        )
        val rawScore = response.scores.firstOrNull()?.score ?: 0.0
        val score = rawScore.roundToInt()
        val productive = score >= ProductiveStreakPreferences.PRODUCTIVE_DAY_SCORE_THRESHOLD

        return if (productive) {
            productiveStreakPreferences.recordProductiveDay(yesterday)
            ProductiveStreakResolution(
                day = yesterday,
                score = score,
                brokenStreakLength = 0,
                streakAdvanced = true
            )
        } else {
            val broken = productiveStreakPreferences.resetCurrentStreakIfBroken(yesterday)
            ProductiveStreakResolution(
                day = yesterday,
                score = score,
                brokenStreakLength = broken,
                streakAdvanced = false
            )
        }
    }
}
