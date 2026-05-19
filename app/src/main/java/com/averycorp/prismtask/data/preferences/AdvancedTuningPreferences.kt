package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.advancedTuningDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "advanced_tuning_prefs")

/**
 * Bands the urgency score is bucketed into. Sliders in Settings → Task Defaults
 * → Urgency. Defaults match the values shipped through v1.6.
 */
data class UrgencyBands(
    val critical: Float = 0.7f,
    val high: Float = 0.5f,
    val medium: Float = 0.3f
)

/** Day windows that bend the score curve in [UrgencyScorer.calculateScore]. */
data class UrgencyWindows(
    val overdueCeilingDays: Int = 7,
    val imminentWindowDays: Int = 7
)

/** Maximum-points caps for each component of the burnout score (sum to 100). */
data class BurnoutWeights(
    val workMax: Int = 25,
    val overdueMax: Int = 20,
    val selfCareMax: Int = 20,
    val medicationMax: Int = 15,
    val streakMax: Int = 10,
    val restDeficitMax: Int = 10,
    val restDeficitDays: Int = 2
)

/** Productivity score component weights — must sum to 1.0. */
data class ProductivityWeights(
    val taskWeight: Float = 0.40f,
    val onTimeWeight: Float = 0.25f,
    val habitWeight: Float = 0.20f,
    val estimationWeight: Float = 0.15f,
    val trendThreshold: Float = 3.0f
)

/** Mood correlation gating: minimum samples and label cutoffs. */
data class MoodCorrelationConfig(
    val minObservations: Int = 7,
    val strongThreshold: Float = 0.5f,
    val moderateThreshold: Float = 0.3f
)

/** Days-of-supply thresholds that drive medication refill urgency labels. */
data class RefillUrgencyConfig(
    val urgentDays: Int = 3,
    val upcomingDays: Int = 7
)

/** Per-energy-band Pomodoro session timing (minutes). */
data class EnergyPomodoroConfig(
    val veryLowWork: Int = 15,
    val veryLowBreak: Int = 10,
    val veryLowLong: Int = 20,
    val lowWork: Int = 15,
    val lowBreak: Int = 10,
    val lowLong: Int = 20,
    val mediumWork: Int = 25,
    val mediumBreak: Int = 5,
    val mediumLong: Int = 15,
    val highWork: Int = 35,
    val highBreak: Int = 4,
    val highLong: Int = 12,
    val veryHighWork: Int = 45,
    val veryHighBreak: Int = 3,
    val veryHighLong: Int = 10
)

/** Good-Enough Timer (ND focus release) timings, in minutes. */
data class GoodEnoughTimerConfig(
    val gracePeriodMinutes: Int = 2,
    val nudgeCooldownMinutes: Int = 10,
    val dialogCooldownMinutes: Int = 15,
    val extensionMinutes: Int = 10
)

/** Smart-suggestion confidence cutoffs and result count. */
data class SuggestionConfig(
    val tagThreshold: Float = 0.2f,
    val projectThreshold: Float = 0.3f,
    val maxResults: Int = 3
)

/** Conversation extractor input/title caps. */
data class ExtractorConfig(
    val maxInputChars: Int = 10_000,
    val maxTitleChars: Int = 120
)

/** Sample-size + duration granularity gates for SmartDefaultsEngine. */
data class SmartDefaultsConfig(
    val minHistory: Int = 5,
    val durationGranularityMinutes: Int = 15
)

/**
 * Length of the morning check-in availability window, in hours after the
 * user's Start-of-Day. The banner and card hide once this many hours have
 * elapsed since SoD. Range 1..24; default 12.
 */
data class MorningCheckInPromptCutoff(
    val windowHours: Int = 12
)

/** Per-category extra keywords (CSV) appended to the built-in classifier list. */
data class LifeCategoryCustomKeywords(
    val work: String = "",
    val personal: String = "",
    val selfCare: String = "",
    val health: String = ""
)

/**
 * Per-mode extra keywords (CSV) appended to the built-in
 * [com.averycorp.prismtask.domain.usecase.TaskModeClassifier] list.
 * See `docs/WORK_PLAY_RELAX.md` § Inference rules.
 */
data class TaskModeCustomKeywords(
    val work: String = "",
    val play: String = "",
    val relax: String = ""
)

/**
 * Per-load extra keywords (CSV) appended to the built-in
 * [com.averycorp.prismtask.domain.usecase.CognitiveLoadClassifier] list.
 * See `docs/COGNITIVE_LOAD.md` § Inference rules.
 */
data class CognitiveLoadCustomKeywords(
    val easy: String = "",
    val medium: String = "",
    val hard: String = ""
)

