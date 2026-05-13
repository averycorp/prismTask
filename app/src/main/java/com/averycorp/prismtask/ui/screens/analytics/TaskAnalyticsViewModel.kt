package com.averycorp.prismtask.ui.screens.analytics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.preferences.ProductiveStreakPreferences
import com.averycorp.prismtask.data.preferences.ProductiveStreakSnapshot
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.repository.HabitRepository
import com.averycorp.prismtask.data.repository.ProjectRepository
import com.averycorp.prismtask.data.repository.TaskCompletionRepository
import com.averycorp.prismtask.data.repository.TaskCompletionStats
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.data.repository.TaskTimingRepository
import com.averycorp.prismtask.domain.model.AnalyticsSummary
import com.averycorp.prismtask.domain.model.ProductivityRange
import com.averycorp.prismtask.domain.model.ProductivityScoreResponse
import com.averycorp.prismtask.domain.model.TimeTrackingResponse
import com.averycorp.prismtask.domain.usecase.AnalyticsMarkdownExporter
import com.averycorp.prismtask.domain.usecase.AnalyticsSummaryAggregator
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import com.averycorp.prismtask.domain.usecase.ProductivityScoreCalculator
import com.averycorp.prismtask.domain.usecase.TimeTrackingAggregator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject

enum class AnalyticsPeriod(
    val days: Int,
    val label: String
) {
    WEEK(7, "7 Days"),
    MONTH(30, "30 Days"),
    QUARTER(90, "90 Days"),
    YEAR(365, "Year")
}

