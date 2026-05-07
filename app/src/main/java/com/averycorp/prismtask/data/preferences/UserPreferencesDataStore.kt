package com.averycorp.prismtask.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.averycorp.prismtask.domain.model.AutoDueDate
import com.averycorp.prismtask.domain.model.StartOfWeek
import com.averycorp.prismtask.domain.model.SwipeAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * Appearance/display preferences used by v1.3.0 customizability features.
 */
data class AppearancePrefs(
    val compactMode: Boolean = false,
    val showTaskCardBorders: Boolean = true,
    val cardCornerRadius: Int = 12
)

/**
 * Swipe gesture preferences for task cards.
 */
data class SwipePrefs(
    val right: SwipeAction = SwipeAction.COMPLETE,
    val left: SwipeAction = SwipeAction.DELETE
)

/**
 * Default values applied when creating a new task.
 */
data class TaskDefaults(
    val defaultPriority: Int = 0,
    val defaultReminderOffset: Long = -1L,
    val defaultProjectId: Long? = null,
    val startOfWeek: StartOfWeek = StartOfWeek.MONDAY,
    val defaultDuration: Int? = null,
    val autoSetDueDate: AutoDueDate = AutoDueDate.NONE,
    val smartDefaultsEnabled: Boolean = false
)

/**
 * Preferences for the quick-add bar.
 */
data class QuickAddPrefs(
    val showConfirmation: Boolean = true,
    val autoAssignProject: Boolean = false
)

/**
 * Forgiveness-first streak preferences (v1.4.0 V5).
 *
 * When [enabled], [StreakCalculator.calculateResilientStreak] tolerates up to
 * [allowedMisses] missed days within a rolling [gracePeriodDays] window before
 * resetting the streak. When disabled, streak calculation reverts to classic
 * strict behavior (a single miss hard-resets the run).
 */
data class ForgivenessPrefs(
    val enabled: Boolean = true,
    val gracePeriodDays: Int = 7,
    val allowedMisses: Int = 1
)

/**
 * Work-Life Balance Engine preferences (v1.4.0 V1).
 *
 * Target ratios are stored as Int percentages (0..100) and should sum to 100.
 * Classifier auto-classification is always on — the keyword classifier runs
 * on every task creation path via `TaskRepository.resolveLifeCategoryForInsert`.
 * A classifier miss resolves to `LifeCategory.UNCATEGORIZED`, not null.
 * [showBalanceBar] toggles the Today screen balance bar visibility.
 */
data class WorkLifeBalancePrefs(
    val workTarget: Int = 40,
    val personalTarget: Int = 25,
    val selfCareTarget: Int = 20,
    val healthTarget: Int = 15,
    val showBalanceBar: Boolean = true,
    val overloadThresholdPct: Int = 10
) {
    /** Whether the four target percentages sum to 100 (allowing ±1 for rounding). */
    fun isValid(): Boolean {
        val sum = workTarget + personalTarget + selfCareTarget + healthTarget
        return sum in 99..101
    }
}

/**
 * Aggregated snapshot of all user preferences for a single point in time.
 * Used primarily by DataExporter/DataImporter and by the Settings screen.
 */
data class UserPreferencesSnapshot(
    val appearance: AppearancePrefs = AppearancePrefs(),
    val swipe: SwipePrefs = SwipePrefs(),
    val taskDefaults: TaskDefaults = TaskDefaults(),
    val quickAdd: QuickAddPrefs = QuickAddPrefs(),
    val workLifeBalance: WorkLifeBalancePrefs = WorkLifeBalancePrefs(),
    val eisenhower: EisenhowerPrefs = EisenhowerPrefs()
)

/**
 * Eisenhower matrix auto-classification preferences (v1.4.x A2).
 *
 * When [autoClassifyEnabled] is true, the client fires a fire-and-forget
 * classification against the backend after every successful task create.
 * Failures (offline, rate-limit, 5xx) leave the task's quadrant unchanged;
 * the user can explicitly re-run classification from the Matrix screen.
 */
data class EisenhowerPrefs(
    val autoClassifyEnabled: Boolean = true
)

