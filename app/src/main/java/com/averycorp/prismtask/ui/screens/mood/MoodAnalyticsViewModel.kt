package com.averycorp.prismtask.ui.screens.mood

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.entity.MoodEnergyLogEntity
import com.averycorp.prismtask.data.repository.MoodEnergyRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.domain.model.LifeCategory
import com.averycorp.prismtask.domain.usecase.CorrelationResult
import com.averycorp.prismtask.domain.usecase.DailyObservation
import com.averycorp.prismtask.domain.usecase.MoodCorrelationEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Mood & Energy analytics screen (v1.4.0 V7).
 *
 * Pulls the last 30 days of mood/energy logs, stitches them together
 * with the user's task completion stats, feeds the result into
 * [MoodCorrelationEngine], and exposes the raw trend data + top
 * correlation insights to the UI.
 */
@HiltViewModel
class MoodAnalyticsViewModel
@Inject
constructor(
    private val moodEnergyRepository: MoodEnergyRepository,
    private val taskRepository: TaskRepository
) : ViewModel() {
    private val engine = MoodCorrelationEngine()

    private val _state = MutableStateFlow(MoodAnalyticsState())
    val state: StateFlow<MoodAnalyticsState> = _state.asStateFlow()

    init {
        // Observe the repository so newly-logged entries (Morning
        // Check-In, Today Energy Check-In, post-Pomodoro prompt) refresh
        // this screen automatically — previously refresh() only fired
        // from init{} and a return-to-screen showed stale data.
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val windowStart = now - 30L * 24 * 60 * 60 * 1000
            moodEnergyRepository.observeRange(windowStart, now).collectLatest { logs ->
                recompute(logs)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val windowStart = now - 30L * 24 * 60 * 60 * 1000
            recompute(moodEnergyRepository.getRange(windowStart, now))
        }
    }

    private suspend fun recompute(logs: List<MoodEnergyLogEntity>) {
        val tasks = taskRepository.getAllTasksOnce()
        val observations = buildObservations(logs, tasks)
        _state.value = MoodAnalyticsState(
            logs = logs,
            observations = observations,
            moodResults = engine.correlateMood(observations),
            energyResults = engine.correlateEnergy(observations),
            averageByDay = engine.averageByDay(logs)
        )
    }

    private fun buildObservations(
        logs: List<MoodEnergyLogEntity>,
        tasks: List<com.averycorp.prismtask.data.local.entity.TaskEntity>
    ): List<DailyObservation> {
        val byDay = engine.averageByDay(logs)
        return byDay.entries
            .sortedBy { it.key }
            .map { (date, avg) ->
                val dayEnd = date + 24L * 60 * 60 * 1000
                val completedOnDay = tasks.filter { t ->
                    t.isCompleted &&
                        t.completedAt != null &&
                        t.completedAt in date until dayEnd
                }
                DailyObservation(
                    date = date,
                    mood = avg.first.toInt(),
                    energy = avg.second.toInt(),
                    tasksCompleted = completedOnDay.size,
                    workTasksCompleted = completedOnDay.count {
                        LifeCategory.fromStorage(it.lifeCategory) == LifeCategory.WORK
                    },
                    selfCareTasksCompleted = completedOnDay.count {
                        LifeCategory.fromStorage(it.lifeCategory) == LifeCategory.SELF_CARE
                    }
                )
            }
    }
}

data class MoodAnalyticsState(
    val logs: List<MoodEnergyLogEntity> = emptyList(),
    val observations: List<DailyObservation> = emptyList(),
    val moodResults: List<CorrelationResult> = emptyList(),
    val energyResults: List<CorrelationResult> = emptyList(),
    val averageByDay: Map<Long, Pair<Float, Float>> = emptyMap()
)