data class TaskAnalyticsState(
    val stats: TaskCompletionStats? = null,
    val selectedPeriod: AnalyticsPeriod = AnalyticsPeriod.MONTH,
    val selectedProjectId: Long? = null,
    val projects: List<ProjectEntity> = emptyList(),
    val isLoading: Boolean = true,
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    val summary: AnalyticsSummary? = null,
    val isPro: Boolean = false,
    val productivity: ProductivityScoreResponse? = null,
    val productivityRange: ProductivityRange = ProductivityRange.THIRTY_DAYS,
    val timeTracking: TimeTrackingResponse? = null,
    val streak: ProductiveStreakSnapshot? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TaskAnalyticsViewModel
@Inject
constructor(
    private val taskCompletionRepository: TaskCompletionRepository,
    private val projectRepository: ProjectRepository,
    private val taskBehaviorPreferences: TaskBehaviorPreferences,
    private val taskRepository: TaskRepository,
    private val habitRepository: HabitRepository,
    private val taskTimingRepository: TaskTimingRepository,
    private val analyticsSummaryAggregator: AnalyticsSummaryAggregator,
    private val productivityScoreCalculator: ProductivityScoreCalculator,
    private val timeTrackingAggregator: TimeTrackingAggregator,
    private val proFeatureGate: ProFeatureGate,
    private val productiveStreakPreferences: ProductiveStreakPreferences,
    private val analyticsMarkdownExporter: AnalyticsMarkdownExporter,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val initialProjectId: Long? = savedStateHandle
        .get<Long>("projectId")
        ?.takeIf { it > 0 }

    private val _selectedPeriod = MutableStateFlow(AnalyticsPeriod.MONTH)
    private val _selectedProjectId = MutableStateFlow(initialProjectId)
    private val _productivityRange = MutableStateFlow(ProductivityRange.THIRTY_DAYS)

    private val statsFlow: Flow<TaskCompletionStats> = combine(
        _selectedPeriod,
        _selectedProjectId
    ) { period, projectId ->
        if (projectId != null) {
            taskCompletionRepository.getProjectStats(projectId, period.days)
        } else {
            taskCompletionRepository.getCompletionStats(period.days)
        }
    }.flatMapLatest { it }

    private val summaryFlow: Flow<AnalyticsSummary> = run {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val todayEnd = todayStart + TimeUnit.DAYS.toMillis(1) - 1
        val thirtyDaysAgo = todayStart - TimeUnit.DAYS.toMillis(29)
        combine(
            taskRepository.getTasksDueOnDate(todayStart, todayEnd),
            taskCompletionRepository.getCompletionsInRange(thirtyDaysAgo, todayEnd),
            habitRepository.getActiveHabits(),
            habitRepository.getAllCompletionsInRange(thirtyDaysAgo, todayEnd)
        ) { tasks, taskCompletions, habits, habitCompletions ->
            analyticsSummaryAggregator.compute(
                today = today,
                zone = zone,
                tasksDueToday = tasks,
                completionsLast30Days = taskCompletions,
                activeHabits = habits,
                habitCompletionsLast30Days = habitCompletions
            )
        }
    }

    private val timeTrackingFlow: Flow<TimeTrackingResponse> = _productivityRange.flatMapLatest { range ->
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startDate = today.minusDays((range.days - 1).toLong())
        val startMillis = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillisExclusive = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        taskTimingRepository.getTimingsInRange(startMillis, endMillisExclusive).map { timings ->
            timeTrackingAggregator.compute(
                endDate = today,
                zone = zone,
                range = range,
                timings = timings
            )
        }
    }

    private val productivityFlow: Flow<ProductivityScoreResponse> = _productivityRange.flatMapLatest { range ->
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startDate = today.minusDays((range.days - 1).toLong())
        val startMillis = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillisExclusive = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        combine(
            taskRepository.getTasksForAnalyticsRange(startMillis, endMillisExclusive),
            habitRepository.getActiveHabits(),
            habitRepository.getAllCompletionsInRange(startMillis, endMillisExclusive - 1)
        ) { tasks, habits, habitCompletions ->
            productivityScoreCalculator.compute(
                startDate = startDate,
                endDate = today,
                zone = zone,
                tasks = tasks,
                activeHabitsCount = habits.size,
                habitCompletions = habitCompletions
            )
        }
    }

    private val isProFlow: Flow<Boolean> = proFeatureGate.userTier.map {
        // Both ANALYTICS_FULL and the legacy isPro check resolve to the same
        // PRO tier; using the gate keyword keeps the Pro feature inventory in
        // ProFeatureGate authoritative, so swapping the analytics tier (e.g.
        // moving to a freemium split later) only requires editing the gate.
        proFeatureGate.hasAccess(ProFeatureGate.ANALYTICS_FULL)
    }

    private val projectsAndDowFlow = combine(
        projectRepository.getAllProjects(),
        taskBehaviorPreferences.getFirstDayOfWeek()
    ) { p, f -> p to f }

    private val summaryProAndStreakFlow = combine(
        summaryFlow,
        isProFlow,
        productiveStreakPreferences.observe()
    ) { s, p, streak -> Triple(s, p, streak) }

    private val productivityAndRangeFlow = combine(
        productivityFlow,
        _productivityRange,
        timeTrackingFlow
    ) { p, r, t -> Triple(p, r, t) }

    private val basicTriple = combine(
        statsFlow,
        _selectedPeriod,
        _selectedProjectId
    ) { stats, period, projectId -> Triple(stats, period, projectId) }

    val state: StateFlow<TaskAnalyticsState> = combine(
        basicTriple,
        projectsAndDowFlow,
        summaryProAndStreakFlow,
        productivityAndRangeFlow
    ) { (stats, period, projectId), (projects, fdow), (summary, isPro, streak), pAndR ->
        val (productivity, range, timeTracking) = pAndR
        TaskAnalyticsState(
            stats = stats,
            selectedPeriod = period,
            selectedProjectId = projectId,
            projects = projects,
            isLoading = false,
            firstDayOfWeek = fdow,
            summary = summary,
            isPro = isPro,
            productivity = productivity,
            productivityRange = range,
            timeTracking = timeTracking,
            streak = streak
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        TaskAnalyticsState()
    )

    fun setPeriod(period: AnalyticsPeriod) {
        _selectedPeriod.value = period
    }

    fun setProject(projectId: Long?) {
        _selectedProjectId.value = projectId
    }

    fun setProductivityRange(range: ProductivityRange) {
        _productivityRange.value = range
    }

    /**
     * Builds the markdown report for the share-sheet action. Snapshot-only —
     * relies on the latest collected [state]; doesn't trigger a fresh
     * recomputation. Safe to call from the UI thread.
     */
    fun buildExportMarkdown(): String {
        val snapshot = state.value
        return analyticsMarkdownExporter.build(
            generatedOn = LocalDate.now(),
            rangeDays = snapshot.productivityRange.days,
            productivity = snapshot.productivity,
            timeTracking = snapshot.timeTracking,
            stats = snapshot.stats,
            summary = snapshot.summary,
            streak = snapshot.streak
        )
    }
}