/** Day-of-week (1=Mon..7=Sun) and clock time for weekly summary workers. */
data class WeeklySummarySchedule(
    // 7 = Sunday.
    val dayOfWeek: Int = 7,
    val taskSummaryHour: Int = 19,
    val taskSummaryMinute: Int = 30,
    val habitSummaryHour: Int = 19,
    val habitSummaryMinute: Int = 0,
    val reviewHour: Int = 20,
    val reviewMinute: Int = 0,
    val eveningSummaryHour: Int = 20,
    // 19:00 default — fires before the weekly review (20:00) so the
    // two Sunday notifications stagger.
    val analyticsSummaryHour: Int = 19,
    val analyticsSummaryMinute: Int = 0
)

/** Re-engagement nudge thresholds. */
data class ReengagementConfig(
    val absenceDays: Int = 2,
    val maxNudges: Int = 1
)

/** Hour of day (0..23) the OverloadCheckWorker fires its periodic check. */
data class OverloadCheckSchedule(
    val hourOfDay: Int = 16,
    val minute: Int = 0
)

/** Days a soft-deleted batch (undo tail) lingers before sweep. */
data class BatchUndoConfig(
    val tailDays: Int = 7
)

/** Fallback hour-of-day applied when a habit's reminderTime CSV is malformed. */
data class HabitReminderFallback(
    val hour: Int = 8,
    val minute: Int = 0
)

/** OkHttp + token-refresh retry knobs for power users on flaky networks. */
data class ApiNetworkConfig(
    val timeoutSeconds: Int = 30,
    val retryAttempts: Int = 2
)

/** Periodic widget refresh cadence (15 = WorkManager floor, 240 = battery-saver ceiling). */
data class WidgetRefreshConfig(
    val intervalMinutes: Int = 15
)

/** Productivity widget score-to-color thresholds (≥green = green, ≥orange = orange, below = red). */
data class ProductivityWidgetThresholds(
    val greenScore: Int = 80,
    val orangeScore: Int = 60
)

/**
 * Editor field row caps for long-form planners. These are the
 * `maxLines` values applied to the description and notes fields in
 * the Add/Edit Task editor (E1).
 */
data class EditorFieldRows(
    val descriptionRows: Int = 5,
    val notesRows: Int = 8
)

/** QuickAdd / paste-multi-task field max-lines (E2). */
data class QuickAddRows(
    val maxLines: Int = 5
)

/** Search results description preview line count (E5). */
data class SearchPreview(
    val previewLines: Int = 2
)

/**
 * Alpha applied to habit-card outer borders. 0.0 hides the border entirely,
 * 1.0 paints it fully opaque. Default 0.4 matches the value shipped before
 * the setting was introduced.
 */
data class HabitBorderBrightness(
    val brightness: Float = 0.4f
)

/**
 * First-display tier per Self-Care routine, applied when no log exists for
 * today. Stored values are validated against the routine's tier order at
 * read time by [SelfCareViewModel.getSelectedTier]; an unknown tier id
 * (e.g. one retired in a future build) falls back to the routine's
 * penultimate-of-order default the ViewModel already used implicitly.
 * Defaults below match that historical behaviour.
 */
data class SelfCareTierDefaults(
    val morning: String = "solid",
    val bedtime: String = "solid",
    val medication: String = "prescription",
    val housework: String = "regular"
) {
    fun forRoutine(routineType: String): String? = when (routineType) {
        "morning" -> morning
        "bedtime" -> bedtime
        "medication" -> medication
        "housework" -> housework
        else -> null
    }
}

