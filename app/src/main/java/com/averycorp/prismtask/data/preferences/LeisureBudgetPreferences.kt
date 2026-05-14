package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.averycorp.prismtask.domain.model.CustomLeisureCategory
import com.averycorp.prismtask.domain.model.LeisureCategory
import com.averycorp.prismtask.domain.model.LeisureEnforcementMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
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
 */
/**
 * User-overridable display values for a [LeisureCategory]. Stored in
 * DataStore so users can rename / re-emoji the four buckets without
 * touching the underlying enum (which the database, sync layer, and
 * NLP parser still pin on the canonical [LeisureCategory.name]).
 */
data class LeisureCategoryDisplay(val emoji: String, val label: String)

data class LeisureBudgetSnapshot(
    val dailyTargetMinutes: Int = 60,
    val weekendTargetMinutes: Int? = null,
    val enforcementMode: LeisureEnforcementMode = LeisureEnforcementMode.SOFT,
    val enabledCategories: Set<LeisureCategory> = LeisureCategory.DEFAULT_ENABLED,
    val customCategories: List<CustomLeisureCategory> = emptyList(),
    val pendingEnforcementMode: LeisureEnforcementMode? = null,
    val pendingEnforcementEffectiveDate: LocalDate? = null
) {
    /** Returns the minimum minutes target appropriate for [localDate]. */
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
        const val DEFAULT_TARGET = 60

        // Sentinel for an explicitly-empty enabled-categories set. The
        // DataStore key being absent means "never set, default to all
        // built-ins"; this marker says "the user removed every built-in
        // on purpose" so the read path doesn't snap them back.
        private const val EXPLICIT_NONE_SENTINEL = "__none__"

        private val DAILY_TARGET_KEY = intPreferencesKey("leisure_daily_target_minutes")
        private val WEEKEND_TARGET_KEY = intPreferencesKey("leisure_weekend_target_minutes")
        private val WEEKEND_OVERRIDE_KEY = booleanPreferencesKey("leisure_weekend_override_enabled")
        private val ENFORCEMENT_KEY = stringPreferencesKey("leisure_enforcement_mode")
        private val ENABLED_CATEGORIES_KEY = stringPreferencesKey("leisure_enabled_categories")
        private val CUSTOM_CATEGORIES_KEY = stringPreferencesKey("leisure_custom_categories")
        private val PENDING_ENFORCEMENT_KEY = stringPreferencesKey("leisure_pending_enforcement_mode")
        private val PENDING_EFFECTIVE_DATE_KEY = stringPreferencesKey("leisure_pending_effective_date")

        private fun categoryLabelKey(category: LeisureCategory) =
            stringPreferencesKey("leisure_category_label_${category.name}")

        private fun categoryEmojiKey(category: LeisureCategory) =
            stringPreferencesKey("leisure_category_emoji_${category.name}")
    }

    fun observeSnapshot(): Flow<LeisureBudgetSnapshot> =
        context.leisureBudgetDataStore.data.map { prefs -> readSnapshot(prefs) }

    suspend fun snapshotOnce(): LeisureBudgetSnapshot =
        readSnapshot(context.leisureBudgetDataStore.data.first())

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
        val rawEnabled = prefs[ENABLED_CATEGORIES_KEY]
        val enabledCategories: Set<LeisureCategory> = when {
            rawEnabled == null -> LeisureCategory.DEFAULT_ENABLED
            rawEnabled == EXPLICIT_NONE_SENTINEL -> emptySet()
            else ->
                rawEnabled
                    .split(",")
                    .mapNotNull { LeisureCategory.fromStringOrNull(it.trim()) }
                    .toSet()
        }
        val customCategories: List<CustomLeisureCategory> =
            decodeCustomCategories(prefs[CUSTOM_CATEGORIES_KEY])
        return LeisureBudgetSnapshot(
            dailyTargetMinutes = (prefs[DAILY_TARGET_KEY] ?: DEFAULT_TARGET)
                .coerceIn(MIN_TARGET, MAX_TARGET),
            weekendTargetMinutes = weekendTarget?.coerceIn(MIN_TARGET, MAX_TARGET),
            enforcementMode = enforcement,
            enabledCategories = enabledCategories,
            customCategories = customCategories,
            pendingEnforcementMode = pendingEnforcement,
            pendingEnforcementEffectiveDate = pendingDate
        )
    }

    private fun decodeCustomCategories(raw: String?): List<CustomLeisureCategory> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val label = obj.optString("label").takeIf { it.isNotBlank() } ?: continue
                    val emoji = obj.optString("emoji").takeIf { it.isNotBlank() } ?: continue
                    add(CustomLeisureCategory(id = id, label = label, emoji = emoji))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeCustomCategories(categories: List<CustomLeisureCategory>): String {
        val arr = JSONArray()
        categories.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("label", c.label)
                    .put("emoji", c.emoji)
            )
        }
        return arr.toString()
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

    suspend fun setEnabledCategories(categories: Set<LeisureCategory>) {
        context.leisureBudgetDataStore.edit { prefs ->
            prefs[ENABLED_CATEGORIES_KEY] = if (categories.isEmpty()) {
                EXPLICIT_NONE_SENTINEL
            } else {
                categories.joinToString(",") { it.name }
            }
        }
    }

    fun observeCategoryDisplays(): Flow<Map<LeisureCategory, LeisureCategoryDisplay>> =
        context.leisureBudgetDataStore.data.map { prefs ->
            LeisureCategory.values().associateWith { cat ->
                LeisureCategoryDisplay(
                    emoji = prefs[categoryEmojiKey(cat)]?.takeIf { it.isNotBlank() } ?: cat.emoji,
                    label = prefs[categoryLabelKey(cat)]?.takeIf { it.isNotBlank() } ?: cat.label
                )
            }
        }

    suspend fun setCategoryDisplay(
        category: LeisureCategory,
        label: String,
        emoji: String
    ) {
        val trimmedLabel = label.trim()
        val trimmedEmoji = emoji.trim()
        context.leisureBudgetDataStore.edit { prefs ->
            if (trimmedLabel.isBlank() || trimmedLabel == category.label) {
                prefs.remove(categoryLabelKey(category))
            } else {
                prefs[categoryLabelKey(category)] = trimmedLabel
            }
            if (trimmedEmoji.isBlank() || trimmedEmoji == category.emoji) {
                prefs.remove(categoryEmojiKey(category))
            } else {
                prefs[categoryEmojiKey(category)] = trimmedEmoji
            }
        }
    }

    suspend fun resetCategoryDisplay(category: LeisureCategory) {
        context.leisureBudgetDataStore.edit { prefs ->
            prefs.remove(categoryLabelKey(category))
            prefs.remove(categoryEmojiKey(category))
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

    /**
     * Add or replace a user-defined custom category. Matched by [CustomLeisureCategory.id].
     * Trims blanks; no-ops when label or emoji is blank.
     */
    suspend fun upsertCustomCategory(category: CustomLeisureCategory) {
        val trimmedLabel = category.label.trim()
        val trimmedEmoji = category.emoji.trim()
        if (trimmedLabel.isBlank() || trimmedEmoji.isBlank()) return
        if (!CustomLeisureCategory.isCustomId(category.id)) return
        context.leisureBudgetDataStore.edit { prefs ->
            val current = decodeCustomCategories(prefs[CUSTOM_CATEGORIES_KEY])
            val updated = current.toMutableList().apply {
                val idx = indexOfFirst { it.id == category.id }
                val normalized = category.copy(label = trimmedLabel, emoji = trimmedEmoji)
                if (idx >= 0) set(idx, normalized) else add(normalized)
            }
            prefs[CUSTOM_CATEGORIES_KEY] = encodeCustomCategories(updated)
        }
    }

    /** Remove a custom category by id. No-op when [id] doesn't exist. */
    suspend fun removeCustomCategory(id: String) {
        context.leisureBudgetDataStore.edit { prefs ->
            val current = decodeCustomCategories(prefs[CUSTOM_CATEGORIES_KEY])
            val updated = current.filterNot { it.id == id }
            if (updated.size != current.size) {
                prefs[CUSTOM_CATEGORIES_KEY] = encodeCustomCategories(updated)
            }
        }
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