/**
 * Master AI-feature opt-out (PII egress audit, 2026-04-26).
 *
 * When [enabled] is false, the Android client must short-circuit every AI
 * surface and never call the backend's Anthropic-touching endpoints
 * (the `/ai/...` family plus `/tasks/parse...` and `/syllabus/parse`).
 * The client should also send `X-PrismTask-AI-Features: disabled` on any
 * request it does make, so the backend's `require_ai_features_enabled`
 * middleware can reject the request with HTTP 451 — defense-in-depth in
 * case a stale code path forgot to check the local flag.
 *
 * Defaults to true so existing Pro users do not lose AI features on
 * upgrade. The disclosure / opt-out path is documented in
 * `docs/privacy/index.md` and `docs/store-listing/compliance/data-safety-form.md`.
 */
data class AiFeaturePrefs(
    val enabled: Boolean = true
)

/**
 * Global default for medication reminder mode (v1.6.0). Per-slot and
 * per-medication overrides on `MedicationSlotEntity` / `MedicationEntity`
 * win over this default when set; resolution lives in
 * `MedicationReminderModeResolver` (PR2).
 *
 * [intervalDefaultMinutes] is only consulted when the resolved mode is
 * INTERVAL. Default of 240 (4 hours) is the typical between-dose window
 * for most medications; the UI clamps to 60..1440.
 */
data class MedicationReminderModePrefs(
    val mode: MedicationReminderMode = MedicationReminderMode.CLOCK,
    val intervalDefaultMinutes: Int = 240
)

enum class MedicationReminderMode {
    CLOCK,
    INTERVAL;

    companion object {
        fun fromName(name: String?): MedicationReminderMode = when (name) {
            INTERVAL.name -> INTERVAL
            else -> CLOCK
        }
    }
}

/**
 * Centralized DataStore for v1.3.0 customization preferences. This sits alongside the
 * existing preference classes (ThemePreferences, TaskBehaviorPreferences, etc.) and
 * holds only the new keys introduced by the customizability track.
 *
 * Takes a [DataStore] in its constructor so it can be unit-tested without an Android
 * Context; production wiring lives in
 * [com.averycorp.prismtask.di.PreferencesModule].
 */
