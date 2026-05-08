package com.averycorp.prismtask.ui.screens.checkin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.calendar.CalendarEventInfo
import com.averycorp.prismtask.data.calendar.CalendarManager
import com.averycorp.prismtask.data.local.entity.MedicationRefillEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.preferences.WorkLifeBalancePrefs
import com.averycorp.prismtask.data.repository.CalendarEventRepository
import com.averycorp.prismtask.data.repository.CheckInLogRepository
import com.averycorp.prismtask.data.repository.HabitRepository
import com.averycorp.prismtask.data.repository.HabitWithStatus
import com.averycorp.prismtask.data.repository.MedicationRefillRepository
import com.averycorp.prismtask.data.repository.MoodEnergyRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.model.LifeCategory
import com.averycorp.prismtask.domain.usecase.BalanceConfig
import com.averycorp.prismtask.domain.usecase.BalanceState
import com.averycorp.prismtask.domain.usecase.BalanceTracker
import com.averycorp.prismtask.domain.usecase.BurnoutResult
import com.averycorp.prismtask.domain.usecase.BurnoutScorer
import com.averycorp.prismtask.domain.usecase.CheckInStep
import com.averycorp.prismtask.domain.usecase.MorningCheckInConfig
import com.averycorp.prismtask.domain.usecase.MorningCheckInResolver
import com.averycorp.prismtask.domain.usecase.RefillCalculator
import com.averycorp.prismtask.domain.usecase.RefillForecast
import com.averycorp.prismtask.domain.usecase.RefillUrgency
import com.averycorp.prismtask.util.DayBoundary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ViewModel for the Morning Check-In screen (v1.4.0 V4).
 *
 * Holds the resolved [com.averycorp.prismtask.domain.usecase.CheckInPlan],
 * tracks which steps the user actually walked through this session, and
 * writes a [com.averycorp.prismtask.data.local.entity.CheckInLogEntity]
 * row on completion so the Today screen stops prompting for the day.
 *
 * The ViewModel also owns the live data needed by each interactive step
 * (medications, habits, balance bar, burnout score, today's calendar
 * events) so the step composables stay purely presentational.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MorningCheckInViewModel
