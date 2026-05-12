package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.averycorp.prismtask.domain.model.LeisureCategory
import com.averycorp.prismtask.domain.model.LeisureEnforcementMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Leisure Budget v2.0 settings DataStore.
 *
 * Replaces the v1.x [LeisurePreferences] entirely. The v1.x slot model
 * (MUSIC / FLEX / LANGUAGE + custom sections) had ~700 LOC of state for
 * per-slot configuration. v2.0 collapses that to a single user-owned
 * pool table ([com.averycorp.prismtask.data.local.entity.LeisureActivityEntity])
 * plus this DataStore for the small set of cross-cutting settings.
 *
 * Mirror server table: `leisure_settings`. The sync layer round-trips
 * the entire snapshot rather than per-key writes so the server-side
 * `pending_enforcement_mode` / `pending_enforcement_effective_date`
 * deferred-promotion pattern stays atomic.
 *
 * Refresh counter ([readRefreshesConsumed] / [incrementRefreshesConsumed])
 * is keyed by the user's *local* date (per
 * [com.averycorp.prismtask.util.DayBoundary]) so the cap rolls over at
 * SoD, not midnight UTC.
 */
data class LeisureBudgetSnapshot(
    val dailyTargetMinutes: Int = 60,
    val weekendTargetMinutes: Int? = null,
    val enforcementMode: LeisureEnforcementMode = LeisureEnforcementMode.SOFT,
    val refreshLimit: Int = 3,
    val enabledCategories: Set<LeisureCategory> = LeisureCategory.DEFAULT_ENABLED,
    val pendingEnforcementMode: LeisureEnforcementMode? = null,
    val pendingEnforcementEffectiveDate: LocalDate? = null
) {
    /** Returns the target appropriate for [localDate]. */
    fun targetForDate(localDate: LocalDate): Int {
        val isWeekend = when (localDate.dayOfWeek) {
            java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY -> true
            else -> false
        }
        return if (isWeekend && weekendTargetMinutes != null) {
            weekendTargetMinutes
        } else {
            dailyTargetMinutes
        }
    }
}

internal val Context.leisureBudgetDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "leisure_budget_prefs")

