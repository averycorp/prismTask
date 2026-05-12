package com.averycorp.prismtask.ui.screens.leisure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.entity.LeisureActivityEntity
import com.averycorp.prismtask.data.preferences.LeisureBudgetPreferences
import com.averycorp.prismtask.data.preferences.LeisureBudgetSnapshot
import com.averycorp.prismtask.data.repository.LeisureBudgetRepository
import com.averycorp.prismtask.domain.model.LeisureCategory
import com.averycorp.prismtask.domain.model.LeisureEnforcementMode
import com.averycorp.prismtask.domain.model.LeisureSessionSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * Leisure Budget v2.0 — Items 1, 2, 5. Backs `LeisurePoolScreen`
 * (pool management + daily target settings) and the
 * `LogPastLeisureSheet` "Log past activity" modal.
 *
 * Free-text activity adds (Q2 lock) go through [addActivity] which
 * inserts a new pool row with the user-chosen category, so subsequent
 * sessions can reference it.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class LeisurePoolViewModel
@Inject
constructor(
    private val repository: LeisureBudgetRepository,
    private val preferences: LeisureBudgetPreferences
) : ViewModel() {

    data class UiState(
        val activities: List<LeisureActivityEntity>,
        val settings: LeisureBudgetSnapshot,
        val activitiesByCategory: Map<LeisureCategory, List<LeisureActivityEntity>>
    ) {
        companion object {
            fun empty(): UiState = UiState(
                activities = emptyList(),
                settings = LeisureBudgetSnapshot(),
                activitiesByCategory = LeisureCategory.values().associateWith { emptyList() }
            )
        }
    }

    val state: StateFlow<UiState> = combine(
        repository.getActivities(),
        preferences.observeSnapshot()
    ) { activities, snap ->
        val grouped: Map<LeisureCategory, List<LeisureActivityEntity>> =
            LeisureCategory.values().associateWith { cat ->
                activities.filter {
                    LeisureCategory.fromStringOrNull(it.category) == cat
                }
            }
        UiState(activities = activities, settings = snap, activitiesByCategory = grouped)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.empty())

    fun addActivity(
        name: String,
        category: LeisureCategory,
        defaultDurationMinutes: Int?
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.upsertActivity(
                LeisureActivityEntity(
                    id = 0,
                    name = name.trim(),
                    category = category.name,
                    defaultDurationMinutes = defaultDurationMinutes,
                    enabled = true,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun renameActivity(activity: LeisureActivityEntity, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.upsertActivity(activity.copy(name = name.trim()))
        }
    }

    fun setActivityCategory(activity: LeisureActivityEntity, category: LeisureCategory) {
        viewModelScope.launch {
            repository.upsertActivity(activity.copy(category = category.name))
        }
    }

    fun setActivityDuration(activity: LeisureActivityEntity, minutes: Int?) {
        viewModelScope.launch {
            repository.upsertActivity(activity.copy(defaultDurationMinutes = minutes))
        }
    }

    fun setActivityEnabled(activity: LeisureActivityEntity, enabled: Boolean) {
        viewModelScope.launch {
            repository.setActivityEnabled(activity.id, enabled)
        }
    }

    fun deleteActivity(activity: LeisureActivityEntity) {
        viewModelScope.launch {
            repository.deleteActivity(activity.id)
        }
    }

    fun setDailyTarget(minutes: Int) {
        viewModelScope.launch {
            preferences.setDailyTargetMinutes(minutes)
        }
    }

    fun setWeekendTarget(minutes: Int?) {
        viewModelScope.launch {
            preferences.setWeekendTargetMinutes(minutes)
        }
    }

    fun setRefreshLimit(limit: Int) {
        viewModelScope.launch {
            preferences.setRefreshLimit(limit)
        }
    }

    fun setEnabledCategories(categories: Set<LeisureCategory>) {
        viewModelScope.launch {
            preferences.setEnabledCategories(categories)
        }
    }

    /**
     * Stage an enforcement-mode change to take effect next local day.
     * SOFT changes apply immediately; MEDIUM/HARD go via the pending
     * promotion path so the user has a chance to undo before they're
     * impacted by escalated notifications.
     */
    fun stageEnforcementMode(mode: LeisureEnforcementMode) {
        viewModelScope.launch {
            if (mode == LeisureEnforcementMode.SOFT) {
                preferences.setEnforcementMode(mode)
            } else {
                preferences.setPendingEnforcementMode(
                    mode = mode,
                    effectiveDate = LocalDate.now().plusDays(1)
                )
            }
        }
    }

    /**
     * Q2 lock: free-text manual entry auto-adds to the pool. If the
     * user picks an existing activity, [activityId] is set and no add
     * happens.
     */
    fun logManualSession(
        activityId: Long?,
        freeTextName: String?,
        category: LeisureCategory,
        durationMinutes: Int,
        loggedAt: Long = System.currentTimeMillis()
    ) {
        viewModelScope.launch {
            val resolvedActivityId = if (activityId != null) {
                activityId
            } else if (!freeTextName.isNullOrBlank()) {
                val newId = repository.upsertActivity(
                    LeisureActivityEntity(
                        id = 0,
                        name = freeTextName.trim(),
                        category = category.name,
                        defaultDurationMinutes = durationMinutes,
                        enabled = true,
                        createdAt = System.currentTimeMillis()
                    )
                )
                newId
            } else {
                null
            }
            repository.logSession(
                activityId = resolvedActivityId,
                category = category,
                durationMinutes = durationMinutes,
                loggedAt = loggedAt,
                source = LeisureSessionSource.MANUAL
            )
        }
    }
}
