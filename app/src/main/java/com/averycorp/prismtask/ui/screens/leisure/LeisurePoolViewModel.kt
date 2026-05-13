package com.averycorp.prismtask.ui.screens.leisure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.entity.LeisureActivityEntity
import com.averycorp.prismtask.data.local.entity.LeisureSessionEntity
import com.averycorp.prismtask.data.preferences.LeisureBudgetPreferences
import com.averycorp.prismtask.data.preferences.LeisureBudgetSnapshot
import com.averycorp.prismtask.data.preferences.LeisureCategoryDisplay
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.repository.LeisureBudgetRepository
import com.averycorp.prismtask.domain.model.CustomLeisureCategory
import com.averycorp.prismtask.domain.model.LeisureCategory
import com.averycorp.prismtask.domain.model.LeisureCategoryRef
import com.averycorp.prismtask.domain.model.LeisureEnforcementMode
import com.averycorp.prismtask.domain.model.LeisureSessionSource
import com.averycorp.prismtask.util.DayBoundary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    private val preferences: LeisureBudgetPreferences,
    private val taskBehaviorPreferences: TaskBehaviorPreferences
) : ViewModel() {

    data class UiState(
        val activities: List<LeisureActivityEntity>,
        val settings: LeisureBudgetSnapshot,
        val visibleCategoryRefs: List<LeisureCategoryRef>,
        val activitiesByCategoryId: Map<String, List<LeisureActivityEntity>>,
        val recentSessions: List<LeisureSessionEntity>,
        val activitiesById: Map<Long, LeisureActivityEntity>,
        val minutesLoggedToday: Int,
        val targetMinutesToday: Int,
        val minutesByCategoryIdToday: Map<String, Int>,
        val categoryDisplays: Map<LeisureCategory, LeisureCategoryDisplay>
    ) {
        /** Lookup by category id; falls back to a synthetic built-in default. */
        fun refForId(id: String?): LeisureCategoryRef? {
            if (id.isNullOrBlank()) return null
            visibleCategoryRefs.firstOrNull { it.id == id }?.let { return it }
            // Built-in not in the visible list (e.g. disabled but still on
            // an activity): synthesize one off the display map.
            val builtIn = LeisureCategory.fromStringOrNull(id) ?: return null
            val display = categoryDisplays[builtIn]
                ?: LeisureCategoryDisplay(emoji = builtIn.emoji, label = builtIn.label)
            return LeisureCategoryRef.BuiltIn(builtIn, display.label, display.emoji)
        }

        fun displayFor(category: LeisureCategory): LeisureCategoryDisplay =
            categoryDisplays[category]
                ?: LeisureCategoryDisplay(emoji = category.emoji, label = category.label)

        companion object {
            fun empty(): UiState = UiState(
                activities = emptyList(),
                settings = LeisureBudgetSnapshot(),
                visibleCategoryRefs = emptyList(),
                activitiesByCategoryId = emptyMap(),
                recentSessions = emptyList(),
                activitiesById = emptyMap(),
                minutesLoggedToday = 0,
                targetMinutesToday = LeisureBudgetPreferences.DEFAULT_TARGET,
                minutesByCategoryIdToday = emptyMap(),
                categoryDisplays = LeisureCategory.values().associateWith {
                    LeisureCategoryDisplay(emoji = it.emoji, label = it.label)
                }
            )
        }
    }

    private val settingsFlow = combine(
        preferences.observeSnapshot(),
        preferences.observeCategoryDisplays()
    ) { snap, displays -> snap to displays }

    val state: StateFlow<UiState> = combine(
        repository.getActivities(),
        settingsFlow,
        repository.observeRecentSessions(limit = 200),
        repository.observeMinutesLoggedToday(),
        taskBehaviorPreferences.getDayStartHour()
    ) { activities, (snap, displays), sessions, minutes, dayStartHour ->
        val today = DayBoundary.currentLocalDate(dayStartHour)
        val startOfDay = DayBoundary.startOfCurrentDay(dayStartHour)
        val endOfDay = startOfDay + DayBoundary.DAY_MILLIS

        val builtInRefs: List<LeisureCategoryRef> = LeisureCategory.values()
            .filter { it in snap.enabledCategories }
            .map { cat ->
                val display = displays[cat] ?: LeisureCategoryDisplay(cat.emoji, cat.label)
                LeisureCategoryRef.BuiltIn(cat, display.label, display.emoji)
            }
        val customRefs: List<LeisureCategoryRef> = snap.customCategories
            .map { LeisureCategoryRef.Custom(it) }
        val visibleRefs = builtInRefs + customRefs

        val activitiesByCategoryId: Map<String, List<LeisureActivityEntity>> =
            activities.groupBy { it.category }
        val minutesByCategoryIdToday: Map<String, Int> = sessions
            .filter { it.loggedAt in startOfDay until endOfDay }
            .groupBy { it.category }
            .mapValues { (_, list) -> list.sumOf { it.durationMinutes } }

        UiState(
            activities = activities,
            settings = snap,
            visibleCategoryRefs = visibleRefs,
            activitiesByCategoryId = activitiesByCategoryId,
            recentSessions = sessions,
            activitiesById = activities.associateBy { it.id },
            minutesLoggedToday = minutes,
            targetMinutesToday = snap.targetForDate(today),
            minutesByCategoryIdToday = minutesByCategoryIdToday,
            categoryDisplays = displays
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.empty())

    fun addActivity(
        name: String,
        category: LeisureCategory,
        defaultDurationMinutes: Int?
    ) = addActivityByCategoryId(name, category.name, defaultDurationMinutes)

    /** Accepts a built-in enum name or a custom category id. */
    fun addActivityByCategoryId(
        name: String,
        categoryId: String,
        defaultDurationMinutes: Int?
    ) {
        if (name.isBlank() || categoryId.isBlank()) return
        viewModelScope.launch {
            repository.upsertActivity(
                LeisureActivityEntity(
                    id = 0,
                    name = name.trim(),
                    category = categoryId,
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

    fun updateActivity(
        activity: LeisureActivityEntity,
        name: String,
        category: LeisureCategory,
        defaultDurationMinutes: Int?
    ) = updateActivityByCategoryId(activity, name, category.name, defaultDurationMinutes)

    /** Accepts a built-in enum name or a custom category id. */
    fun updateActivityByCategoryId(
        activity: LeisureActivityEntity,
        name: String,
        categoryId: String,
        defaultDurationMinutes: Int?
    ) {
        if (name.isBlank() || categoryId.isBlank()) return
        viewModelScope.launch {
            repository.upsertActivity(
                activity.copy(
                    name = name.trim(),
                    category = categoryId,
                    defaultDurationMinutes = defaultDurationMinutes
                )
            )
        }
    }

    /**
     * Quick-log a category-only session — no activity attached. Backs
     * the category cards on `LeisurePoolScreen`: tapping a category +
     * choosing a duration drops a session with `activityId = null`,
     * which is fine because the session row denormalizes `category` at
     * insertion time.
     */
    fun logCategorySession(category: LeisureCategory, durationMinutes: Int) =
        logCategorySessionById(category.name, durationMinutes)

    /** Accepts a built-in enum name or a custom category id. */
    fun logCategorySessionById(categoryId: String, durationMinutes: Int) {
        val minutes = durationMinutes.coerceAtLeast(1)
        viewModelScope.launch {
            repository.logSessionByCategoryId(
                activityId = null,
                categoryId = categoryId,
                durationMinutes = minutes,
                source = LeisureSessionSource.MANUAL
            )
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
        }
    }

    fun updateSessionTime(sessionId: Long, newLoggedAt: Long) {
        viewModelScope.launch {
            repository.updateSessionTime(sessionId, newLoggedAt)
        }
    }

    /**
     * Mark an activity done as part of today's leisure budget. Logs a
     * session with [durationMinutes] (or the activity's default when
     * null, falling back to 30 minutes), counted toward today's target.
     */
    fun checkOffActivity(
        activity: LeisureActivityEntity,
        durationMinutes: Int? = null
    ) {
        val resolvedDuration = (durationMinutes ?: activity.defaultDurationMinutes ?: 30)
            .coerceAtLeast(1)
        viewModelScope.launch {
            repository.logSessionByCategoryId(
                activityId = activity.id,
                categoryId = activity.category,
                durationMinutes = resolvedDuration,
                source = LeisureSessionSource.MANUAL
            )
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

    fun setEnabledCategories(categories: Set<LeisureCategory>) {
        viewModelScope.launch {
            preferences.setEnabledCategories(categories)
        }
    }

    fun setCategoryEnabled(category: LeisureCategory, enabled: Boolean) {
        viewModelScope.launch {
            val current = preferences.snapshotOnce().enabledCategories
            val next = if (enabled) current + category else current - category
            preferences.setEnabledCategories(next)
        }
    }

    fun setCategoryDisplay(category: LeisureCategory, label: String, emoji: String) {
        viewModelScope.launch {
            preferences.setCategoryDisplay(category, label, emoji)
        }
    }

    fun resetCategoryDisplay(category: LeisureCategory) {
        viewModelScope.launch {
            preferences.resetCategoryDisplay(category)
        }
    }

    /**
     * Add a brand-new user-defined leisure category. Stored locally only
     * for now — the server's `leisure_activities.category` CHECK
     * constraint still pins synced activity rows to the four built-in
     * buckets, so activities/sessions tagged with a custom category stay
     * on-device until a server-side schema change relaxes that.
     */
    fun addCustomCategory(label: String, emoji: String) {
        val trimmedLabel = label.trim()
        val trimmedEmoji = emoji.trim()
        if (trimmedLabel.isBlank() || trimmedEmoji.isBlank()) return
        viewModelScope.launch {
            preferences.upsertCustomCategory(
                CustomLeisureCategory(
                    id = CustomLeisureCategory.newId(),
                    label = trimmedLabel,
                    emoji = trimmedEmoji
                )
            )
        }
    }

    /** Rename / re-emoji an existing custom category in place. */
    fun updateCustomCategory(id: String, label: String, emoji: String) {
        val trimmedLabel = label.trim()
        val trimmedEmoji = emoji.trim()
        if (trimmedLabel.isBlank() || trimmedEmoji.isBlank()) return
        if (!CustomLeisureCategory.isCustomId(id)) return
        viewModelScope.launch {
            preferences.upsertCustomCategory(
                CustomLeisureCategory(id = id, label = trimmedLabel, emoji = trimmedEmoji)
            )
        }
    }

    /** Remove a custom category. Activities tagged with it keep their
     * row (so historical sessions stay attributed correctly) but the
     * category stops appearing in pickers. */
    fun removeCustomCategory(id: String) {
        if (!CustomLeisureCategory.isCustomId(id)) return
        viewModelScope.launch {
            preferences.removeCustomCategory(id)
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
    ) = logManualSessionByCategoryId(
        activityId = activityId,
        freeTextName = freeTextName,
        categoryId = category.name,
        durationMinutes = durationMinutes,
        loggedAt = loggedAt
    )

    /** Accepts a built-in enum name or a custom category id. */
    fun logManualSessionByCategoryId(
        activityId: Long?,
        freeTextName: String?,
        categoryId: String,
        durationMinutes: Int,
        loggedAt: Long = System.currentTimeMillis()
    ) {
        if (categoryId.isBlank()) return
        viewModelScope.launch {
            val resolvedActivityId = if (activityId != null) {
                activityId
            } else if (!freeTextName.isNullOrBlank()) {
                val newId = repository.upsertActivity(
                    LeisureActivityEntity(
                        id = 0,
                        name = freeTextName.trim(),
                        category = categoryId,
                        defaultDurationMinutes = durationMinutes,
                        enabled = true,
                        createdAt = System.currentTimeMillis()
                    )
                )
                newId
            } else {
                null
            }
            repository.logSessionByCategoryId(
                activityId = resolvedActivityId,
                categoryId = categoryId,
                durationMinutes = durationMinutes,
                loggedAt = loggedAt,
                source = LeisureSessionSource.MANUAL
            )
        }
    }
}
