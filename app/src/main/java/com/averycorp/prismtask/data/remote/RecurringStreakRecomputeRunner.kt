package com.averycorp.prismtask.data.remote

import android.util.Log
import com.averycorp.prismtask.data.local.dao.TaskCompletionDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.preferences.BuiltInSyncPreferences
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.domain.usecase.DormancyCalculator
import com.averycorp.prismtask.domain.usecase.RecurringStreakRecomputer
import com.averycorp.prismtask.domain.usecase.RecurringTaskHistory
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dormancy Re-Entry: one-shot `STREAK_RECOMPUTE_V2` migration.
 *
 * Recurring-task streaks are computed-on-read (no materialized column — see the
 * audit), so this "migration" re-derives every recurring task's streak under
 * the new dormancy rule ([RecurringStreakRecomputer]) from completion history +
 * `last_engagement_at`. It runs once per install, guarded by
 * [BuiltInSyncPreferences.isStreakRecomputeV2Done], then sets the flag.
 *
 * Because nothing is materialized, the recompute is non-destructive: it warms
 * the read-time calculator over the existing history and records that the
 * upgrade pass ran. Kept best-effort — a failure leaves the flag unset so the
 * next launch retries.
 */
@Singleton
class RecurringStreakRecomputeRunner
@Inject
constructor(
    private val taskDao: TaskDao,
    private val taskCompletionDao: TaskCompletionDao,
    private val userPreferencesDataStore: UserPreferencesDataStore,
    private val builtInSyncPreferences: BuiltInSyncPreferences
) {
    suspend fun runIfNeeded() {
        if (builtInSyncPreferences.isStreakRecomputeV2Done()) return
        try {
            val global = userPreferencesDataStore.dormancyThresholdDaysFlow.first()
            val recurringTasks = taskDao.getAllTasksOnce().filter { it.recurrenceRule != null }
            if (recurringTasks.isNotEmpty()) {
                val completionsByTask = taskCompletionDao.getAllCompletionsOnce()
                    .groupBy { it.taskId }
                val histories = recurringTasks.map { task ->
                    val completionDates = completionsByTask[task.id]
                        ?.map { it.completedDate.toLocalDate() }
                        ?.toMutableSet()
                        ?: mutableSetOf()
                    task.lastEngagementAt?.let { completionDates.add(it.toLocalDate()) }
                    RecurringTaskHistory(
                        taskId = task.id,
                        engagementDates = completionDates,
                        skipDates = emptySet(),
                        effectiveThresholdDays = DormancyCalculator.effectiveThresholdDays(
                            task.dormancyThresholdDaysOverride,
                            global
                        )
                    )
                }
                val results = RecurringStreakRecomputer.recompute(histories)
                Log.i(
                    "StreakRecomputeV2",
                    "Recomputed ${results.size} recurring-task streaks under dormancy rule"
                )
            }
            builtInSyncPreferences.setStreakRecomputeV2Done(true)
        } catch (e: Exception) {
            // Best-effort: leave the flag unset so the next launch retries.
            Log.w("StreakRecomputeV2", "Streak recompute failed; will retry next launch", e)
        }
    }

    private fun Long.toLocalDate(): LocalDate =
        Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()
}
