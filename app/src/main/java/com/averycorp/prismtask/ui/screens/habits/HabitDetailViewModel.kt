package com.averycorp.prismtask.ui.screens.habits

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.HabitLogEntity
import com.averycorp.prismtask.data.repository.HabitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HabitDetailStats(
    val totalCount: Int = 0,
    val averageIntervalDays: Int? = null,
    val lastDoneDate: Long? = null,
    val nextSuggestedDate: Long? = null
)

@HiltViewModel
class HabitDetailViewModel
@Inject
constructor(private val habitRepository: HabitRepository, savedStateHandle: SavedStateHandle) :
    ViewModel() {
    private val habitId: Long = savedStateHandle.get<Long>("habitId") ?: -1L

    val habit: StateFlow<HabitEntity?> = habitRepository
        .getHabitById(habitId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val logs: StateFlow<List<HabitLogEntity>> = habitRepository
        .getLogsForHabit(habitId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val stats: StateFlow<HabitDetailStats> = logs
        .map { logList ->
            val totalCount = logList.size
            val sortedDates = logList.map { it.date }.sorted()
            val averageInterval = if (sortedDates.size >= 2) {
                val intervals = sortedDates.zipWithNext { a, b -> b - a }
                val avgMillis = intervals.average()
                (avgMillis / (24 * 60 * 60 * 1000)).toInt().coerceAtLeast(1)
            } else {
                null
            }

            val lastDone = sortedDates.lastOrNull()
            val nextSuggested = if (lastDone != null && averageInterval != null) {
                lastDone + averageInterval.toLong() * 24 * 60 * 60 * 1000
            } else {
                null
            }

            HabitDetailStats(
                totalCount = totalCount,
                averageIntervalDays = averageInterval,
                lastDoneDate = lastDone,
                nextSuggestedDate = nextSuggested
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HabitDetailStats())

    fun onLogActivity(date: Long, notes: String?) {
        viewModelScope.launch {
            try {
                habitRepository.logActivity(habitId, date, notes)
            } catch (e: Exception) {
                Log.e("HabitDetailVM", "Failed to log activity", e)
            }
        }
    }

    fun onSetBooked(isBooked: Boolean, bookedDate: Long?, bookedNote: String?) {
        viewModelScope.launch {
            try {
                habitRepository.setBooked(habitId, isBooked, bookedDate, bookedNote)
            } catch (e: Exception) {
                Log.e("HabitDetailVM", "Failed to set booking", e)
            }
        }
    }

    fun onDeleteLog(log: HabitLogEntity) {
        viewModelScope.launch {
            try {
                habitRepository.deleteLog(log)
            } catch (e: Exception) {
                Log.e("HabitDetailVM", "Failed to delete log", e)
            }
        }
    }
}
