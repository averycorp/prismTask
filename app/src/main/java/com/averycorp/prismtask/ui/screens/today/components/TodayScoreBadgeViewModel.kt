package com.averycorp.prismtask.ui.screens.today.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.repository.HabitRepository
import com.averycorp.prismtask.domain.usecase.ProductivityScoreCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Visible-state for the [ProductivityScoreBadge]. Computed locally — no
 * backend call. The "≥3 days history" gate hides the badge for brand-new
 * users where the score would trivially max at 100 (no tasks due → 100%
 * task-completion default).
 */
data class TodayScoreBadgeState(val todayScore: Int? = null, val hasEnoughHistory: Boolean = false)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayScoreBadgeViewModel
@Inject
constructor(
    private val taskDao: TaskDao,
    private val habitRepository: HabitRepository,
    private val habitCompletionDao: HabitCompletionDao,
    private val productivityScoreCalculator: ProductivityScoreCalculator
) : ViewModel() {

    val state: StateFlow<TodayScoreBadgeState> = run {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        // Span seven days so the calculator has enough context for a
        // representative trend, even though only today's score is consumed.
        val windowStart = today.minusDays(WINDOW_DAYS - 1L)
        val startMillis = windowStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillisExclusive = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        combine(
            taskDao.getTasksForAnalyticsRange(startMillis, endMillisExclusive),
            habitRepository.getActiveHabits(),
            habitCompletionDao.getAllCompletionsInRange(startMillis, endMillisExclusive - 1)
        ) { tasks, habits, habitCompletions ->
            val response = productivityScoreCalculator.compute(
                startDate = windowStart,
                endDate = today,
                zone = zone,
                tasks = tasks,
                activeHabitsCount = habits.size,
                habitCompletions = habitCompletions
            )
            val todayScoreEntry = response.scores.lastOrNull { it.date == today }
            val activeDays = activeDayCount(tasks = tasks, habitCompletions = habitCompletions, zone = zone)

            TodayScoreBadgeState(
                todayScore = todayScoreEntry?.score?.roundToInt(),
                hasEnoughHistory = activeDays >= MIN_ACTIVE_DAYS
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STATE_SUBSCRIPTION_TIMEOUT_MS),
            TodayScoreBadgeState()
        )
    }

    companion object {
        const val WINDOW_DAYS = 7
        const val MIN_ACTIVE_DAYS = 3
        const val STATE_SUBSCRIPTION_TIMEOUT_MS = 5_000L

        /**
         * Counts distinct local-dates (per [zone]) on which the user
         * completed at least one task or one habit. Used to gate the
         * badge so brand-new users don't see a misleading 100/100.
         */
        fun activeDayCount(
            tasks: List<com.averycorp.prismtask.data.local.entity.TaskEntity>,
            habitCompletions: List<com.averycorp.prismtask.data.local.entity.HabitCompletionEntity>,
            zone: ZoneId
        ): Int {
            val activeDays = sortedSetOf<LocalDate>()
            tasks.asSequence()
                .filter { it.isCompleted && it.completedAt != null }
                .forEach {
                    activeDays += java.time.Instant.ofEpochMilli(it.completedAt!!).atZone(zone).toLocalDate()
                }
            habitCompletions.forEach {
                activeDays += java.time.Instant.ofEpochMilli(it.completedDate).atZone(zone).toLocalDate()
            }
            return activeDays.size
        }
    }
}
