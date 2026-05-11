package com.averycorp.prismtask.ui.screens.planner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.billing.UserTier
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.remote.api.WeeklyPlanPreferencesRequest
import com.averycorp.prismtask.data.remote.api.WeeklyPlanRequest
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

data class WeeklyPlanConfig(
    val workDays: List<String> = listOf("MO", "TU", "WE", "TH", "FR"),
    val focusHoursPerDay: Int = 6,
    val preferFrontLoading: Boolean = true
)

data class PlannedTask(val taskId: Long, val title: String, val suggestedTime: String, val durationMinutes: Int, val reason: String)

data class DayPlan(
    val date: String,
    val dayName: String,
    val tasks: List<PlannedTask>,
    val totalHours: Double,
    val calendarEvents: List<String>,
    val habits: List<String>
)

data class UnscheduledTask(
    // Null when the AI returned a Firestore doc id that couldn't be resolved
    // to a local row (cross-device task not yet synced). Title + reason
    // still surface so the user knows the task exists.
    val taskId: Long?,
    val title: String,
    val reason: String
)

data class WeeklyPlan(val days: List<DayPlan>, val unscheduled: List<UnscheduledTask>, val weekSummary: String, val tips: List<String>)

@HiltViewModel
class WeeklyPlannerViewModel
@Inject
constructor(
    private val api: PrismTaskApi,
    private val taskDao: TaskDao,
    private val proFeatureGate: ProFeatureGate,
    private val taskBehaviorPreferences: TaskBehaviorPreferences
) : ViewModel() {
    val userTier: StateFlow<UserTier> = proFeatureGate.userTier

    private val _config = MutableStateFlow(WeeklyPlanConfig())
    val config: StateFlow<WeeklyPlanConfig> = _config

    private val _plan = MutableStateFlow<WeeklyPlan?>(null)
    val plan: StateFlow<WeeklyPlan?> = _plan

    private val _selectedDayIndex = MutableStateFlow(0)
    val selectedDayIndex: StateFlow<Int> = _selectedDayIndex

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _showUpgradePrompt = MutableStateFlow(false)
    val showUpgradePrompt: StateFlow<Boolean> = _showUpgradePrompt

    private val _weekStart = MutableStateFlow(computeNextWeekStart(DayOfWeek.MONDAY))
    val weekStart: StateFlow<LocalDate> = _weekStart

    private val _planApplied = MutableStateFlow(false)
    val planApplied: StateFlow<Boolean> = _planApplied

    init {
        viewModelScope.launch {
            val fdow = taskBehaviorPreferences.getFirstDayOfWeek().first()
            _weekStart.value = computeNextWeekStart(fdow)
        }
    }

    private fun computeNextWeekStart(fdow: DayOfWeek): LocalDate {
        val today = LocalDate.now()
        return if (today.dayOfWeek == fdow) {
            today
        } else {
            today.with(TemporalAdjusters.next(fdow))
        }
    }

    fun navigateWeek(offset: Int) {
        _weekStart.value = _weekStart.value.plusWeeks(offset.toLong())
        _plan.value = null
        _planApplied.value = false
    }

    fun updateWorkDays(days: List<String>) {
        _config.value = _config.value.copy(workDays = days)
    }

    fun updateFocusHours(hours: Int) {
        _config.value = _config.value.copy(focusHoursPerDay = hours)
    }

    fun toggleFrontLoading() {
        _config.value = _config.value.copy(preferFrontLoading = !_config.value.preferFrontLoading)
    }

    fun selectDay(index: Int) {
        _selectedDayIndex.value = index
    }

    fun dismissUpgradePrompt() {
        _showUpgradePrompt.value = false
    }

    fun generatePlan() {
        if (!proFeatureGate.hasAccess(ProFeatureGate.AI_WEEKLY_PLAN)) {
            _showUpgradePrompt.value = true
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _planApplied.value = false
            try {
                val cfg = _config.value
                val response = api.getWeeklyPlan(
                    WeeklyPlanRequest(
                        weekStart = _weekStart.value.format(DateTimeFormatter.ISO_LOCAL_DATE),
                        preferences = WeeklyPlanPreferencesRequest(
                            workDays = cfg.workDays,
                            focusHoursPerDay = cfg.focusHoursPerDay,
                            preferFrontLoading = cfg.preferFrontLoading
                        )
                    )
                )

                // Resolve Firestore doc IDs to local Long task ids. Tasks
                // not present locally (e.g. created on another device and
                // not yet synced down) are demoted into the unscheduled
                // list with a "Not synced to this device" reason so the
                // rest of the plan still renders.
                val demoted = mutableListOf<UnscheduledTask>()
                val dayOrder = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
                val days = dayOrder.mapNotNull { dayName ->
                    response.plan[dayName]?.let { dayPlan ->
                        val resolvedTasks = dayPlan.tasks.mapNotNull { t ->
                            val localId = taskDao.getIdByCloudId(t.taskId)
                            if (localId == null) {
                                demoted += UnscheduledTask(null, t.title, "Not synced to this device")
                                null
                            } else {
                                PlannedTask(localId, t.title, t.suggestedTime, t.durationMinutes, t.reason)
                            }
                        }
                        DayPlan(
                            date = dayPlan.date,
                            dayName = dayName,
                            tasks = resolvedTasks,
                            totalHours = dayPlan.totalHours,
                            calendarEvents = dayPlan.calendarEvents,
                            habits = dayPlan.habits
                        )
                    }
                }

                val resolvedUnscheduled = response.unscheduled.map {
                    UnscheduledTask(taskDao.getIdByCloudId(it.taskId), it.title, it.reason)
                } + demoted

                _plan.value = WeeklyPlan(
                    days = days,
                    unscheduled = resolvedUnscheduled,
                    weekSummary = response.weekSummary,
                    tips = response.tips
                )
                _selectedDayIndex.value = 0
            } catch (e: Exception) {
                _error.value = "Couldn't generate plan"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun moveTaskToDay(taskId: Long, targetDayIndex: Int) {
        val currentPlan = _plan.value ?: return
        val currentDays = currentPlan.days.toMutableList()
        var movedTask: PlannedTask? = null

        // Remove from current day
        for (i in currentDays.indices) {
            val day = currentDays[i]
            val task = day.tasks.find { it.taskId == taskId }
            if (task != null) {
                movedTask = task
                currentDays[i] = day.copy(tasks = day.tasks.filter { it.taskId != taskId })
                break
            }
        }

        // Also check unscheduled. Unresolved cross-device entries
        // (taskId == null) aren't movable — they're informational.
        val unscheduled = currentPlan.unscheduled.toMutableList()
        if (movedTask == null) {
            val fromUnscheduled = unscheduled.find { it.taskId == taskId }
            if (fromUnscheduled != null) {
                movedTask = PlannedTask(taskId, fromUnscheduled.title, "TBD", 30, "Manually scheduled")
                unscheduled.removeAll { it.taskId == taskId }
            }
        }

        if (movedTask != null && targetDayIndex in currentDays.indices) {
            val targetDay = currentDays[targetDayIndex]
            currentDays[targetDayIndex] = targetDay.copy(tasks = targetDay.tasks + movedTask)
        }

        _plan.value = currentPlan.copy(days = currentDays, unscheduled = unscheduled)
    }

    fun applyPlan() {
        val plan = _plan.value ?: return
        viewModelScope.launch {
            try {
                val ws = _weekStart.value
                for (day in plan.days) {
                    val dayDate = LocalDate.parse(day.date, DateTimeFormatter.ISO_LOCAL_DATE)
                    val dayMillis = dayDate.toEpochDay() * 86400000L
                    for ((index, task) in day.tasks.withIndex()) {
                        taskDao.updatePlannedDateAndSortOrder(task.taskId, dayMillis, index)
                    }
                }
                _planApplied.value = true
            } catch (e: Exception) {
                _error.value = "Couldn't apply plan"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
