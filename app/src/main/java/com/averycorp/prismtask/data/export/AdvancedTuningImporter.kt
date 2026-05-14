package com.averycorp.prismtask.data.export

import com.averycorp.prismtask.data.preferences.AdvancedTuningPreferences
import com.averycorp.prismtask.data.preferences.ApiNetworkConfig
import com.averycorp.prismtask.data.preferences.BatchUndoConfig
import com.averycorp.prismtask.data.preferences.BurnoutWeights
import com.averycorp.prismtask.data.preferences.CognitiveLoadCustomKeywords
import com.averycorp.prismtask.data.preferences.EditorFieldRows
import com.averycorp.prismtask.data.preferences.EnergyPomodoroConfig
import com.averycorp.prismtask.data.preferences.ExtractorConfig
import com.averycorp.prismtask.data.preferences.GoodEnoughTimerConfig
import com.averycorp.prismtask.data.preferences.HabitBorderBrightness
import com.averycorp.prismtask.data.preferences.HabitReminderFallback
import com.averycorp.prismtask.data.preferences.LifeCategoryCustomKeywords
import com.averycorp.prismtask.data.preferences.MoodCorrelationConfig
import com.averycorp.prismtask.data.preferences.MorningCheckInPromptCutoff
import com.averycorp.prismtask.data.preferences.NdPreferencesDataStore
import com.averycorp.prismtask.data.preferences.OverloadCheckSchedule
import com.averycorp.prismtask.data.preferences.ProductivityWeights
import com.averycorp.prismtask.data.preferences.ProductivityWidgetThresholds
import com.averycorp.prismtask.data.preferences.QuickAddRows
import com.averycorp.prismtask.data.preferences.ReengagementConfig
import com.averycorp.prismtask.data.preferences.RefillUrgencyConfig
import com.averycorp.prismtask.data.preferences.SearchPreview
import com.averycorp.prismtask.data.preferences.SelfCareTierDefaults
import com.averycorp.prismtask.data.preferences.SmartDefaultsConfig
import com.averycorp.prismtask.data.preferences.SuggestionConfig
import com.averycorp.prismtask.data.preferences.TaskModeCustomKeywords
import com.averycorp.prismtask.data.preferences.UrgencyBands
import com.averycorp.prismtask.data.preferences.UrgencyWindows
import com.averycorp.prismtask.data.preferences.WeeklySummarySchedule
import com.averycorp.prismtask.data.preferences.WidgetRefreshConfig
import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * Importer for the power-user tuning block and ND preferences. Split off
 * from [ConfigImporter] because the advanced-tuning section alone is
 * ~28 sub-keys and would push the dispatcher file over the per-file LOC
 * budget.
 */