@Singleton
class LeisureBudgetPreferences
@Inject
constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val MIN_TARGET = 0
        const val MAX_TARGET = 1440
        const val MIN_REFRESH = 0
        const val MAX_REFRESH = 10
        const val DEFAULT_TARGET = 60
        const val DEFAULT_REFRESH_LIMIT = 3

        private val DAILY_TARGET_KEY = intPreferencesKey("leisure_daily_target_minutes")
        private val WEEKEND_TARGET_KEY = intPreferencesKey("leisure_weekend_target_minutes")
        private val WEEKEND_OVERRIDE_KEY = booleanPreferencesKey("leisure_weekend_override_enabled")
        private val ENFORCEMENT_KEY = stringPreferencesKey("leisure_enforcement_mode")
        private val REFRESH_LIMIT_KEY = intPreferencesKey("leisure_refresh_limit")
        private val ENABLED_CATEGORIES_KEY = stringPreferencesKey("leisure_enabled_categories")
        private val PENDING_ENFORCEMENT_KEY = stringPreferencesKey("leisure_pending_enforcement_mode")
        private val PENDING_EFFECTIVE_DATE_KEY = stringPreferencesKey("leisure_pending_effective_date")

        // Refresh-counter keys are minted per local-date so the cap
        // rolls over with the user's SoD boundary, not UTC midnight.
        private fun refreshCounterKey(localDate: String) =
            intPreferencesKey("leisure_refreshes_consumed_$localDate")
    }

    fun observeSnapshot(): Flow<LeisureBudgetSnapshot> =
        context.leisureBudgetDataStore.data.map { prefs -> readSnapshot(prefs) }

    suspend fun snapshotOnce(): LeisureBudgetSnapshot {
        var snap: LeisureBudgetSnapshot = LeisureBudgetSnapshot()
        context.leisureBudgetDataStore.data.collect {
            snap = readSnapshot(it)
            return@collect
        }
        return snap
    }

    private fun readSnapshot(prefs: Preferences): LeisureBudgetSnapshot {
        val rawEnforcement = prefs[ENFORCEMENT_KEY]
        val enforcement = LeisureEnforcementMode.values()
            .firstOrNull { it.name == rawEnforcement }
            ?: LeisureEnforcementMode.SOFT
        val weekendOverride = prefs[WEEKEND_OVERRIDE_KEY] ?: false
        val weekendTarget = if (weekendOverride) prefs[WEEKEND_TARGET_KEY] else null
        val pendingEnforcement = prefs[PENDING_ENFORCEMENT_KEY]?.let { raw ->
            LeisureEnforcementMode.values().firstOrNull { it.name == raw }
        }
        val pendingDate = prefs[PENDING_EFFECTIVE_DATE_KEY]?.let { raw ->
            runCatching { LocalDate.parse(raw) }.getOrNull()
        }
        val enabledCategories: Set<LeisureCategory> =
            prefs[ENABLED_CATEGORIES_KEY]
                ?.split(",")
                ?.mapNotNull { LeisureCategory.fromStringOrNull(it.trim()) }
                ?.toSet()
                ?.takeIf { it.isNotEmpty() }
                ?: LeisureCategory.DEFAULT_ENABLED
        return LeisureBudgetSnapshot(
            dailyTargetMinutes = (prefs[DAILY_TARGET_KEY] ?: DEFAULT_TARGET)
                .coerceIn(MIN_TARGET, MAX_TARGET),
            weekendTargetMinutes = weekendTarget?.coerceIn(MIN_TARGET, MAX_TARGET),
            enforcementMode = enforcement,
            refreshLimit = (prefs[REFRESH_LIMIT_KEY] ?: DEFAULT_REFRESH_LIMIT)
                .coerceIn(MIN_REFRESH, MAX_REFRESH),
            enabledCategories = enabledCategories,
            pendingEnforcementMode = pendingEnforcement,
            pendingEnforcementEffectiveDate = pendingDate
        )
    }

    suspend fun setDailyTargetMinutes(minutes: Int) {
        context.leisureBudgetDataStore.edit { prefs ->
            prefs[DAILY_TARGET_KEY] = minutes.coerceIn(MIN_TARGET, MAX_TARGET)
        }
    }

    suspend fun setWeekendTargetMinutes(minutes: Int?) {
        context.leisureBudgetDataStore.edit { prefs ->
            if (minutes == null) {
                prefs[WEEKEND_OVERRIDE_KEY] = false
                prefs.remove(WEEKEND_TARGET_KEY)
            } else {
                prefs[WEEKEND_OVERRIDE_KEY] = true
                prefs[WEEKEND_TARGET_KEY] = minutes.coerceIn(MIN_TARGET, MAX_TARGET)
            }
        }
    }

    suspend fun setRefreshLimit(limit: Int) {
        context.leisureBudgetDataStore.edit { prefs ->
            prefs[REFRESH_LIMIT_KEY] = limit.coerceIn(MIN_REFRESH, MAX_REFRESH)
        }
    }

    suspend fun setEnabledCategories(categories: Set<LeisureCategory>) {
        if (categories.isEmpty()) return // never let the pool dead-state
        context.leisureBudgetDataStore.edit { prefs ->
            prefs[ENABLED_CATEGORIES_KEY] = categories.joinToString(",") { it.name }
        }
    }

    /**
     * Stage an enforcement-mode change to take effect on [effectiveDate].
     * Mirrors the server-side deferred-promotion pattern: writes go to
     * `pending_*` columns and the daily-reset worker promotes them on
     * the SoD boundary by calling [promotePendingEnforcement].
     */
    suspend fun setPendingEnforcementMode(
        mode: LeisureEnforcementMode,
        effectiveDate: LocalDate
    ) {
        context.leisureBudgetDataStore.edit { prefs ->
            prefs[PENDING_ENFORCEMENT_KEY] = mode.name
            prefs[PENDING_EFFECTIVE_DATE_KEY] = effectiveDate.toString()
        }
    }

    /** Apply a pending enforcement change if the effective date has arrived. */
    suspend fun promotePendingEnforcement(today: LocalDate): Boolean {
        var promoted = false
        context.leisureBudgetDataStore.edit { prefs ->
            val pending = prefs[PENDING_ENFORCEMENT_KEY] ?: return@edit
            val date = prefs[PENDING_EFFECTIVE_DATE_KEY]?.let { raw ->
                runCatching { LocalDate.parse(raw) }.getOrNull()
            } ?: return@edit
            if (!date.isAfter(today)) {
                prefs[ENFORCEMENT_KEY] = pending
                prefs.remove(PENDING_ENFORCEMENT_KEY)
                prefs.remove(PENDING_EFFECTIVE_DATE_KEY)
                promoted = true
            }
        }
        return promoted
    }

    fun observeRefreshesConsumed(localDate: String): Flow<Int> =
        context.leisureBudgetDataStore.data.map { prefs ->
            prefs[refreshCounterKey(localDate)] ?: 0
        }

    suspend fun readRefreshesConsumed(localDate: String): Int =
        snapshotConsumed(localDate)

    private suspend fun snapshotConsumed(localDate: String): Int {
        var consumed = 0
        context.leisureBudgetDataStore.data.collect {
            consumed = it[refreshCounterKey(localDate)] ?: 0
            return@collect
        }
        return consumed
    }

    /** Returns the new count post-increment. */
    suspend fun incrementRefreshesConsumed(localDate: String): Int {
        var next = 0
        context.leisureBudgetDataStore.edit { prefs ->
            val current = prefs[refreshCounterKey(localDate)] ?: 0
            next = current + 1
            prefs[refreshCounterKey(localDate)] = next
        }
        return next
    }

    /**
     * Direct setter (used by the SyncListener when the server returns
     * an updated enforcement-mode that bypassed the deferred pattern
     * — e.g. the per-day worker promoted it elsewhere first).
     */
    suspend fun setEnforcementMode(mode: LeisureEnforcementMode) {
        context.leisureBudgetDataStore.edit { prefs ->
            prefs[ENFORCEMENT_KEY] = mode.name
            prefs.remove(PENDING_ENFORCEMENT_KEY)
            prefs.remove(PENDING_EFFECTIVE_DATE_KEY)
        }
    }
}
