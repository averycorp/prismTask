package com.averycorp.prismtask.data.export

import com.averycorp.prismtask.data.preferences.AppearancePrefs
import com.averycorp.prismtask.data.preferences.ForgivenessPrefs
import com.averycorp.prismtask.data.preferences.QuickAddPrefs
import com.averycorp.prismtask.data.preferences.SwipePrefs
import com.averycorp.prismtask.data.preferences.TaskDefaults
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.preferences.WorkLifeBalancePrefs
import com.averycorp.prismtask.domain.model.AutoDueDate
import com.averycorp.prismtask.domain.model.StartOfWeek
import com.averycorp.prismtask.domain.model.SwipeAction
import com.averycorp.prismtask.domain.model.TaskCardDisplayConfig
import com.averycorp.prismtask.domain.model.TaskMenuAction
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first

/**
 * Importer for the cross-cutting `config.userPreferences` block. Split
 * out of [ConfigImporter] so neither file exceeds the per-helper LOC
 * budget. Each section reads the current preference value first and
 * falls back to it when the import payload omits a field, preserving
 * forward/backwards compatibility on additive schema changes.
 */
internal class UserPreferencesImporter(
    private val userPreferencesDataStore: UserPreferencesDataStore,
    private val gson: Gson
) {
    suspend fun importUserPreferencesConfig(config: JsonObject) {
        config.getAsJsonObject("userPreferences")?.let { userPrefs ->
            importAppearancePrefs(userPrefs)
            importSwipePrefs(userPrefs)
            importTaskDefaultsPrefs(userPrefs)
            importQuickAddPrefs(userPrefs)
            importWorkLifeBalancePrefs(userPrefs)
            importForgivenessPrefs(userPrefs)
            importTaskMenuActionsPrefs(userPrefs)
            importTaskCardDisplayPrefs(userPrefs)
        }
    }

    private suspend fun importForgivenessPrefs(userPrefs: JsonObject) {
        userPrefs.getAsJsonObject("forgiveness")?.let { f ->
            val current = userPreferencesDataStore.forgivenessFlow.first()
            userPreferencesDataStore.setForgivenessPrefs(
                ForgivenessPrefs(
                    enabled = f.get("enabled")?.takeIf { !it.isJsonNull }?.asBoolean ?: current.enabled,
                    gracePeriodDays = f.get("gracePeriodDays")?.takeIf { !it.isJsonNull }?.asInt
                        ?: current.gracePeriodDays,
                    allowedMisses = f.get("allowedMisses")?.takeIf { !it.isJsonNull }?.asInt
                        ?: current.allowedMisses
                )
            )
        }
    }

    private suspend fun importTaskMenuActionsPrefs(userPrefs: JsonObject) {
        val arr = userPrefs.getAsJsonArray("taskMenuActions") ?: return
        try {
            val listType = TypeToken.getParameterized(
                List::class.java,
                TaskMenuAction::class.java
            ).type
            val actions: List<TaskMenuAction> = gson.fromJson(arr, listType)
            userPreferencesDataStore.setTaskMenuActions(actions)
        } catch (_: Exception) {
            // Malformed — fall back to defaults (already the DataStore behavior).
        }
    }

    private suspend fun importTaskCardDisplayPrefs(userPrefs: JsonObject) {
        val obj = userPrefs.getAsJsonObject("taskCardDisplay") ?: return
        try {
            val cfg = gson.fromJson(obj, TaskCardDisplayConfig::class.java)
            if (cfg != null) userPreferencesDataStore.setTaskCardDisplay(cfg)
        } catch (_: Exception) {
            // Malformed — ignore.
        }
    }

    private suspend fun importAppearancePrefs(userPrefs: JsonObject) {
        userPrefs.getAsJsonObject("appearance")?.let { a ->
            val current = userPreferencesDataStore.appearanceFlow.first()
            userPreferencesDataStore.setAppearance(
                AppearancePrefs(
                    compactMode = a.get("compactMode")?.takeIf { !it.isJsonNull }?.asBoolean ?: current.compactMode,
                    showTaskCardBorders =
                    a.get("showTaskCardBorders")?.takeIf { !it.isJsonNull }?.asBoolean ?: current.showTaskCardBorders,
                    cardCornerRadius = a.get("cardCornerRadius")?.takeIf { !it.isJsonNull }?.asInt ?: current.cardCornerRadius
                )
            )
        }
    }

    private suspend fun importSwipePrefs(userPrefs: JsonObject) {
        userPrefs.getAsJsonObject("swipe")?.let { s ->
            userPreferencesDataStore.setSwipe(
                SwipePrefs(
                    right = SwipeAction.fromName(
                        s.get("right")?.takeIf { !it.isJsonNull }?.asString
                    ),
                    left = SwipeAction.fromName(
                        s.get("left")?.takeIf { !it.isJsonNull }?.asString
                            ?: SwipeAction.DELETE.name
                    )
                )
            )
        }
    }

    private suspend fun importTaskDefaultsPrefs(userPrefs: JsonObject) {
        userPrefs.getAsJsonObject("taskDefaults")?.let { d ->
            val current = userPreferencesDataStore.taskDefaultsFlow.first()
            userPreferencesDataStore.setTaskDefaults(
                TaskDefaults(
                    defaultPriority = d.get("defaultPriority")?.takeIf { !it.isJsonNull }?.asInt ?: current.defaultPriority,
                    defaultReminderOffset =
                    d.get("defaultReminderOffset")?.takeIf { !it.isJsonNull }?.asLong ?: current.defaultReminderOffset,
                    defaultProjectId = d.get("defaultProjectId")?.takeIf { !it.isJsonNull }?.asLong ?: current.defaultProjectId,
                    startOfWeek = StartOfWeek.fromName(
                        d.get("startOfWeek")?.takeIf { !it.isJsonNull }?.asString
                    ),
                    defaultDuration = d.get("defaultDuration")?.takeIf { !it.isJsonNull }?.asInt ?: current.defaultDuration,
                    autoSetDueDate = AutoDueDate.fromName(
                        d.get("autoSetDueDate")?.takeIf { !it.isJsonNull }?.asString
                    ),
                    smartDefaultsEnabled =
                    d.get("smartDefaultsEnabled")?.takeIf { !it.isJsonNull }?.asBoolean ?: current.smartDefaultsEnabled
                )
            )
        }
    }

    private suspend fun importQuickAddPrefs(userPrefs: JsonObject) {
        userPrefs.getAsJsonObject("quickAdd")?.let { q ->
            userPreferencesDataStore.setQuickAdd(
                QuickAddPrefs(
                    showConfirmation = q.get("showConfirmation")?.takeIf { !it.isJsonNull }?.asBoolean ?: true,
                    autoAssignProject = q.get("autoAssignProject")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                )
            )
        }
    }

    private suspend fun importWorkLifeBalancePrefs(userPrefs: JsonObject) {
        userPrefs.getAsJsonObject("workLifeBalance")?.let { w ->
            val current = userPreferencesDataStore.workLifeBalanceFlow.first()
            userPreferencesDataStore.setWorkLifeBalance(
                WorkLifeBalancePrefs(
                    workTarget = w.get("workTarget")?.takeIf { !it.isJsonNull }?.asInt ?: current.workTarget,
                    personalTarget = w.get("personalTarget")?.takeIf { !it.isJsonNull }?.asInt ?: current.personalTarget,
                    selfCareTarget = w.get("selfCareTarget")?.takeIf { !it.isJsonNull }?.asInt ?: current.selfCareTarget,
                    healthTarget = w.get("healthTarget")?.takeIf { !it.isJsonNull }?.asInt ?: current.healthTarget,
                    showBalanceBar = w.get("showBalanceBar")?.takeIf { !it.isJsonNull }?.asBoolean ?: current.showBalanceBar,
                    overloadThresholdPct =
                    w.get("overloadThresholdPct")?.takeIf { !it.isJsonNull }?.asInt ?: current.overloadThresholdPct
                )
            )
        }
    }
}
