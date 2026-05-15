package com.averycorp.prismtask.ui.screens.balance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.model.LifeCategory
import com.averycorp.prismtask.domain.usecase.BalanceConfig
import com.averycorp.prismtask.domain.usecase.BalanceContributionsProvider
import com.averycorp.prismtask.domain.usecase.BalanceState
import com.averycorp.prismtask.domain.usecase.BalanceTracker
import com.averycorp.prismtask.domain.usecase.BurnoutBand
import com.averycorp.prismtask.domain.usecase.BurnoutScorer
import com.averycorp.prismtask.domain.usecase.CognitiveLoadBalanceConfig
import com.averycorp.prismtask.domain.usecase.CognitiveLoadBalanceState
import com.averycorp.prismtask.domain.usecase.CognitiveLoadBalanceTracker
import com.averycorp.prismtask.domain.usecase.WeeklyReviewAggregator
import com.averycorp.prismtask.domain.usecase.WeeklyReviewStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Weekly Balance Report (v1.4.0 V3 phase 2).
 *
 * Aggregates the current week's stats via [WeeklyReviewAggregator] and
 * exposes them alongside the current [BalanceState] and burnout score.
 * The "Previous / Next Week" navigation lets the user scroll through
 * past weeks; each re-aggregation hits the same pure function so the
 * screen is functionally deterministic.
 */
@HiltViewModel
class WeeklyBalanceReportViewModel
@Inject
constructor(
    private val taskRepository: TaskRepository,
    private val userPreferencesDataStore: UserPreferencesDataStore,
    private val taskBehaviorPreferences: TaskBehaviorPreferences,
    private val balanceContributionsProvider: BalanceContributionsProvider
) : ViewModel() {
    private val aggregator = WeeklyReviewAggregator()
    private val balanceTracker = BalanceTracker()
    private val cognitiveLoadBalanceTracker = CognitiveLoadBalanceTracker()
    private val burnoutScorer = BurnoutScorer()

    private val _state = MutableStateFlow(WeeklyBalanceReportState())
    val state: StateFlow<WeeklyBalanceReportState> = _state.asStateFlow()

    init {
        loadWeek(System.currentTimeMillis())
    }

    fun loadWeek(reference: Long) {
        viewModelScope.launch {
            try {
                val prefs = userPreferencesDataStore.workLifeBalanceFlow.first()
                val sod = taskBehaviorPreferences.getStartOfDay().first()
                val taskDefaults = userPreferencesDataStore.taskDefaultsFlow.first()
                val defaultDuration = taskDefaults.defaultDuration ?: 30
                val tasks = taskRepository.getAllTasksOnce()
                val contributions = balanceContributionsProvider.snapshot(now = reference)
                val stats = aggregator.aggregate(tasks, reference)
                val config = BalanceConfig(
                    workTarget = prefs.workTarget / 100f,
                    personalTarget = prefs.personalTarget / 100f,
                    selfCareTarget = prefs.selfCareTarget / 100f,
                    healthTarget = prefs.healthTarget / 100f,
                    overloadThreshold = prefs.overloadThresholdPct / 100f
                )
                val balance = balanceTracker.compute(
                    allTasks = tasks,
                    config = config,
                    now = reference,
                    dayStartHour = sod.hour,
                    dayStartMinute = sod.minute,
                    habitContributions = contributions.habits,
                    leisureContributions = contributions.leisure,
                    defaultDurationMinutes = defaultDuration
                )
                val cognitiveLoadBalance = cognitiveLoadBalanceTracker.compute(
                    allTasks = tasks,
                    config = CognitiveLoadBalanceConfig(),
                    now = reference,
                    dayStartHour = sod.hour,
                    dayStartMinute = sod.minute,
                    habitCompletionTimestamps = contributions.habitTimestamps,
                    leisureSessionTimestamps = contributions.leisureTimestamps,
                    defaultDurationMinutes = defaultDuration
                )
                val workRatio = balance.currentRatios[LifeCategory.WORK] ?: 0f
                val burnout = burnoutScorer.computeFromTasks(
                    tasks = tasks,
                    workRatio = workRatio,
                    workTarget = prefs.workTarget / 100f,
                    now = reference,
                    habitStreakBreaks = contributions.habitStreakBreaks,
                    selfCareHabitCompletionsRecent = contributions.selfCareHabitCompletionsRecent,
                    leisureMinutesRecent = contributions.leisureMinutesRecent
                )

                // v1.4.0 V3 phase 3: 4-week rolling trend per tracked
                // category. We collect both the ratio (for normalized sparkline
                // rendering) and the absolute count (to show this-vs-last-week
                // deltas in the sparkline label). Lists are oldest → newest
                // so index 3 is the selected week.
                val weekMillis = SEVEN_DAYS_MILLIS
                val trendRatios = mutableMapOf<LifeCategory, MutableList<Float>>()
                val trendCounts = mutableMapOf<LifeCategory, MutableList<Int>>()
                LifeCategory.TRACKED.forEach {
                    trendRatios[it] = mutableListOf()
                    trendCounts[it] = mutableListOf()
                }
                for (i in 3 downTo 0) {
                    val weekRef = reference - i.toLong() * weekMillis
                    val weekStats = aggregator.aggregate(tasks, weekRef)
                    val totalInWeek = weekStats.byCategory.values
                        .sum()
                        .coerceAtLeast(1)
                    LifeCategory.TRACKED.forEach { cat ->
                        val count = weekStats.byCategory[cat] ?: 0
                        trendRatios.getValue(cat).add(count.toFloat() / totalInWeek.toFloat())
                        trendCounts.getValue(cat).add(count)
                    }
                }

                _state.value = WeeklyBalanceReportState(
                    stats = stats,
                    balance = balance,
                    cognitiveLoadBalance = cognitiveLoadBalance,
                    burnoutScore = burnout.score,
                    burnoutBand = burnout.band,
                    reference = reference,
                    fourWeekTrend = trendRatios.mapValues { it.value.toList() },
                    fourWeekCounts = trendCounts.mapValues { it.value.toList() }
                )
            } catch (e: Exception) {
                android.util.Log.e("WeeklyBalanceVM", "Failed to load week", e)
            }
        }
    }

    fun previousWeek() {
        loadWeek(_state.value.reference - SEVEN_DAYS_MILLIS)
    }

    fun nextWeek() {
        loadWeek(_state.value.reference + SEVEN_DAYS_MILLIS)
    }

    companion object {
        private const val SEVEN_DAYS_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}

data class WeeklyBalanceReportState(
    val stats: WeeklyReviewStats? = null,
    val balance: BalanceState = BalanceState.EMPTY,
    val cognitiveLoadBalance: CognitiveLoadBalanceState = CognitiveLoadBalanceState.EMPTY,
    val burnoutScore: Int = 0,
    val burnoutBand: BurnoutBand = BurnoutBand.BALANCED,
    val reference: Long = System.currentTimeMillis(),
    /** 4-week rolling ratio trend per category (0f..1f), oldest → newest. */
    val fourWeekTrend: Map<LifeCategory, List<Float>> = emptyMap(),
    /** 4-week absolute completed-task count per category, oldest → newest. */
    val fourWeekCounts: Map<LifeCategory, List<Int>> = emptyMap()
)