class UserPreferencesDataStore(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        // Appearance
        val KEY_COMPACT_MODE = booleanPreferencesKey("compact_mode")
        val KEY_SHOW_CARD_BORDERS = booleanPreferencesKey("show_card_borders")
        val KEY_CARD_CORNER_RADIUS = intPreferencesKey("card_corner_radius")

        // Swipe actions
        val KEY_SWIPE_RIGHT = stringPreferencesKey("swipe_right_action")
        val KEY_SWIPE_LEFT = stringPreferencesKey("swipe_left_action")

        // Task defaults
        val KEY_DEFAULT_PRIORITY = intPreferencesKey("default_priority")
        val KEY_DEFAULT_REMINDER_OFFSET = longPreferencesKey("default_reminder_offset")
        val KEY_DEFAULT_PROJECT_ID = longPreferencesKey("default_project_id")
        val KEY_START_OF_WEEK = stringPreferencesKey("start_of_week")
        val KEY_DEFAULT_DURATION = intPreferencesKey("default_duration")
        val KEY_AUTO_SET_DUE_DATE = stringPreferencesKey("auto_set_due_date")
        val KEY_SMART_DEFAULTS = booleanPreferencesKey("smart_defaults_enabled")

        // Quick-add
        val KEY_QUICK_ADD_CONFIRM = booleanPreferencesKey("quick_add_show_confirmation")
        val KEY_QUICK_ADD_AUTO_PROJECT = booleanPreferencesKey("quick_add_auto_assign_project")

        // Task menu actions config (JSON-encoded)
        val KEY_TASK_MENU_ACTIONS = stringPreferencesKey("task_menu_actions_json")

        // Task card display config (JSON-encoded)
        val KEY_TASK_CARD_DISPLAY = stringPreferencesKey("task_card_display_json")

        // Forgiveness-first streaks (v1.4.0 V5)
        val KEY_FORGIVENESS_ENABLED = booleanPreferencesKey("forgiveness_enabled")
        val KEY_FORGIVENESS_GRACE_DAYS = intPreferencesKey("forgiveness_grace_days")
        val KEY_FORGIVENESS_ALLOWED_MISSES = intPreferencesKey("forgiveness_allowed_misses")

        // Work-Life Balance (v1.4.0 V1)
        val KEY_WLB_WORK_TARGET = intPreferencesKey("wlb_work_target")
        val KEY_WLB_PERSONAL_TARGET = intPreferencesKey("wlb_personal_target")
        val KEY_WLB_SELFCARE_TARGET = intPreferencesKey("wlb_selfcare_target")
        val KEY_WLB_HEALTH_TARGET = intPreferencesKey("wlb_health_target")
        val KEY_WLB_SHOW_BAR = booleanPreferencesKey("wlb_show_bar")
        val KEY_WLB_OVERLOAD_THRESHOLD = intPreferencesKey("wlb_overload_threshold")

        // Eisenhower auto-classification (v1.4.x A2)
        val KEY_EISENHOWER_AUTO_CLASSIFY = booleanPreferencesKey("eisenhower_auto_classify")

        // Master AI-feature opt-out (PII egress audit, 2026-04-26)
        val KEY_AI_FEATURES_ENABLED = booleanPreferencesKey("ai_features_enabled")

        // First-run AI chat disclosure (CHAT_QUALITY_AUDIT C.1, Phase 2 #2).
        // Set to true the first time the user dismisses the disclosure
        // dialog so it is not shown again on subsequent chat opens.
        //
        // V1 is intentionally retained (not deleted) so back-revved
        // clients don't crash on the missing key. The V2 bump below
        // (F8_CHAT_PRIVACY_DOC_UPDATE_AUDIT § A.5 Option A) re-fires
        // the dialog once for existing users when the disclosure copy
        // is materially expanded.
        val KEY_AI_CHAT_DISCLOSURE_SHOWN = booleanPreferencesKey("ai_chat_disclosure_shown")

        // F8 chat privacy doc update — V2 bump fires the disclosure
        // dialog once for every user (new + existing) so the new copy
        // enumerating the task-context snapshot fields and rolling
        // conversation history is acknowledged.
        val KEY_AI_CHAT_DISCLOSURE_SHOWN_V2 =
            booleanPreferencesKey("ai_chat_disclosure_shown_v2")

        // Medication reminder mode global default (v1.6.0)
        val KEY_MED_REMINDER_MODE_DEFAULT = stringPreferencesKey("med_reminder_mode_default")
        val KEY_MED_REMINDER_INTERVAL_DEFAULT_MINUTES =
            intPreferencesKey("med_reminder_interval_default_minutes")

        private const val DEFAULT_PROJECT_NULL_SENTINEL: Long = -1L

        /** Inclusive bounds for any user-entered interval-minutes value. */
        const val MED_REMINDER_INTERVAL_MIN_MINUTES: Int = 60
        const val MED_REMINDER_INTERVAL_MAX_MINUTES: Int = 1440
    }

    // region Flows ---------------------------------------------------------

    val appearanceFlow: Flow<AppearancePrefs> = dataStore.data.map { prefs ->
        AppearancePrefs(
            compactMode = prefs[KEY_COMPACT_MODE] ?: false,
            showTaskCardBorders = prefs[KEY_SHOW_CARD_BORDERS] ?: true,
            cardCornerRadius = (prefs[KEY_CARD_CORNER_RADIUS] ?: 12).coerceIn(0, 24)
        )
    }

    val swipeFlow: Flow<SwipePrefs> = dataStore.data.map { prefs ->
        SwipePrefs(
            right = SwipeAction.fromName(prefs[KEY_SWIPE_RIGHT]),
            left = SwipeAction.fromName(prefs[KEY_SWIPE_LEFT] ?: SwipeAction.DELETE.name)
        )
    }

    val taskDefaultsFlow: Flow<TaskDefaults> = dataStore.data.map { prefs ->
        val rawProjectId = prefs[KEY_DEFAULT_PROJECT_ID]
        TaskDefaults(
            defaultPriority = (prefs[KEY_DEFAULT_PRIORITY] ?: 0).coerceIn(0, 4),
            defaultReminderOffset = prefs[KEY_DEFAULT_REMINDER_OFFSET] ?: -1L,
            defaultProjectId = if (rawProjectId == null || rawProjectId == DEFAULT_PROJECT_NULL_SENTINEL) null else rawProjectId,
            startOfWeek = StartOfWeek.fromName(prefs[KEY_START_OF_WEEK]),
            defaultDuration = prefs[KEY_DEFAULT_DURATION]?.takeIf { it > 0 },
            autoSetDueDate = AutoDueDate.fromName(prefs[KEY_AUTO_SET_DUE_DATE]),
            smartDefaultsEnabled = prefs[KEY_SMART_DEFAULTS] ?: false
        )
    }

    val quickAddFlow: Flow<QuickAddPrefs> = dataStore.data.map { prefs ->
        QuickAddPrefs(
            showConfirmation = prefs[KEY_QUICK_ADD_CONFIRM] ?: true,
            autoAssignProject = prefs[KEY_QUICK_ADD_AUTO_PROJECT] ?: false
        )
    }

    val taskMenuActionsFlow: Flow<List<com.averycorp.prismtask.domain.model.TaskMenuAction>> =
        dataStore.data.map { prefs ->
            val json = prefs[KEY_TASK_MENU_ACTIONS]
            if (json.isNullOrBlank()) {
                com.averycorp.prismtask.domain.model.TaskMenuAction
                    .defaults()
            } else {
                try {
                    val listType = com.google.gson.reflect.TypeToken
                        .getParameterized(List::class.java, com.averycorp.prismtask.domain.model.TaskMenuAction::class.java)
                        .type
                    val parsed: List<com.averycorp.prismtask.domain.model.TaskMenuAction> =
                        com.google.gson
                            .Gson()
                            .fromJson(json, listType)
                    com.averycorp.prismtask.domain.model.TaskMenuAction
                        .mergeWithDefaults(parsed)
                } catch (_: Exception) {
                    com.averycorp.prismtask.domain.model.TaskMenuAction
                        .defaults()
                }
            }
        }

    suspend fun setTaskMenuActions(actions: List<com.averycorp.prismtask.domain.model.TaskMenuAction>) {
        val json = com.google.gson
            .Gson()
            .toJson(actions)
        dataStore.edit { it[KEY_TASK_MENU_ACTIONS] = json }
    }

    val taskCardDisplayFlow: Flow<com.averycorp.prismtask.domain.model.TaskCardDisplayConfig> =
        dataStore.data.map { prefs ->
            val json = prefs[KEY_TASK_CARD_DISPLAY]
            if (json.isNullOrBlank()) {
                com.averycorp.prismtask.domain.model
                    .TaskCardDisplayConfig()
            } else {
                try {
                    com.google.gson
                        .Gson()
                        .fromJson(json, com.averycorp.prismtask.domain.model.TaskCardDisplayConfig::class.java)
                        ?.withClampedTagLimit()
                        ?: com.averycorp.prismtask.domain.model
                            .TaskCardDisplayConfig()
                } catch (_: Exception) {
                    com.averycorp.prismtask.domain.model
                        .TaskCardDisplayConfig()
                }
            }
        }

    suspend fun setTaskCardDisplay(config: com.averycorp.prismtask.domain.model.TaskCardDisplayConfig) {
        val clamped = config.withClampedTagLimit()
        val json = com.google.gson
            .Gson()
            .toJson(clamped)
        dataStore.edit { it[KEY_TASK_CARD_DISPLAY] = json }
    }

    val forgivenessFlow: Flow<ForgivenessPrefs> = dataStore.data.map { prefs ->
        ForgivenessPrefs(
            enabled = prefs[KEY_FORGIVENESS_ENABLED] ?: true,
            gracePeriodDays = (prefs[KEY_FORGIVENESS_GRACE_DAYS] ?: 7).coerceIn(1, 30),
            allowedMisses = (prefs[KEY_FORGIVENESS_ALLOWED_MISSES] ?: 1).coerceIn(0, 5)
        )
    }

    suspend fun setForgivenessPrefs(prefs: ForgivenessPrefs) {
        dataStore.edit {
            it[KEY_FORGIVENESS_ENABLED] = prefs.enabled
            it[KEY_FORGIVENESS_GRACE_DAYS] = prefs.gracePeriodDays.coerceIn(1, 30)
            it[KEY_FORGIVENESS_ALLOWED_MISSES] = prefs.allowedMisses.coerceIn(0, 5)
        }
    }

    val workLifeBalanceFlow: Flow<WorkLifeBalancePrefs> = dataStore.data.map { prefs ->
        WorkLifeBalancePrefs(
            workTarget = (prefs[KEY_WLB_WORK_TARGET] ?: 40).coerceIn(0, 100),
            personalTarget = (prefs[KEY_WLB_PERSONAL_TARGET] ?: 25).coerceIn(0, 100),
            selfCareTarget = (prefs[KEY_WLB_SELFCARE_TARGET] ?: 20).coerceIn(0, 100),
            healthTarget = (prefs[KEY_WLB_HEALTH_TARGET] ?: 15).coerceIn(0, 100),
            showBalanceBar = prefs[KEY_WLB_SHOW_BAR] ?: true,
            overloadThresholdPct = (prefs[KEY_WLB_OVERLOAD_THRESHOLD] ?: 10).coerceIn(5, 25)
        )
    }

    val eisenhowerFlow: Flow<EisenhowerPrefs> = dataStore.data.map { prefs ->
        EisenhowerPrefs(
            autoClassifyEnabled = prefs[KEY_EISENHOWER_AUTO_CLASSIFY] ?: true
        )
    }

    val aiFeaturePrefsFlow: Flow<AiFeaturePrefs> = dataStore.data.map { prefs ->
        AiFeaturePrefs(
            enabled = prefs[KEY_AI_FEATURES_ENABLED] ?: true
        )
    }

    val medicationReminderModeFlow: Flow<MedicationReminderModePrefs> = dataStore.data.map { prefs ->
        MedicationReminderModePrefs(
            mode = MedicationReminderMode.fromName(prefs[KEY_MED_REMINDER_MODE_DEFAULT]),
            intervalDefaultMinutes = (prefs[KEY_MED_REMINDER_INTERVAL_DEFAULT_MINUTES] ?: 240)
                .coerceIn(MED_REMINDER_INTERVAL_MIN_MINUTES, MED_REMINDER_INTERVAL_MAX_MINUTES)
        )
    }

    /** Combined flow emitting the full preferences bundle. */
    val allFlow: Flow<UserPreferencesSnapshot> = combine(
        appearanceFlow,
        swipeFlow,
        taskDefaultsFlow,
        quickAddFlow,
        workLifeBalanceFlow,
        eisenhowerFlow
    ) { flows ->
        UserPreferencesSnapshot(
            appearance = flows[0] as AppearancePrefs,
            swipe = flows[1] as SwipePrefs,
            taskDefaults = flows[2] as TaskDefaults,
            quickAdd = flows[3] as QuickAddPrefs,
            workLifeBalance = flows[4] as WorkLifeBalancePrefs,
            eisenhower = flows[5] as EisenhowerPrefs
        )
    }

    // endregion

    // region Setters -------------------------------------------------------

    suspend fun setAppearance(prefs: AppearancePrefs) {
        dataStore.edit {
            it[KEY_COMPACT_MODE] = prefs.compactMode
            it[KEY_SHOW_CARD_BORDERS] = prefs.showTaskCardBorders
            it[KEY_CARD_CORNER_RADIUS] = prefs.cardCornerRadius.coerceIn(0, 24)
        }
    }

    suspend fun setCompactMode(enabled: Boolean) {
        dataStore.edit { it[KEY_COMPACT_MODE] = enabled }
    }

    suspend fun setShowCardBorders(enabled: Boolean) {
        dataStore.edit { it[KEY_SHOW_CARD_BORDERS] = enabled }
    }

    suspend fun setCardCornerRadius(radius: Int) {
        dataStore.edit { it[KEY_CARD_CORNER_RADIUS] = radius.coerceIn(0, 24) }
    }

    suspend fun setSwipe(prefs: SwipePrefs) {
        dataStore.edit {
            it[KEY_SWIPE_RIGHT] = prefs.right.name
            it[KEY_SWIPE_LEFT] = prefs.left.name
        }
    }

    suspend fun setSwipeRight(action: SwipeAction) {
        dataStore.edit { it[KEY_SWIPE_RIGHT] = action.name }
    }

    suspend fun setSwipeLeft(action: SwipeAction) {
        dataStore.edit { it[KEY_SWIPE_LEFT] = action.name }
    }

    suspend fun setTaskDefaults(defaults: TaskDefaults) {
        dataStore.edit {
            it[KEY_DEFAULT_PRIORITY] = defaults.defaultPriority.coerceIn(0, 4)
            it[KEY_DEFAULT_REMINDER_OFFSET] = defaults.defaultReminderOffset
            it[KEY_DEFAULT_PROJECT_ID] = defaults.defaultProjectId ?: DEFAULT_PROJECT_NULL_SENTINEL
            it[KEY_START_OF_WEEK] = defaults.startOfWeek.name
            it[KEY_DEFAULT_DURATION] = defaults.defaultDuration ?: -1
            it[KEY_AUTO_SET_DUE_DATE] = defaults.autoSetDueDate.name
            it[KEY_SMART_DEFAULTS] = defaults.smartDefaultsEnabled
        }
    }

    suspend fun setSmartDefaultsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_SMART_DEFAULTS] = enabled }
    }

    suspend fun setQuickAdd(prefs: QuickAddPrefs) {
        dataStore.edit {
            it[KEY_QUICK_ADD_CONFIRM] = prefs.showConfirmation
            it[KEY_QUICK_ADD_AUTO_PROJECT] = prefs.autoAssignProject
        }
    }

    /**
     * Save a new Work-Life Balance configuration. Values that don't sum to 100
     * are stored as-is; the UI is responsible for validation. The overload
     * threshold is clamped to a sane range.
     */
    suspend fun setWorkLifeBalance(prefs: WorkLifeBalancePrefs) {
        dataStore.edit {
            it[KEY_WLB_WORK_TARGET] = prefs.workTarget.coerceIn(0, 100)
            it[KEY_WLB_PERSONAL_TARGET] = prefs.personalTarget.coerceIn(0, 100)
            it[KEY_WLB_SELFCARE_TARGET] = prefs.selfCareTarget.coerceIn(0, 100)
            it[KEY_WLB_HEALTH_TARGET] = prefs.healthTarget.coerceIn(0, 100)
            it[KEY_WLB_SHOW_BAR] = prefs.showBalanceBar
            it[KEY_WLB_OVERLOAD_THRESHOLD] = prefs.overloadThresholdPct.coerceIn(5, 25)
        }
    }

    suspend fun setShowBalanceBar(enabled: Boolean) {
        dataStore.edit { it[KEY_WLB_SHOW_BAR] = enabled }
    }

    suspend fun setEisenhowerAutoClassifyEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_EISENHOWER_AUTO_CLASSIFY] = enabled }
    }

    suspend fun setAiFeaturesEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_AI_FEATURES_ENABLED] = enabled }
    }

    /**
     * Whether the first-run AI chat disclosure has been acknowledged.
     * Defaults to false so the dialog fires on the first chat open.
     *
     * V1 is retained for back-revved clients. V2 (below) is the
     * load-bearing flag the chat surface checks today.
     */
    val aiChatDisclosureShownFlow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_AI_CHAT_DISCLOSURE_SHOWN] ?: false
    }

    suspend fun setAiChatDisclosureShown(shown: Boolean) {
        dataStore.edit { it[KEY_AI_CHAT_DISCLOSURE_SHOWN] = shown }
    }

    /**
     * V2 of the first-run AI chat disclosure (F8 chat privacy doc
     * update). Defaults to false so the dialog fires once for every
     * user — including users who already dismissed V1 — after the
     * disclosure copy was materially expanded to enumerate the
     * task-context snapshot fields and rolling conversation history.
     */
    val aiChatDisclosureShownV2Flow: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_AI_CHAT_DISCLOSURE_SHOWN_V2] ?: false
    }

    suspend fun setAiChatDisclosureShownV2(shown: Boolean) {
        dataStore.edit { it[KEY_AI_CHAT_DISCLOSURE_SHOWN_V2] = shown }
    }

    /**
     * Synchronous read of the AI-features flag, intended for OkHttp
     * interceptors (which must run on the calling thread). Mirrors the
     * pattern used by `AuthTokenPreferences.getAccessTokenBlocking`.
     */
    fun isAiFeaturesEnabledBlocking(): Boolean = runBlocking {
        aiFeaturePrefsFlow.first().enabled
    }

    suspend fun setMedicationReminderMode(prefs: MedicationReminderModePrefs) {
        dataStore.edit {
            it[KEY_MED_REMINDER_MODE_DEFAULT] = prefs.mode.name
            it[KEY_MED_REMINDER_INTERVAL_DEFAULT_MINUTES] = prefs.intervalDefaultMinutes
                .coerceIn(MED_REMINDER_INTERVAL_MIN_MINUTES, MED_REMINDER_INTERVAL_MAX_MINUTES)
        }
    }

    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }

    // endregion
}
