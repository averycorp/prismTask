package com.averycorp.prismtask.ui.screens.reflection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.preferences.ReflectionEntry
import com.averycorp.prismtask.data.preferences.ReflectionPreferences
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.util.DayBoundary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * F4 Item 1 — End-of-day Reflection ViewModel.
 *
 * Pulls today's logical date (SoD-aware) once at start, exposes the
 * existing reflection entry for that date (if any) and the count of
 * completed tasks today as positive framing for the prompt. NO list of
 * unfinished items, NO percentage, NO judgment language per Principle 1.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReflectionViewModel
@Inject
constructor(
    private val reflectionPreferences: ReflectionPreferences,
    private val taskRepository: TaskRepository,
    private val taskBehaviorPreferences: TaskBehaviorPreferences
) : ViewModel() {

    val today: StateFlow<LocalDate> = taskBehaviorPreferences.getStartOfDay()
        .map { sod ->
            LocalDate.parse(
                DayBoundary.currentLocalDateString(
                    dayStartHour = sod.hour,
                    now = System.currentTimeMillis(),
                    dayStartMinute = sod.minute
                )
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LocalDate.now())

    val currentEntry: StateFlow<ReflectionEntry?> = today
        .flatMapLatest { reflectionPreferences.observeEntryFor(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val completedTodayCount: StateFlow<Int> = taskBehaviorPreferences.getStartOfDay()
        .flatMapLatest { sod ->
            val isoToday = DayBoundary.currentLocalDateString(
                dayStartHour = sod.hour,
                now = System.currentTimeMillis(),
                dayStartMinute = sod.minute
            )
            val startMs = LocalDate.parse(isoToday)
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli()
            taskRepository.getCompletedToday(startMs).map { it.size }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun save(text: String) {
        viewModelScope.launch {
            val date = today.first()
            reflectionPreferences.upsert(ReflectionEntry(date = date, text = text))
        }
    }

    fun clearToday() {
        viewModelScope.launch {
            val date = today.first()
            reflectionPreferences.delete(date)
        }
    }
}