@Inject
constructor(
    private val taskRepository: TaskRepository,
    private val habitRepository: HabitRepository,
    private val moodEnergyRepository: MoodEnergyRepository,
    private val checkInLogRepository: CheckInLogRepository,
    private val medicationRefillRepository: MedicationRefillRepository,
    private val calendarManager: CalendarManager,
    private val calendarEventRepository: CalendarEventRepository,
    private val userPreferencesDataStore: UserPreferencesDataStore,
    private val taskBehaviorPreferences: TaskBehaviorPreferences
) : ViewModel() {
    private val resolver = MorningCheckInResolver()
    private val balanceTracker = BalanceTracker()
    private val burnoutScorer = BurnoutScorer()

    private val _completedSteps = MutableStateFlow<Set<CheckInStep>>(emptySet())
    val completedSteps: StateFlow<Set<CheckInStep>> = _completedSteps

    private val _isFinished = MutableStateFlow(false)
    val isFinished: StateFlow<Boolean> = _isFinished

    private val _plan = MutableStateFlow(CheckInScreenState())
    val plan: StateFlow<CheckInScreenState> = _plan

    private val _medicationDoses = MutableStateFlow<Set<Long>>(emptySet())

    /** Today's tracked medications paired with refill forecasts + "taken" flag. */
    val medications: StateFlow<List<MedicationCheckInItem>> =
        combine(
            medicationRefillRepository.observeAll(),
            _medicationDoses
        ) { refills, taken ->
            refills.map { refill ->
                MedicationCheckInItem(
                    refill = refill,
                    forecast = RefillCalculator.forecast(refill),
                    taken = taken.contains(refill.id)
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Live habit list (already day-aware) so toggles update the UI instantly. */
    val todayHabits: StateFlow<List<HabitWithStatus>> =
        habitRepository
            .getHabitsWithTodayStatus()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** User's Work-Life Balance configuration — target ratios, overload threshold. */
    private val workLifeBalancePrefs: StateFlow<WorkLifeBalancePrefs> =
        userPreferencesDataStore.workLifeBalanceFlow
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WorkLifeBalancePrefs())

    /** Live balance state (last 7 days of task categorization). */
    val balanceState: StateFlow<BalanceState> =
        combine(
            taskRepository.getAllTasks(),
            workLifeBalancePrefs,
            taskBehaviorPreferences.getStartOfDay()
        ) { allTasks, prefs, sod ->
            val config = BalanceConfig(
                workTarget = prefs.workTarget / 100f,
                personalTarget = prefs.personalTarget / 100f,
                selfCareTarget = prefs.selfCareTarget / 100f,
                healthTarget = prefs.healthTarget / 100f,
                overloadThreshold = prefs.overloadThresholdPct / 100f
            )
            balanceTracker.compute(
                allTasks,
                config,
                dayStartHour = sod.hour,
                dayStartMinute = sod.minute
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BalanceState.EMPTY)

    /** Composite burnout result (0–100 score + band) for the Balance step badge. */
    val burnoutResult: StateFlow<BurnoutResult> =
        combine(
            taskRepository.getAllTasks(),
            workLifeBalancePrefs,
            balanceState
        ) { allTasks, prefs, balance ->
            val workRatio = balance.currentRatios[LifeCategory.WORK] ?: 0f
            burnoutScorer.computeFromTasks(
                tasks = allTasks,
                workRatio = workRatio,
                workTarget = prefs.workTarget / 100f
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BurnoutResult.EMPTY)

    /** Whether Google Calendar is currently connected (OAuth scope granted). */
    val calendarConnected: StateFlow<Boolean> = calendarManager.isCalendarConnected

    private val _calendarEvents = MutableStateFlow<List<CalendarEventInfo>>(emptyList())
    val calendarEvents: StateFlow<List<CalendarEventInfo>> = _calendarEvents.asStateFlow()

    init {
        viewModelScope.launch {
            val dayStartHour = taskBehaviorPreferences.getDayStartHour().first()
            val todayStart = DayBoundary.startOfCurrentDay(dayStartHour)
            val tasks = taskRepository.getAllTasks().first()
            val habits = habitRepository.getHabitsWithTodayStatus().first()
            val plan = resolver.plan(
                tasks = tasks,
                habits = habits,
                config = MorningCheckInConfig(),
                todayStart = todayStart
            )
            _plan.value = CheckInScreenState(
                steps = plan.steps,
                topTasks = plan.topTasks,
                habits = plan.todayHabits
            )
        }
        loadCalendarEvents()
    }

    private fun loadCalendarEvents() {
        viewModelScope.launch {
            if (!calendarManager.isCalendarConnected.value) return@launch
            val dayStartHour = taskBehaviorPreferences.getDayStartHour().first()
            val now = System.currentTimeMillis()
            val dayEnd = DayBoundary.endOfCurrentDay(dayStartHour)
            val events = withContext(Dispatchers.IO) {
                runCatching {
                    calendarEventRepository.getTodayUpcomingEvents(
                        now = now,
                        dayEnd = dayEnd,
                        limit = 3
                    )
                }.getOrDefault(emptyList())
            }
            _calendarEvents.value = events
        }
    }

    fun markStepComplete(step: CheckInStep) {
        _completedSteps.value = _completedSteps.value + step
    }

    fun logMoodEnergy(mood: Int, energy: Int, notes: String? = null) {
        viewModelScope.launch {
            val dayStartHour = taskBehaviorPreferences.getDayStartHour().first()
            val todayStart = DayBoundary.startOfCurrentDay(dayStartHour)
            moodEnergyRepository.upsertForDate(
                date = todayStart,
                mood = mood,
                energy = energy,
                notes = notes,
                timeOfDay = "morning"
            )
            markStepComplete(CheckInStep.MOOD_ENERGY)
        }
    }

    /**
     * Records a taken dose for the given medication. Decrements the on-hand
     * pill count via [MedicationRefillRepository.applyDailyDose] and marks
     * the row as "taken" in local state so the UI checkbox stays checked.
     */
    fun takeMedicationDose(refill: MedicationRefillEntity) {
        if (_medicationDoses.value.contains(refill.id)) return
        _medicationDoses.value = _medicationDoses.value + refill.id
        viewModelScope.launch {
            medicationRefillRepository.applyDailyDose(refill)
        }
    }

    /**
     * Toggles the completion state of a habit for today. Calls the
     * repository's complete/uncomplete helpers, which also respect
     * per-habit targets and medication-interval habits.
     */
    fun toggleHabit(habit: HabitWithStatus) {
        viewModelScope.launch {
            val dayStartHour = taskBehaviorPreferences.getDayStartHour().first()
            val todayStart = DayBoundary.startOfCurrentDay(dayStartHour)
            if (habit.isCompletedToday) {
                habitRepository.uncompleteHabit(habit.habit.id, todayStart)
            } else {
                habitRepository.completeHabit(habit.habit.id, todayStart)
            }
        }
    }

    /** Finalizes the check-in: persists a CheckInLog row and flips [isFinished]. */
    fun finalize() {
        viewModelScope.launch {
            val dayStartHour = taskBehaviorPreferences.getDayStartHour().first()
            val todayStart = DayBoundary.startOfCurrentDay(dayStartHour)
            checkInLogRepository.record(
                date = todayStart,
                stepsCompleted = _completedSteps.value.toList(),
                medicationsConfirmed = _medicationDoses.value.size,
                tasksReviewed = _plan.value.topTasks.size,
                habitsCompleted = todayHabits.value.count { it.isCompletedToday }
            )
            _isFinished.value = true
        }
    }
}

data class CheckInScreenState(
    val steps: List<CheckInStep> = emptyList(),
    val topTasks: List<TaskEntity> = emptyList(),
    val habits: List<HabitWithStatus> = emptyList()
)

/**
 * UI-layer bundle for a single medication row in the Morning Check-In
 * "Medications" step. Pairs the entity with its computed refill forecast
 * and a transient "taken this morning" flag held in the ViewModel.
 */
data class MedicationCheckInItem(
    val refill: MedicationRefillEntity,
    val forecast: RefillForecast,
    val taken: Boolean
) {
    val isRefillUrgent: Boolean
        get() = forecast.urgency == RefillUrgency.URGENT ||
            forecast.urgency == RefillUrgency.OUT_OF_STOCK
}
