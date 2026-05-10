package com.averycorp.prismtask.ui.screens.habits

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.preferences.HabitListPreferences
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.data.repository.HabitRepository
import com.averycorp.prismtask.notifications.HabitReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddEditHabitViewModel
@Inject
constructor(
    private val habitRepository: HabitRepository,
    private val medicationReminderScheduler: HabitReminderScheduler,
    private val notificationPreferences: NotificationPreferences,
    private val habitListPreferences: HabitListPreferences,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val _errorMessages = MutableSharedFlow<String>()
    val errorMessages: SharedFlow<String> = _errorMessages.asSharedFlow()

    private val habitId: Long? = savedStateHandle.get<Long>("habitId")?.takeIf { it != -1L }
    val isEditMode: Boolean = habitId != null

    private var existingHabit: HabitEntity? = null

    var name by mutableStateOf("")
        private set
    var description by mutableStateOf("")
        private set
    var targetFrequency by mutableIntStateOf(1)
        private set
    var frequencyPeriod by mutableStateOf("daily")
        private set
    var activeDays by mutableStateOf<Set<Int>>(emptySet())
        private set
    var color by mutableStateOf("#4A90D9")
        private set
    var icon by mutableStateOf("\u2B50")
        private set
    var reminderEnabled by mutableStateOf(false)
        private set
    var reminderHour by mutableIntStateOf(9)
        private set
    var reminderMinute by mutableIntStateOf(0)
        private set
    var category by mutableStateOf("")
        private set
    var medicationReminderEnabled by mutableStateOf(false)
        private set
    var medicationReminderIntervalIndex by mutableIntStateOf(2) // 1=0.5h, 2=1h, ..., 48=24h
        private set
    var medicationTimesPerDay by mutableIntStateOf(1)
        private set
    var hasLogging by mutableStateOf(false)
        private set
    var trackBooking by mutableStateOf(false)
        private set
    var trackPreviousPeriod by mutableStateOf(false)
        private set
    var isBookable by mutableStateOf(false)
        private set
    var showStreak by mutableStateOf(false)
        private set
    var createDailyTask by mutableStateOf(false)
        private set
    var nagSuppressionOverrideEnabled by mutableStateOf(false)
        private set
    var nagSuppressionDaysOverride by mutableIntStateOf(-1)
        private set
    var nagSuppressionDisableForHabit by mutableStateOf(false)
        private set
    var globalSuppressionDays by mutableIntStateOf(NotificationPreferences.DEFAULT_HABIT_NAG_SUPPRESSION_DAYS)
        private set
    var todaySkipAfterCompleteOverrideEnabled by mutableStateOf(false)
        private set
    var todaySkipAfterCompleteDays by mutableIntStateOf(0)
        private set
    var todaySkipBeforeScheduleOverrideEnabled by mutableStateOf(false)
        private set
    var todaySkipBeforeScheduleDays by mutableIntStateOf(0)
        private set
    var globalSkipAfterCompleteDays by mutableIntStateOf(HabitListPreferences.DEFAULT_TODAY_SKIP_AFTER_COMPLETE_DAYS)
        private set
    var globalSkipBeforeScheduleDays by mutableIntStateOf(HabitListPreferences.DEFAULT_TODAY_SKIP_BEFORE_SCHEDULE_DAYS)
        private set
    var nameError by mutableStateOf(false)
        private set
    var customCategories by mutableStateOf<List<String>>(emptyList())
        private set

    init {
        viewModelScope.launch {
            customCategories = habitRepository.getAllCategories()
            globalSuppressionDays = notificationPreferences.getHabitNagSuppressionDaysOnce()
            globalSkipAfterCompleteDays = habitListPreferences.getTodaySkipAfterCompleteDays().first()
            globalSkipBeforeScheduleDays = habitListPreferences.getTodaySkipBeforeScheduleDays().first()
        }
        if (habitId != null) {
            viewModelScope.launch {
                habitRepository.getHabitByIdOnce(habitId)?.let { habit ->
                    existingHabit = habit
                    name = habit.name
                    description = habit.description ?: ""
                    targetFrequency = habit.targetFrequency
                    frequencyPeriod = habit.frequencyPeriod
                    activeDays = parseActiveDays(habit.activeDays)
                    color = habit.color
                    icon = habit.icon
                    category = habit.category ?: ""
                    hasLogging = habit.hasLogging
                    trackBooking = habit.trackBooking
                    trackPreviousPeriod = habit.trackPreviousPeriod
                    isBookable = habit.isBookable
                    showStreak = habit.showStreak
                    createDailyTask = habit.createDailyTask
                    nagSuppressionOverrideEnabled = habit.nagSuppressionOverrideEnabled
                    nagSuppressionDaysOverride = habit.nagSuppressionDaysOverride
                    nagSuppressionDisableForHabit = habit.nagSuppressionDaysOverride == 0
                    todaySkipAfterCompleteOverrideEnabled = habit.todaySkipAfterCompleteDays >= 0
                    todaySkipAfterCompleteDays = habit.todaySkipAfterCompleteDays.coerceAtLeast(0)
                    todaySkipBeforeScheduleOverrideEnabled = habit.todaySkipBeforeScheduleDays >= 0
                    todaySkipBeforeScheduleDays = habit.todaySkipBeforeScheduleDays.coerceAtLeast(0)
                    if (habit.reminderTime != null) {
                        reminderEnabled = true
                        reminderHour = (habit.reminderTime / (60 * 60 * 1000)).toInt()
                        reminderMinute = ((habit.reminderTime % (60 * 60 * 1000)) / (60 * 1000)).toInt()
                    }
                    if (habit.reminderIntervalMillis != null) {
                        medicationReminderEnabled = true
                        medicationReminderIntervalIndex = (habit.reminderIntervalMillis / 1_800_000L).toInt().coerceIn(1, 48)
                        medicationTimesPerDay = habit.reminderTimesPerDay.coerceIn(1, 10)
                    }
                }
            }
        }
    }

    fun onNameChange(value: String) {
        name = value
        if (value.isNotBlank()) nameError = false
    }

    fun onDescriptionChange(value: String) {
        description = value
    }

    fun onTargetFrequencyChange(value: Int) {
        val max = when (frequencyPeriod) {
            "weekly" -> 7
            "fortnightly" -> 14
            "monthly" -> 30
            "bimonthly" -> 60
            "quarterly" -> 90
            else -> 10
        }
        targetFrequency = value.coerceIn(1, max)
    }

    fun onFrequencyPeriodChange(value: String) {
        val wasRecurring = frequencyPeriod != "daily"
        frequencyPeriod = value
        val max = when (value) {
            "weekly" -> 7
            "fortnightly" -> 14
            "monthly" -> 30
            "bimonthly" -> 60
            "quarterly" -> 90
            else -> 10
        }
        targetFrequency = targetFrequency.coerceIn(1, max)

        // For new habits, default logging / booking / previous-period tracking ON
        // when switching to a recurring (non-daily) frequency for the first time.
        if (!isEditMode && value != "daily" && !wasRecurring) {
            hasLogging = true
            trackBooking = true
            trackPreviousPeriod = true
        }
    }

    fun onToggleActiveDay(day: Int) {
        activeDays = if (day in activeDays) activeDays - day else activeDays + day
    }

    fun onColorChange(value: String) {
        color = value
    }

    fun onIconChange(value: String) {
        icon = value
    }

    fun onReminderEnabledChange(value: Boolean) {
        reminderEnabled = value
    }

    fun onReminderHourChange(value: Int) {
        reminderHour = value
    }

    fun onReminderMinuteChange(value: Int) {
        reminderMinute = value
    }

    fun onCategoryChange(value: String) {
        category = value
    }

    fun onMedicationReminderEnabledChange(value: Boolean) {
        medicationReminderEnabled = value
    }

    fun onMedicationReminderIntervalChange(index: Int) {
        medicationReminderIntervalIndex = index.coerceIn(1, 48)
    }

    fun onMedicationTimesPerDayChange(value: Int) {
        medicationTimesPerDay = value.coerceIn(1, 10)
    }

    fun onHasLoggingChange(value: Boolean) {
        hasLogging = value
    }

    fun onTrackBookingChange(value: Boolean) {
        trackBooking = value
    }

    fun onTrackPreviousPeriodChange(value: Boolean) {
        trackPreviousPeriod = value
    }

    fun onIsBookableChange(value: Boolean) {
        isBookable = value
    }

    fun onShowStreakChange(value: Boolean) {
        showStreak = value
    }

    fun onCreateDailyTaskChange(value: Boolean) {
        createDailyTask = value
    }

    fun onNagSuppressionOverrideEnabledChange(value: Boolean) {
        nagSuppressionOverrideEnabled = value
        if (!value) {
            nagSuppressionDaysOverride = -1
            nagSuppressionDisableForHabit = false
        }
    }

    fun onNagSuppressionDisableForHabitChange(value: Boolean) {
        nagSuppressionDisableForHabit = value
        nagSuppressionDaysOverride = if (value) 0 else 7
    }

    fun onNagSuppressionDaysOverrideChange(value: Int) {
        nagSuppressionDaysOverride = value.coerceIn(1, 30)
        nagSuppressionDisableForHabit = false
    }

    fun onTodaySkipAfterCompleteOverrideEnabledChange(value: Boolean) {
        todaySkipAfterCompleteOverrideEnabled = value
        if (value && todaySkipAfterCompleteDays == 0) {
            todaySkipAfterCompleteDays = globalSkipAfterCompleteDays.coerceAtLeast(1)
        }
    }

    fun onTodaySkipAfterCompleteDaysChange(value: Int) {
        todaySkipAfterCompleteDays = value.coerceIn(0, HabitListPreferences.MAX_TODAY_SKIP_DAYS)
    }

    fun onTodaySkipBeforeScheduleOverrideEnabledChange(value: Boolean) {
        todaySkipBeforeScheduleOverrideEnabled = value
        if (value && todaySkipBeforeScheduleDays == 0) {
            todaySkipBeforeScheduleDays = globalSkipBeforeScheduleDays.coerceAtLeast(1)
        }
    }

    fun onTodaySkipBeforeScheduleDaysChange(value: Int) {
        todaySkipBeforeScheduleDays = value.coerceIn(0, HabitListPreferences.MAX_TODAY_SKIP_DAYS)
    }

    suspend fun saveHabit(): Boolean {
        if (name.isBlank()) {
            nameError = true
            return false
        }

        return try {
            val reminderTime = if (reminderEnabled) {
                (reminderHour.toLong() * 60 * 60 * 1000) + (reminderMinute.toLong() * 60 * 1000)
            } else {
                null
            }

            val reminderIntervalMillis = if (medicationReminderEnabled) {
                medicationReminderIntervalIndex.toLong() * 1_800_000L
            } else {
                null
            }

            val timesPerDay = if (medicationReminderEnabled) medicationTimesPerDay else 1

            val activeDaysJson = if (frequencyPeriod == "weekly" && activeDays.isNotEmpty()) {
                "[${activeDays.sorted().joinToString(",")}]"
            } else {
                null
            }

            // Booking/previous period tracking only applies to recurring habits
            val isRecurring = frequencyPeriod != "daily"
            val effectiveTrackBooking = isRecurring && trackBooking
            val effectiveTrackPreviousPeriod = isRecurring && trackPreviousPeriod
            val effectiveIsBookable = isRecurring && isBookable

            val effectiveNagOverride = if (nagSuppressionOverrideEnabled) {
                nagSuppressionDaysOverride
            } else {
                -1
            }

            val effectiveSkipAfterComplete = if (todaySkipAfterCompleteOverrideEnabled) {
                todaySkipAfterCompleteDays.coerceIn(0, HabitListPreferences.MAX_TODAY_SKIP_DAYS)
            } else {
                -1
            }

            val effectiveSkipBeforeSchedule = if (todaySkipBeforeScheduleOverrideEnabled) {
                todaySkipBeforeScheduleDays.coerceIn(0, HabitListPreferences.MAX_TODAY_SKIP_DAYS)
            } else {
                -1
            }

            val existing = existingHabit
            if (existing != null) {
                habitRepository.updateHabit(
                    existing.copy(
                        name = name.trim(),
                        description = description.trim().ifEmpty { null },
                        targetFrequency = targetFrequency,
                        frequencyPeriod = frequencyPeriod,
                        activeDays = activeDaysJson,
                        color = color,
                        icon = icon,
                        reminderTime = reminderTime,
                        reminderIntervalMillis = reminderIntervalMillis,
                        reminderTimesPerDay = timesPerDay,
                        category = category.trim().ifEmpty { null },
                        hasLogging = hasLogging,
                        trackBooking = effectiveTrackBooking,
                        trackPreviousPeriod = effectiveTrackPreviousPeriod,
                        isBookable = effectiveIsBookable,
                        showStreak = showStreak,
                        createDailyTask = createDailyTask,
                        nagSuppressionOverrideEnabled = nagSuppressionOverrideEnabled,
                        nagSuppressionDaysOverride = effectiveNagOverride,
                        todaySkipAfterCompleteDays = effectiveSkipAfterComplete,
                        todaySkipBeforeScheduleDays = effectiveSkipBeforeSchedule
                    )
                )
                // Cancel every alarm previously registered for this habit
                // (covers both interval and daily-time modes), then let
                // rescheduleAll() re-register the modes that are still
                // enabled on the updated entity. Using cancel+reschedule
                // instead of branching on which field changed keeps the
                // two modes decoupled.
                medicationReminderScheduler.cancelAll(existing.id)
                medicationReminderScheduler.rescheduleAll()
            } else {
                habitRepository.addHabit(
                    HabitEntity(
                        name = name.trim(),
                        description = description.trim().ifEmpty { null },
                        targetFrequency = targetFrequency,
                        frequencyPeriod = frequencyPeriod,
                        activeDays = activeDaysJson,
                        color = color,
                        icon = icon,
                        reminderTime = reminderTime,
                        reminderIntervalMillis = reminderIntervalMillis,
                        reminderTimesPerDay = timesPerDay,
                        category = category.trim().ifEmpty { null },
                        hasLogging = hasLogging,
                        trackBooking = effectiveTrackBooking,
                        trackPreviousPeriod = effectiveTrackPreviousPeriod,
                        isBookable = effectiveIsBookable,
                        showStreak = showStreak,
                        createDailyTask = createDailyTask,
                        nagSuppressionOverrideEnabled = nagSuppressionOverrideEnabled,
                        nagSuppressionDaysOverride = effectiveNagOverride,
                        todaySkipAfterCompleteDays = effectiveSkipAfterComplete,
                        todaySkipBeforeScheduleDays = effectiveSkipBeforeSchedule
                    )
                )
                // New habit may have a daily-time reminder that needs an
                // alarm registered right away. Interval-mode habits will
                // schedule on first completion via HabitRepository, so
                // rescheduleAll covers both paths uniformly.
                medicationReminderScheduler.rescheduleAll()
            }
            true
        } catch (e: Exception) {
            Log.e("AddEditHabitVM", "Failed to save habit", e)
            _errorMessages.emit("Couldn't save habit")
            false
        }
    }

    suspend fun deleteHabit() {
        try {
            habitId?.let { habitRepository.deleteHabit(it) }
        } catch (e: Exception) {
            Log.e("AddEditHabitVM", "Failed to delete habit", e)
            _errorMessages.emit("Couldn't delete habit")
        }
    }

    private fun parseActiveDays(json: String?): Set<Int> {
        if (json.isNullOrBlank()) return emptySet()
        return try {
            json
                .trim('[', ']')
                .split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }
}