internal class AdvancedTuningImporter(
    private val advancedTuningPreferences: AdvancedTuningPreferences,
    private val ndPreferencesDataStore: NdPreferencesDataStore,
    private val gson: Gson
) {
    /**
     * Restores power-user tuning knobs ([AdvancedTuningPreferences]) from a
     * v5+ backup. Each sub-key maps to a typed config data class; missing
     * sub-keys (older backups) silently no-op so the local default stands.
     * Each setter applies its own clamping, so out-of-range stored values
     * cannot land in the live preference.
     */
    suspend fun importAdvancedTuningConfig(config: JsonObject) {
        val a = config.getAsJsonObject("advancedTuning") ?: return
        val p = advancedTuningPreferences
        a.getAsJsonObject("urgencyBands")?.let {
            p.setUrgencyBands(gson.fromJson(it, UrgencyBands::class.java))
        }
        a.getAsJsonObject("urgencyWindows")?.let {
            p.setUrgencyWindows(gson.fromJson(it, UrgencyWindows::class.java))
        }
        a.getAsJsonObject("burnoutWeights")?.let {
            p.setBurnoutWeights(gson.fromJson(it, BurnoutWeights::class.java))
        }
        a.getAsJsonObject("productivityWeights")?.let {
            p.setProductivityWeights(gson.fromJson(it, ProductivityWeights::class.java))
        }
        a.getAsJsonObject("moodCorrelation")?.let {
            p.setMoodCorrelationConfig(gson.fromJson(it, MoodCorrelationConfig::class.java))
        }
        a.getAsJsonObject("refillUrgency")?.let {
            p.setRefillUrgencyConfig(gson.fromJson(it, RefillUrgencyConfig::class.java))
        }
        a.getAsJsonObject("energyPomodoro")?.let {
            p.setEnergyPomodoroConfig(gson.fromJson(it, EnergyPomodoroConfig::class.java))
        }
        a.getAsJsonObject("goodEnoughTimer")?.let {
            p.setGoodEnoughTimerConfig(gson.fromJson(it, GoodEnoughTimerConfig::class.java))
        }
        a.getAsJsonObject("suggestion")?.let {
            p.setSuggestionConfig(gson.fromJson(it, SuggestionConfig::class.java))
        }
        a.getAsJsonObject("extractor")?.let {
            p.setExtractorConfig(gson.fromJson(it, ExtractorConfig::class.java))
        }
        a.getAsJsonObject("smartDefaults")?.let {
            p.setSmartDefaultsConfig(gson.fromJson(it, SmartDefaultsConfig::class.java))
        }
        a.getAsJsonObject("morningCheckInCutoff")?.let {
            p.setMorningCheckInPromptCutoff(gson.fromJson(it, MorningCheckInPromptCutoff::class.java))
        }
        a.getAsJsonObject("lifeCategoryKeywords")?.let {
            p.setLifeCategoryCustomKeywords(gson.fromJson(it, LifeCategoryCustomKeywords::class.java))
        }
        a.getAsJsonObject("taskModeKeywords")?.let {
            p.setTaskModeCustomKeywords(gson.fromJson(it, TaskModeCustomKeywords::class.java))
        }
        a.getAsJsonObject("cognitiveLoadKeywords")?.let {
            p.setCognitiveLoadCustomKeywords(gson.fromJson(it, CognitiveLoadCustomKeywords::class.java))
        }
        a.getAsJsonObject("weeklySummary")?.let {
            p.setWeeklySummarySchedule(gson.fromJson(it, WeeklySummarySchedule::class.java))
        }
        a.getAsJsonObject("reengagement")?.let {
            p.setReengagementConfig(gson.fromJson(it, ReengagementConfig::class.java))
        }
        a.getAsJsonObject("overloadCheck")?.let {
            p.setOverloadCheckSchedule(gson.fromJson(it, OverloadCheckSchedule::class.java))
        }
        a.getAsJsonObject("batchUndo")?.let {
            p.setBatchUndoConfig(gson.fromJson(it, BatchUndoConfig::class.java))
        }
        a.getAsJsonObject("habitReminderFallback")?.let {
            p.setHabitReminderFallback(gson.fromJson(it, HabitReminderFallback::class.java))
        }
        a.getAsJsonObject("apiNetwork")?.let {
            p.setApiNetworkConfig(gson.fromJson(it, ApiNetworkConfig::class.java))
        }
        a.getAsJsonObject("widgetRefresh")?.let {
            p.setWidgetRefreshConfig(gson.fromJson(it, WidgetRefreshConfig::class.java))
        }
        a.getAsJsonObject("productivityWidget")?.let {
            p.setProductivityWidgetThresholds(gson.fromJson(it, ProductivityWidgetThresholds::class.java))
        }
        a.getAsJsonObject("editorFieldRows")?.let {
            p.setEditorFieldRows(gson.fromJson(it, EditorFieldRows::class.java))
        }
        a.getAsJsonObject("quickAddRows")?.let {
            p.setQuickAddRows(gson.fromJson(it, QuickAddRows::class.java))
        }
        a.getAsJsonObject("searchPreview")?.let {
            p.setSearchPreview(gson.fromJson(it, SearchPreview::class.java))
        }
        a.getAsJsonObject("selfCareTierDefaults")?.let {
            p.setSelfCareTierDefaults(gson.fromJson(it, SelfCareTierDefaults::class.java))
        }
        a.getAsJsonObject("habitBorderBrightness")?.let {
            p.setHabitBorderBrightness(gson.fromJson(it, HabitBorderBrightness::class.java))
        }
    }

    suspend fun importNdConfig(config: JsonObject) {
        val nd = config.getAsJsonObject("nd") ?: return
        // Route every known ND key through updateNdPreference so validation,
        // enum coercion, and coupling (mode toggles flip sub-settings) live in
        // one place. Unknown keys are skipped silently.
        nd.entrySet().forEach { (key, value) ->
            if (value.isJsonNull) return@forEach
            val primitive = value.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return@forEach
            // Map our camelCase data-class field names to the keys
            // updateNdPreference() expects (snake_case + "_enabled" suffixes).
            val mapped = NdCamelToUpdateKey[key] ?: return@forEach
            val coerced: Any? = when {
                primitive.isBoolean -> primitive.asBoolean
                primitive.isNumber -> primitive.asNumber.toInt()
                primitive.isString -> primitive.asString
                else -> null
            }
            if (coerced != null) {
                runCatching { ndPreferencesDataStore.updateNdPreference(mapped, coerced) }
            }
        }
    }

    companion object {
        /**
         * Maps serialized `NdPreferences` field names (camelCase) to the keys
         * accepted by [NdPreferencesDataStore.updateNdPreference] (snake_case,
         * some with an "_enabled" suffix).
         */
        private val NdCamelToUpdateKey: Map<String, String> = mapOf(
            "adhdModeEnabled" to "adhd_mode_enabled",
            "calmModeEnabled" to "calm_mode_enabled",
            "focusReleaseModeEnabled" to "focus_release_mode_enabled",
            "reduceAnimations" to "reduce_animations",
            "mutedColorPalette" to "muted_color_palette",
            "quietMode" to "quiet_mode",
            "reduceHaptics" to "reduce_haptics",
            "softContrast" to "soft_contrast",
            "checkInIntervalMinutes" to "check_in_interval_minutes",
            "completionAnimations" to "completion_animations",
            "streakCelebrations" to "streak_celebrations",
            "showProgressBars" to "show_progress_bars",
            // `forgivenessStreaks` was removed in the mental-health-first
            // audit § R6 (duplicate of the global ForgivenessPrefs.enabled,
            // no consumer). Old backups carrying this key fall through the
            // `?: return@forEach` path in `importNdConfig` and are ignored.
            "goodEnoughTimersEnabled" to "good_enough_timers_enabled",
            "defaultGoodEnoughMinutes" to "default_good_enough_minutes",
            "goodEnoughEscalation" to "good_enough_escalation",
            "antiReworkEnabled" to "anti_rework_enabled",
            "softWarningEnabled" to "soft_warning_enabled",
            "coolingOffEnabled" to "cooling_off_enabled",
            "coolingOffMinutes" to "cooling_off_minutes",
            "revisionCounterEnabled" to "revision_counter_enabled",
            "maxRevisions" to "max_revisions",
            "shipItCelebrationsEnabled" to "ship_it_celebrations_enabled",
            "celebrationIntensity" to "celebration_intensity"
        )
    }
}
