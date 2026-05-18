package com.averycorp.prismtask.ui.screens.habits

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.entity.HabitCompletionEntity
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.preferences.HabitListPreferences
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.repository.HabitRepository
import com.averycorp.prismtask.domain.usecase.HabitForgivenessResolver
import com.averycorp.prismtask.domain.usecase.StreakCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

data class HabitAnalyticsState(
    val habit: HabitEntity? = null,
    val completions: List<HabitCompletionEntity> = emptyList(),
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val totalCompletions: Int = 0,
    val rate7d: Float = 0f,
    val rate30d: Float = 0f,
    val rate90d: Float = 0f,
    val completionsByDay: Map<LocalDate, Int> = emptyMap(),
    val weeklyTotals: List<Int> = emptyList(),
    val dayOfWeekAverages: Map<DayOfWeek, Float> = emptyMap(),
    val bestDay: DayOfWeek? = null,
    val worstDay: DayOfWeek? = null,
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY
)

@HiltViewModel
class HabitAnalyticsViewModel
@Inject
constructor(
    private val habitRepository: HabitRepository,
    private val habitListPreferences: HabitListPreferences,
    private val taskBehaviorPreferences: TaskBehaviorPreferences,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val habitId: Long = savedStateHandle.get<Long>("habitId") ?: -1L

    private val _state = MutableStateFlow(HabitAnalyticsState())
    val state: StateFlow<HabitAnalyticsState> = _state

    init {
        viewModelScope.launch {
            val habit = habitRepository.getHabitByIdOnce(habitId) ?: return@launch
            val completions = habitRepository.getCompletionsForHabitOnce(habitId)
            val globalStreakMaxMissedDays = habitListPreferences.getStreakMaxMissedDays().first()
            val streakMaxMissedDays = HabitForgivenessResolver.resolveMaxMissedDays(
                habit,
                globalStreakMaxMissedDays
            )
            val firstDayOfWeek = taskBehaviorPreferences.getFirstDayOfWeek().first()
            val today = LocalDate.now()
            val gridStart = today.minusWeeks(11).with(TemporalAdjusters.previousOrSame(firstDayOfWeek))

            val completionsByDay = StreakCalculator.getCompletionsByDay(completions, gridStart, today)

            // Weekly totals for last 12 weeks
            val weeklyTotals = (0 until 12).map { weekOffset ->
                val weekStart = today.minusWeeks(11L - weekOffset).with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
                val weekEnd = weekStart.plusDays(6)
                completions.count { c ->
                    val d = java.time.Instant
                        .ofEpochMilli(c.completedDate)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()
                    d in weekStart..weekEnd
                }
            }

            // Day-of-week averages
            val totalWeeks = 12f
            val dayAverages = DayOfWeek.entries.associateWith { dow ->
                completions.count { c ->
                    val d = java.time.Instant
                        .ofEpochMilli(c.completedDate)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()
                    d.dayOfWeek == dow && d >= gridStart
                } / totalWeeks
            }

            _state.value = HabitAnalyticsState(
                habit = habit,
                completions = completions,
                currentStreak = StreakCalculator.calculateCurrentStreak(completions, habit, today, streakMaxMissedDays, firstDayOfWeek),
                longestStreak = StreakCalculator.calculateLongestStreak(completions, habit, today, streakMaxMissedDays, firstDayOfWeek),
                totalCompletions = completions.size,
                rate7d = StreakCalculator.calculateCompletionRate(completions, habit, 7, today),
                rate30d = StreakCalculator.calculateCompletionRate(completions, habit, 30, today),
                rate90d = StreakCalculator.calculateCompletionRate(completions, habit, 90, today),
                completionsByDay = completionsByDay,
                weeklyTotals = weeklyTotals,
                dayOfWeekAverages = dayAverages,
                bestDay = StreakCalculator.getBestDay(completions),
                worstDay = StreakCalculator.getWorstDay(completions),
                firstDayOfWeek = firstDayOfWeek
            )
        }
    }

    fun archiveHabit() {
        viewModelScope.launch {
            habitRepository.archiveHabit(habitId)
        }
    }
}