@Singleton
class AdvancedTuningPreferences
@Inject
constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // B1 — urgency bands
        private val URGENCY_BAND_CRITICAL = floatPreferencesKey("urgency_band_critical")
        private val URGENCY_BAND_HIGH = floatPreferencesKey("urgency_band_high")
        private val URGENCY_BAND_MEDIUM = floatPreferencesKey("urgency_band_medium")

        // B2 — urgency day windows
        private val URGENCY_OVERDUE_CEIL = intPreferencesKey("urgency_overdue_ceiling_days")
        private val URGENCY_IMMINENT_WIN = intPreferencesKey("urgency_imminent_window_days")

        // B3, B4 — burnout
        private val BURNOUT_WORK_MAX = intPreferencesKey("burnout_work_max")
        private val BURNOUT_OVERDUE_MAX = intPreferencesKey("burnout_overdue_max")
        private val BURNOUT_SELFCARE_MAX = intPreferencesKey("burnout_selfcare_max")
        private val BURNOUT_MEDICATION_MAX = intPreferencesKey("burnout_medication_max")
        private val BURNOUT_STREAK_MAX = intPreferencesKey("burnout_streak_max")
        private val BURNOUT_REST_MAX = intPreferencesKey("burnout_rest_deficit_max")
        private val BURNOUT_REST_DAYS = intPreferencesKey("burnout_rest_deficit_days")

        // B11 — productivity score weights
        private val PROD_TASK = floatPreferencesKey("productivity_task_weight")
        private val PROD_ONTIME = floatPreferencesKey("productivity_ontime_weight")
        private val PROD_HABIT = floatPreferencesKey("productivity_habit_weight")
        private val PROD_ESTIMATION = floatPreferencesKey("productivity_estimation_weight")
        private val PROD_TREND_THRESHOLD = floatPreferencesKey("productivity_trend_threshold")

        // B5 — mood correlation
        private val MOOD_MIN_OBS = intPreferencesKey("mood_min_observations")
        private val MOOD_STRONG = floatPreferencesKey("mood_strong_threshold")
        private val MOOD_MODERATE = floatPreferencesKey("mood_moderate_threshold")

        // B6 — refill urgency
        private val REFILL_URGENT = intPreferencesKey("refill_urgent_days")
        private val REFILL_UPCOMING = intPreferencesKey("refill_upcoming_days")

        // B7 — energy pomodoro (5 bands × 3 fields)
        private val POM_VL_W = intPreferencesKey("pom_very_low_work")
        private val POM_VL_B = intPreferencesKey("pom_very_low_break")
        private val POM_VL_L = intPreferencesKey("pom_very_low_long")
        private val POM_L_W = intPreferencesKey("pom_low_work")
        private val POM_L_B = intPreferencesKey("pom_low_break")
        private val POM_L_L = intPreferencesKey("pom_low_long")
        private val POM_M_W = intPreferencesKey("pom_medium_work")
        private val POM_M_B = intPreferencesKey("pom_medium_break")
        private val POM_M_L = intPreferencesKey("pom_medium_long")
        private val POM_H_W = intPreferencesKey("pom_high_work")
        private val POM_H_B = intPreferencesKey("pom_high_break")
        private val POM_H_L = intPreferencesKey("pom_high_long")
        private val POM_VH_W = intPreferencesKey("pom_very_high_work")
        private val POM_VH_B = intPreferencesKey("pom_very_high_break")
        private val POM_VH_L = intPreferencesKey("pom_very_high_long")

        // B8 — good-enough timer
        private val GE_GRACE = intPreferencesKey("ge_timer_grace_min")
        private val GE_NUDGE = intPreferencesKey("ge_timer_nudge_min")
        private val GE_DIALOG = intPreferencesKey("ge_timer_dialog_min")
        private val GE_EXTEND = intPreferencesKey("ge_timer_extend_min")

        // B9 — suggestion engine
        private val SUG_TAG_THRESH = floatPreferencesKey("suggestion_tag_threshold")
        private val SUG_PROJ_THRESH = floatPreferencesKey("suggestion_project_threshold")
        private val SUG_MAX_RESULTS = intPreferencesKey("suggestion_max_results")

        // B10 — extractor caps
        private val EXTRACT_MAX_INPUT = intPreferencesKey("extract_max_input_chars")
        private val EXTRACT_MAX_TITLE = intPreferencesKey("extract_max_title_chars")

        // B12 — smart defaults
        private val SD_MIN_HISTORY = intPreferencesKey("smart_defaults_min_history")
        private val SD_GRANULARITY = intPreferencesKey("smart_defaults_granularity")

        // B14 — morning check-in availability window (hours after SoD)
        private val MORNING_WINDOW_HOURS = intPreferencesKey("morning_checkin_window_hours")

        // B15 — custom keywords
        private val CK_WORK = stringPreferencesKey("custom_keywords_work")
        private val CK_PERSONAL = stringPreferencesKey("custom_keywords_personal")
        private val CK_SELFCARE = stringPreferencesKey("custom_keywords_selfcare")
        private val CK_HEALTH = stringPreferencesKey("custom_keywords_health")

        // B16 — task-mode custom keywords (Work / Play / Relax)
        private val MODE_CK_WORK = stringPreferencesKey("mode_custom_keywords_work")
        private val MODE_CK_PLAY = stringPreferencesKey("mode_custom_keywords_play")
        private val MODE_CK_RELAX = stringPreferencesKey("mode_custom_keywords_relax")

        // B17 — cognitive-load custom keywords (Easy / Medium / Hard)
        private val LOAD_CK_EASY = stringPreferencesKey("load_custom_keywords_easy")
        private val LOAD_CK_MEDIUM = stringPreferencesKey("load_custom_keywords_medium")
        private val LOAD_CK_HARD = stringPreferencesKey("load_custom_keywords_hard")

        // C1 — weekly summary schedule
        private val WS_DAY = intPreferencesKey("weekly_summary_day_of_week")
        private val WS_TASK_HR = intPreferencesKey("weekly_summary_task_hour")
        private val WS_TASK_MIN = intPreferencesKey("weekly_summary_task_minute")
        private val WS_HABIT_HR = intPreferencesKey("weekly_summary_habit_hour")
        private val WS_HABIT_MIN = intPreferencesKey("weekly_summary_habit_minute")
        private val WS_REVIEW_HR = intPreferencesKey("weekly_summary_review_hour")
        private val WS_REVIEW_MIN = intPreferencesKey("weekly_summary_review_minute")
        private val WS_EVENING_HR = intPreferencesKey("weekly_summary_evening_hour")
        private val WS_ANALYTICS_HR = intPreferencesKey("weekly_summary_analytics_hour")
        private val WS_ANALYTICS_MIN = intPreferencesKey("weekly_summary_analytics_minute")

        // C2 — re-engagement
        private val RE_ABSENCE_DAYS = intPreferencesKey("reengagement_absence_days")
        private val RE_MAX_NUDGES = intPreferencesKey("reengagement_max_nudges")

        // C3 — overload check schedule
        private val OC_HOUR = intPreferencesKey("overload_check_hour")
        private val OC_MINUTE = intPreferencesKey("overload_check_minute")

        // C4 — batch undo tail
        private val BU_TAIL_DAYS = intPreferencesKey("batch_undo_tail_days")

        // C7 — habit reminder fallback
        private val HRF_HOUR = intPreferencesKey("habit_reminder_fallback_hour")
        private val HRF_MINUTE = intPreferencesKey("habit_reminder_fallback_minute")

        // C9 — API network
        private val API_TIMEOUT = intPreferencesKey("api_network_timeout_seconds")
        private val API_RETRIES = intPreferencesKey("api_network_retry_attempts")

        // D1 — widget refresh cadence
        private val WIDGET_REFRESH_MINUTES = intPreferencesKey("widget_refresh_minutes")

        // D4 — productivity widget thresholds
        private val PROD_WIDGET_GREEN = intPreferencesKey("productivity_widget_green")
        private val PROD_WIDGET_ORANGE = intPreferencesKey("productivity_widget_orange")

        // E1 — editor field rows
        private val EDITOR_DESCRIPTION_ROWS = intPreferencesKey("editor_description_rows")
        private val EDITOR_NOTES_ROWS = intPreferencesKey("editor_notes_rows")

        // E2 — quick-add rows
        private val QUICKADD_MAX_LINES = intPreferencesKey("quickadd_max_lines")

        // E5 — search preview lines
        private val SEARCH_PREVIEW_LINES = intPreferencesKey("search_preview_lines")

        // Habit-card outer border brightness (0..1 alpha, default 0.4)
        private val HABIT_BORDER_BRIGHTNESS = floatPreferencesKey("habit_border_brightness")

        // Self-care default tiers (one per routine)
        private val SC_DEFAULT_MORNING = stringPreferencesKey("selfcare_default_tier_morning")
        private val SC_DEFAULT_BEDTIME = stringPreferencesKey("selfcare_default_tier_bedtime")
        private val SC_DEFAULT_MEDICATION = stringPreferencesKey("selfcare_default_tier_medication")
        private val SC_DEFAULT_HOUSEWORK = stringPreferencesKey("selfcare_default_tier_housework")
    }

    fun getUrgencyBands(): Flow<UrgencyBands> = context.advancedTuningDataStore.data.map {
        UrgencyBands(
            critical = it[URGENCY_BAND_CRITICAL] ?: 0.7f,
            high = it[URGENCY_BAND_HIGH] ?: 0.5f,
            medium = it[URGENCY_BAND_MEDIUM] ?: 0.3f
        )
    }

    fun getUrgencyWindows(): Flow<UrgencyWindows> = context.advancedTuningDataStore.data.map {
        UrgencyWindows(
            overdueCeilingDays = (it[URGENCY_OVERDUE_CEIL] ?: 7).coerceAtLeast(1),
            imminentWindowDays = (it[URGENCY_IMMINENT_WIN] ?: 7).coerceAtLeast(1)
        )
    }

    fun getBurnoutWeights(): Flow<BurnoutWeights> = context.advancedTuningDataStore.data.map {
        BurnoutWeights(
            workMax = it[BURNOUT_WORK_MAX] ?: 25,
            overdueMax = it[BURNOUT_OVERDUE_MAX] ?: 20,
            selfCareMax = it[BURNOUT_SELFCARE_MAX] ?: 20,
            medicationMax = it[BURNOUT_MEDICATION_MAX] ?: 15,
            streakMax = it[BURNOUT_STREAK_MAX] ?: 10,
            restDeficitMax = it[BURNOUT_REST_MAX] ?: 10,
            restDeficitDays = (it[BURNOUT_REST_DAYS] ?: 2).coerceAtLeast(1)
        )
    }

    fun getProductivityWeights(): Flow<ProductivityWeights> = context.advancedTuningDataStore.data.map {
        ProductivityWeights(
            taskWeight = it[PROD_TASK] ?: 0.40f,
            onTimeWeight = it[PROD_ONTIME] ?: 0.25f,
            habitWeight = it[PROD_HABIT] ?: 0.20f,
            estimationWeight = it[PROD_ESTIMATION] ?: 0.15f,
            trendThreshold = it[PROD_TREND_THRESHOLD] ?: 3.0f
        )
    }

    fun getMoodCorrelationConfig(): Flow<MoodCorrelationConfig> = context.advancedTuningDataStore.data.map {
        MoodCorrelationConfig(
            minObservations = (it[MOOD_MIN_OBS] ?: 7).coerceAtLeast(1),
            strongThreshold = it[MOOD_STRONG] ?: 0.5f,
            moderateThreshold = it[MOOD_MODERATE] ?: 0.3f
        )
    }

    fun getRefillUrgencyConfig(): Flow<RefillUrgencyConfig> = context.advancedTuningDataStore.data.map {
        RefillUrgencyConfig(
            urgentDays = (it[REFILL_URGENT] ?: 3).coerceAtLeast(0),
            upcomingDays = (it[REFILL_UPCOMING] ?: 7).coerceAtLeast(0)
        )
    }

    fun getEnergyPomodoroConfig(): Flow<EnergyPomodoroConfig> = context.advancedTuningDataStore.data.map {
        EnergyPomodoroConfig(
            veryLowWork = it[POM_VL_W] ?: 15, veryLowBreak = it[POM_VL_B] ?: 10, veryLowLong = it[POM_VL_L] ?: 20,
            lowWork = it[POM_L_W] ?: 15, lowBreak = it[POM_L_B] ?: 10, lowLong = it[POM_L_L] ?: 20,
            mediumWork = it[POM_M_W] ?: 25, mediumBreak = it[POM_M_B] ?: 5, mediumLong = it[POM_M_L] ?: 15,
            highWork = it[POM_H_W] ?: 35, highBreak = it[POM_H_B] ?: 4, highLong = it[POM_H_L] ?: 12,
            veryHighWork = it[POM_VH_W] ?: 45, veryHighBreak = it[POM_VH_B] ?: 3, veryHighLong = it[POM_VH_L] ?: 10
        )
    }

    fun getGoodEnoughTimerConfig(): Flow<GoodEnoughTimerConfig> = context.advancedTuningDataStore.data.map {
        GoodEnoughTimerConfig(
            gracePeriodMinutes = it[GE_GRACE] ?: 2,
            nudgeCooldownMinutes = it[GE_NUDGE] ?: 10,
            dialogCooldownMinutes = it[GE_DIALOG] ?: 15,
            extensionMinutes = it[GE_EXTEND] ?: 10
        )
    }

    fun getSuggestionConfig(): Flow<SuggestionConfig> = context.advancedTuningDataStore.data.map {
        SuggestionConfig(
            tagThreshold = it[SUG_TAG_THRESH] ?: 0.2f,
            projectThreshold = it[SUG_PROJ_THRESH] ?: 0.3f,
            maxResults = (it[SUG_MAX_RESULTS] ?: 3).coerceAtLeast(1)
        )
    }

    fun getExtractorConfig(): Flow<ExtractorConfig> = context.advancedTuningDataStore.data.map {
        ExtractorConfig(
            maxInputChars = (it[EXTRACT_MAX_INPUT] ?: 10_000).coerceAtLeast(100),
            maxTitleChars = (it[EXTRACT_MAX_TITLE] ?: 120).coerceAtLeast(20)
        )
    }

    fun getSmartDefaultsConfig(): Flow<SmartDefaultsConfig> = context.advancedTuningDataStore.data.map {
        SmartDefaultsConfig(
            minHistory = (it[SD_MIN_HISTORY] ?: 5).coerceAtLeast(1),
            durationGranularityMinutes = (it[SD_GRANULARITY] ?: 15).coerceAtLeast(1)
        )
    }

    fun getMorningCheckInPromptCutoff(): Flow<MorningCheckInPromptCutoff> = context.advancedTuningDataStore.data.map {
        MorningCheckInPromptCutoff(
            windowHours = (it[MORNING_WINDOW_HOURS] ?: 12).coerceIn(1, 24)
        )
    }

    fun getLifeCategoryCustomKeywords(): Flow<LifeCategoryCustomKeywords> = context.advancedTuningDataStore.data.map {
        LifeCategoryCustomKeywords(
            work = it[CK_WORK] ?: "",
            personal = it[CK_PERSONAL] ?: "",
            selfCare = it[CK_SELFCARE] ?: "",
            health = it[CK_HEALTH] ?: ""
        )
    }

    fun getTaskModeCustomKeywords(): Flow<TaskModeCustomKeywords> = context.advancedTuningDataStore.data.map {
        TaskModeCustomKeywords(
            work = it[MODE_CK_WORK] ?: "",
            play = it[MODE_CK_PLAY] ?: "",
            relax = it[MODE_CK_RELAX] ?: ""
        )
    }

    fun getCognitiveLoadCustomKeywords(): Flow<CognitiveLoadCustomKeywords> = context.advancedTuningDataStore.data.map {
        CognitiveLoadCustomKeywords(
            easy = it[LOAD_CK_EASY] ?: "",
            medium = it[LOAD_CK_MEDIUM] ?: "",
            hard = it[LOAD_CK_HARD] ?: ""
        )
    }

    suspend fun setUrgencyBands(bands: UrgencyBands) {
        context.advancedTuningDataStore.edit {
            it[URGENCY_BAND_CRITICAL] = bands.critical
            it[URGENCY_BAND_HIGH] = bands.high
            it[URGENCY_BAND_MEDIUM] = bands.medium
        }
    }

    suspend fun setUrgencyWindows(windows: UrgencyWindows) {
        context.advancedTuningDataStore.edit {
            it[URGENCY_OVERDUE_CEIL] = windows.overdueCeilingDays
            it[URGENCY_IMMINENT_WIN] = windows.imminentWindowDays
        }
    }

    suspend fun setBurnoutWeights(w: BurnoutWeights) {
        context.advancedTuningDataStore.edit {
            it[BURNOUT_WORK_MAX] = w.workMax
            it[BURNOUT_OVERDUE_MAX] = w.overdueMax
            it[BURNOUT_SELFCARE_MAX] = w.selfCareMax
            it[BURNOUT_MEDICATION_MAX] = w.medicationMax
            it[BURNOUT_STREAK_MAX] = w.streakMax
            it[BURNOUT_REST_MAX] = w.restDeficitMax
            it[BURNOUT_REST_DAYS] = w.restDeficitDays
        }
    }

    suspend fun setProductivityWeights(w: ProductivityWeights) {
        context.advancedTuningDataStore.edit {
            it[PROD_TASK] = w.taskWeight
            it[PROD_ONTIME] = w.onTimeWeight
            it[PROD_HABIT] = w.habitWeight
            it[PROD_ESTIMATION] = w.estimationWeight
            it[PROD_TREND_THRESHOLD] = w.trendThreshold
        }
    }

    suspend fun setMoodCorrelationConfig(c: MoodCorrelationConfig) {
        context.advancedTuningDataStore.edit {
            it[MOOD_MIN_OBS] = c.minObservations
            it[MOOD_STRONG] = c.strongThreshold
            it[MOOD_MODERATE] = c.moderateThreshold
        }
    }

    suspend fun setRefillUrgencyConfig(c: RefillUrgencyConfig) {
        context.advancedTuningDataStore.edit {
            it[REFILL_URGENT] = c.urgentDays
            it[REFILL_UPCOMING] = c.upcomingDays
        }
    }

    suspend fun setEnergyPomodoroConfig(c: EnergyPomodoroConfig) {
        context.advancedTuningDataStore.edit {
            it[POM_VL_W] = c.veryLowWork
            it[POM_VL_B] = c.veryLowBreak
            it[POM_VL_L] = c.veryLowLong
            it[POM_L_W] = c.lowWork
            it[POM_L_B] = c.lowBreak
            it[POM_L_L] = c.lowLong
            it[POM_M_W] = c.mediumWork
            it[POM_M_B] = c.mediumBreak
            it[POM_M_L] = c.mediumLong
            it[POM_H_W] = c.highWork
            it[POM_H_B] = c.highBreak
            it[POM_H_L] = c.highLong
            it[POM_VH_W] = c.veryHighWork
            it[POM_VH_B] = c.veryHighBreak
            it[POM_VH_L] = c.veryHighLong
        }
    }

    suspend fun setGoodEnoughTimerConfig(c: GoodEnoughTimerConfig) {
        context.advancedTuningDataStore.edit {
            it[GE_GRACE] = c.gracePeriodMinutes
            it[GE_NUDGE] = c.nudgeCooldownMinutes
            it[GE_DIALOG] = c.dialogCooldownMinutes
            it[GE_EXTEND] = c.extensionMinutes
        }
    }

    suspend fun setSuggestionConfig(c: SuggestionConfig) {
        context.advancedTuningDataStore.edit {
            it[SUG_TAG_THRESH] = c.tagThreshold
            it[SUG_PROJ_THRESH] = c.projectThreshold
            it[SUG_MAX_RESULTS] = c.maxResults
        }
    }

    suspend fun setExtractorConfig(c: ExtractorConfig) {
        context.advancedTuningDataStore.edit {
            it[EXTRACT_MAX_INPUT] = c.maxInputChars
            it[EXTRACT_MAX_TITLE] = c.maxTitleChars
        }
    }

    suspend fun setSmartDefaultsConfig(c: SmartDefaultsConfig) {
        context.advancedTuningDataStore.edit {
            it[SD_MIN_HISTORY] = c.minHistory
            it[SD_GRANULARITY] = c.durationGranularityMinutes
        }
    }

    suspend fun setMorningCheckInPromptCutoff(c: MorningCheckInPromptCutoff) {
        context.advancedTuningDataStore.edit {
            it[MORNING_WINDOW_HOURS] = c.windowHours.coerceIn(1, 24)
        }
    }

    suspend fun setLifeCategoryCustomKeywords(k: LifeCategoryCustomKeywords) {
        context.advancedTuningDataStore.edit {
            it[CK_WORK] = k.work
            it[CK_PERSONAL] = k.personal
            it[CK_SELFCARE] = k.selfCare
            it[CK_HEALTH] = k.health
        }
    }

    suspend fun setTaskModeCustomKeywords(k: TaskModeCustomKeywords) {
        context.advancedTuningDataStore.edit {
            it[MODE_CK_WORK] = k.work
            it[MODE_CK_PLAY] = k.play
            it[MODE_CK_RELAX] = k.relax
        }
    }

    suspend fun setCognitiveLoadCustomKeywords(k: CognitiveLoadCustomKeywords) {
        context.advancedTuningDataStore.edit {
            it[LOAD_CK_EASY] = k.easy
            it[LOAD_CK_MEDIUM] = k.medium
            it[LOAD_CK_HARD] = k.hard
        }
    }

    fun getWeeklySummarySchedule(): Flow<WeeklySummarySchedule> = context.advancedTuningDataStore.data.map {
        WeeklySummarySchedule(
            dayOfWeek = (it[WS_DAY] ?: 7).coerceIn(1, 7),
            taskSummaryHour = (it[WS_TASK_HR] ?: 19).coerceIn(0, 23),
            taskSummaryMinute = (it[WS_TASK_MIN] ?: 30).coerceIn(0, 59),
            habitSummaryHour = (it[WS_HABIT_HR] ?: 19).coerceIn(0, 23),
            habitSummaryMinute = (it[WS_HABIT_MIN] ?: 0).coerceIn(0, 59),
            reviewHour = (it[WS_REVIEW_HR] ?: 20).coerceIn(0, 23),
            reviewMinute = (it[WS_REVIEW_MIN] ?: 0).coerceIn(0, 59),
            eveningSummaryHour = (it[WS_EVENING_HR] ?: 20).coerceIn(0, 23),
            analyticsSummaryHour = (it[WS_ANALYTICS_HR] ?: 19).coerceIn(0, 23),
            analyticsSummaryMinute = (it[WS_ANALYTICS_MIN] ?: 0).coerceIn(0, 59)
        )
    }

    fun getReengagementConfig(): Flow<ReengagementConfig> = context.advancedTuningDataStore.data.map {
        ReengagementConfig(
            absenceDays = (it[RE_ABSENCE_DAYS] ?: 2).coerceAtLeast(1),
            maxNudges = (it[RE_MAX_NUDGES] ?: 1).coerceAtLeast(1)
        )
    }

    fun getOverloadCheckSchedule(): Flow<OverloadCheckSchedule> = context.advancedTuningDataStore.data.map {
        OverloadCheckSchedule(
            hourOfDay = (it[OC_HOUR] ?: 16).coerceIn(0, 23),
            minute = (it[OC_MINUTE] ?: 0).coerceIn(0, 59)
        )
    }

    fun getBatchUndoConfig(): Flow<BatchUndoConfig> = context.advancedTuningDataStore.data.map {
        BatchUndoConfig(
            tailDays = (it[BU_TAIL_DAYS] ?: 7).coerceAtLeast(1)
        )
    }

    fun getHabitReminderFallback(): Flow<HabitReminderFallback> = context.advancedTuningDataStore.data.map {
        HabitReminderFallback(
            hour = (it[HRF_HOUR] ?: 8).coerceIn(0, 23),
            minute = (it[HRF_MINUTE] ?: 0).coerceIn(0, 59)
        )
    }

    fun getApiNetworkConfig(): Flow<ApiNetworkConfig> = context.advancedTuningDataStore.data.map {
        ApiNetworkConfig(
            timeoutSeconds = (it[API_TIMEOUT] ?: 30).coerceAtLeast(5),
            retryAttempts = (it[API_RETRIES] ?: 2).coerceAtLeast(0)
        )
    }

    suspend fun setWeeklySummarySchedule(s: WeeklySummarySchedule) {
        context.advancedTuningDataStore.edit {
            it[WS_DAY] = s.dayOfWeek
            it[WS_TASK_HR] = s.taskSummaryHour
            it[WS_TASK_MIN] = s.taskSummaryMinute
            it[WS_HABIT_HR] = s.habitSummaryHour
            it[WS_HABIT_MIN] = s.habitSummaryMinute
            it[WS_REVIEW_HR] = s.reviewHour
            it[WS_REVIEW_MIN] = s.reviewMinute
            it[WS_EVENING_HR] = s.eveningSummaryHour
            it[WS_ANALYTICS_HR] = s.analyticsSummaryHour
            it[WS_ANALYTICS_MIN] = s.analyticsSummaryMinute
        }
    }

    suspend fun setReengagementConfig(c: ReengagementConfig) {
        context.advancedTuningDataStore.edit {
            it[RE_ABSENCE_DAYS] = c.absenceDays
            it[RE_MAX_NUDGES] = c.maxNudges
        }
    }

    suspend fun setOverloadCheckSchedule(s: OverloadCheckSchedule) {
        context.advancedTuningDataStore.edit {
            it[OC_HOUR] = s.hourOfDay
            it[OC_MINUTE] = s.minute
        }
    }

    suspend fun setBatchUndoConfig(c: BatchUndoConfig) {
        context.advancedTuningDataStore.edit {
            it[BU_TAIL_DAYS] = c.tailDays
        }
    }

    suspend fun setHabitReminderFallback(c: HabitReminderFallback) {
        context.advancedTuningDataStore.edit {
            it[HRF_HOUR] = c.hour
            it[HRF_MINUTE] = c.minute
        }
    }

    suspend fun setApiNetworkConfig(c: ApiNetworkConfig) {
        context.advancedTuningDataStore.edit {
            it[API_TIMEOUT] = c.timeoutSeconds
            it[API_RETRIES] = c.retryAttempts
        }
    }

    fun getWidgetRefreshConfig(): Flow<WidgetRefreshConfig> = context.advancedTuningDataStore.data.map {
        WidgetRefreshConfig(
            intervalMinutes = (it[WIDGET_REFRESH_MINUTES] ?: 15).coerceIn(15, 240)
        )
    }

    suspend fun setWidgetRefreshConfig(c: WidgetRefreshConfig) {
        context.advancedTuningDataStore.edit {
            it[WIDGET_REFRESH_MINUTES] = c.intervalMinutes.coerceIn(15, 240)
        }
    }

    fun getProductivityWidgetThresholds(): Flow<ProductivityWidgetThresholds> =
        context.advancedTuningDataStore.data.map {
            ProductivityWidgetThresholds(
                greenScore = (it[PROD_WIDGET_GREEN] ?: 80).coerceIn(0, 100),
                orangeScore = (it[PROD_WIDGET_ORANGE] ?: 60).coerceIn(0, 100)
            )
        }

    suspend fun setProductivityWidgetThresholds(c: ProductivityWidgetThresholds) {
        context.advancedTuningDataStore.edit {
            it[PROD_WIDGET_GREEN] = c.greenScore.coerceIn(0, 100)
            it[PROD_WIDGET_ORANGE] = c.orangeScore.coerceIn(0, 100)
        }
    }

    fun getEditorFieldRows(): Flow<EditorFieldRows> = context.advancedTuningDataStore.data.map {
        EditorFieldRows(
            descriptionRows = (it[EDITOR_DESCRIPTION_ROWS] ?: 5).coerceIn(1, 50),
            notesRows = (it[EDITOR_NOTES_ROWS] ?: 8).coerceIn(1, 50)
        )
    }

    suspend fun setEditorFieldRows(c: EditorFieldRows) {
        context.advancedTuningDataStore.edit {
            it[EDITOR_DESCRIPTION_ROWS] = c.descriptionRows.coerceIn(1, 50)
            it[EDITOR_NOTES_ROWS] = c.notesRows.coerceIn(1, 50)
        }
    }

    fun getQuickAddRows(): Flow<QuickAddRows> = context.advancedTuningDataStore.data.map {
        QuickAddRows(
            maxLines = (it[QUICKADD_MAX_LINES] ?: 5).coerceIn(1, 50)
        )
    }

    suspend fun setQuickAddRows(c: QuickAddRows) {
        context.advancedTuningDataStore.edit {
            it[QUICKADD_MAX_LINES] = c.maxLines.coerceIn(1, 50)
        }
    }

    fun getSearchPreview(): Flow<SearchPreview> = context.advancedTuningDataStore.data.map {
        SearchPreview(
            previewLines = (it[SEARCH_PREVIEW_LINES] ?: 2).coerceIn(1, 10)
        )
    }

    suspend fun setSearchPreview(c: SearchPreview) {
        context.advancedTuningDataStore.edit {
            it[SEARCH_PREVIEW_LINES] = c.previewLines.coerceIn(1, 10)
        }
    }

    fun getHabitBorderBrightness(): Flow<HabitBorderBrightness> =
        context.advancedTuningDataStore.data.map {
            HabitBorderBrightness(
                brightness = (it[HABIT_BORDER_BRIGHTNESS] ?: 0.4f).coerceIn(0f, 1f)
            )
        }

    suspend fun setHabitBorderBrightness(c: HabitBorderBrightness) {
        context.advancedTuningDataStore.edit {
            it[HABIT_BORDER_BRIGHTNESS] = c.brightness.coerceIn(0f, 1f)
        }
    }

    fun getSelfCareTierDefaults(): Flow<SelfCareTierDefaults> =
        context.advancedTuningDataStore.data.map {
            SelfCareTierDefaults(
                morning = it[SC_DEFAULT_MORNING] ?: "solid",
                bedtime = it[SC_DEFAULT_BEDTIME] ?: "solid",
                medication = it[SC_DEFAULT_MEDICATION] ?: "prescription",
                housework = it[SC_DEFAULT_HOUSEWORK] ?: "regular"
            )
        }

    suspend fun setSelfCareTierDefaults(d: SelfCareTierDefaults) {
        context.advancedTuningDataStore.edit {
            it[SC_DEFAULT_MORNING] = d.morning
            it[SC_DEFAULT_BEDTIME] = d.bedtime
            it[SC_DEFAULT_MEDICATION] = d.medication
            it[SC_DEFAULT_HOUSEWORK] = d.housework
        }
    }
}
