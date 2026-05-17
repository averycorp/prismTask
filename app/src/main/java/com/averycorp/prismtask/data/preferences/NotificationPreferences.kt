package com.averycorp.prismtask.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal val Context.notificationDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "notification_prefs"
)

/**
 * Notification-delivery types whose channel sound and vibration the user
 * can override per-type. Only the three classes whose channels route
 * through [com.averycorp.prismtask.notifications.NotificationHelper.Style]
 * are exposed today; other workers create their own channels and would
 * need separate plumbing to respect these overrides.
 */
enum class NotificationOverrideType {
    TASK_REMINDERS,
    TIMER_ALERTS,
    MEDICATION_REMINDERS
}

/**
 * Effective per-type override pair resolved by
 * [NotificationPreferences.getOverridesForOnce]. When the user has the
 * type set to "follow phone settings", both fields are false — the
 * channel then uses Android's defaults instead of the alarm stream and
 * the long buzz pattern.
 */
data class NotificationOverrides(val loud: Boolean, val repeat: Boolean)

/**
 * Persists user-controlled notification settings:
 *  - per-type enable/disable flags for every notification surface,
 *  - a global importance/intrusiveness setting that maps to channel
 *    importance + builder priority,
 *  - a default reminder lead-time used to pre-fill `reminderOffset` on
 *    newly-created tasks.
 *
 * Takes a [DataStore] directly so it can be unit-tested without an Android
 * Context; production wiring lives in
 * [com.averycorp.prismtask.di.PreferencesModule]. The [from] factory exists
 * for non-Hilt callers like [com.averycorp.prismtask.notifications.NotificationHelper]
 * that only have a [Context] on hand.
 *
 * Everything is read/written via [Flow]/`suspend` setters — callers
 * (workers, the notification helper, ViewModels) read with `.first()`
 * before posting, or collect the flow when they need live updates.
 */
class NotificationPreferences(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        // Per-type enable flags (default true)
        private val TASK_REMINDERS_ENABLED = booleanPreferencesKey("task_reminders_enabled")
        private val TIMER_ALERTS_ENABLED = booleanPreferencesKey("timer_alerts_enabled")
        private val MEDICATION_REMINDERS_ENABLED = booleanPreferencesKey("medication_reminders_enabled")
        private val DAILY_BRIEFING_ENABLED = booleanPreferencesKey("daily_briefing_enabled")
        private val EVENING_SUMMARY_ENABLED = booleanPreferencesKey("evening_summary_enabled")
        private val WEEKLY_SUMMARY_ENABLED = booleanPreferencesKey("weekly_summary_enabled")
        private val WEEKLY_TASK_SUMMARY_ENABLED =
            booleanPreferencesKey("weekly_task_summary_enabled")
        private val OVERLOAD_ALERTS_ENABLED = booleanPreferencesKey("overload_alerts_enabled")
        private val REENGAGEMENT_ENABLED = booleanPreferencesKey("reengagement_enabled")

        // Full-screen heads-up reminder (opens in full-screen over lock screen)
        private val FULL_SCREEN_NOTIFICATIONS_ENABLED = booleanPreferencesKey("full_screen_notifications_enabled")

        // Play reminders at alarm volume (bypasses silent ringer).
        // Legacy global — superseded by the per-type [perTypeVolumeLoud]
        // family, which falls back to this key so existing users don't
        // lose behavior on upgrade.
        private val OVERRIDE_VOLUME_ENABLED = booleanPreferencesKey("override_volume_enabled")

        // Use a long, repeating vibration pattern for reminders.
        // Legacy global — superseded by the per-type [perTypeVibrationRepeat]
        // family; same fallback story as [OVERRIDE_VOLUME_ENABLED].
        private val REPEATING_VIBRATION_ENABLED = booleanPreferencesKey("repeating_vibration_enabled")

        // ---- v1.9.x per-type volume / vibration override ----
        //
        // Three booleans per delivery type drive a "follow phone settings"
        // switch plus two app-controlled override flags. When
        // `<type>_follow_system` is true the notification ignores the app
        // overrides (channel uses the system defaults the user configures
        // in Android Settings). When false, the loud/repeat flags decide
        // whether to route through the alarm stream and use the long buzz
        // pattern, respectively. Missing keys fall back to the legacy
        // [OVERRIDE_VOLUME_ENABLED]/[REPEATING_VIBRATION_ENABLED] globals
        // so an upgrading user inherits their previous behavior until they
        // explicitly touch a per-type control.
        private val TASK_FOLLOW_SYSTEM = booleanPreferencesKey("task_reminders_follow_system")
        private val TASK_VOLUME_LOUD = booleanPreferencesKey("task_reminders_volume_loud")
        private val TASK_VIBRATION_REPEAT = booleanPreferencesKey("task_reminders_vibration_repeat")

        private val TIMER_FOLLOW_SYSTEM = booleanPreferencesKey("timer_alerts_follow_system")
        private val TIMER_VOLUME_LOUD = booleanPreferencesKey("timer_alerts_volume_loud")
        private val TIMER_VIBRATION_REPEAT = booleanPreferencesKey("timer_alerts_vibration_repeat")

        private val MED_FOLLOW_SYSTEM = booleanPreferencesKey("medication_reminders_follow_system")
        private val MED_VOLUME_LOUD = booleanPreferencesKey("medication_reminders_volume_loud")
        private val MED_VIBRATION_REPEAT = booleanPreferencesKey("medication_reminders_vibration_repeat")

        // Importance / intrusiveness
        private val NOTIFICATION_IMPORTANCE = stringPreferencesKey("notification_importance")

        /**
         * Tracks the importance level the channel was *last* created with so
         * the next change can delete the stale channel before creating the
         * new one. Channel importance is immutable after creation, so we use
         * a per-importance suffix on the channel ID.
         */
        private val PREVIOUS_IMPORTANCE = stringPreferencesKey("previous_importance")

        // Default reminder lead time (millis before due date)
        private val DEFAULT_REMINDER_OFFSET = longPreferencesKey("default_reminder_offset")

        // ---- v1.4.0 Notifications Overhaul additions ----

        // Active profile ID (points at notification_profiles.id). -1 = built-in fallback.
        private val ACTIVE_PROFILE_ID = longPreferencesKey("active_notification_profile_id")

        // Per-category profile overrides; stored as "<category_key>=<profile_id>" strings.
        private val CATEGORY_PROFILE_OVERRIDES =
            stringSetPreferencesKey("category_profile_overrides")

        // Streak & gamification alerts
        private val STREAK_ALERTS_ENABLED = booleanPreferencesKey("streak_alerts_enabled")
        private val STREAK_AT_RISK_LEAD_HOURS = intPreferencesKey("streak_at_risk_lead_hours")

        // Daily briefing customization
        private val BRIEFING_MORNING_HOUR = intPreferencesKey("briefing_morning_hour")
        private val BRIEFING_MIDDAY_ENABLED = booleanPreferencesKey("briefing_midday_enabled")
        private val BRIEFING_EVENING_HOUR = intPreferencesKey("briefing_evening_hour")
        private val BRIEFING_TONE = stringPreferencesKey("briefing_tone")
        private val BRIEFING_SECTIONS = stringSetPreferencesKey("briefing_sections")
        private val BRIEFING_READ_ALOUD = booleanPreferencesKey("briefing_read_aloud")

        // Collaborator notifications
        private val COLLAB_DIGEST_MODE = stringPreferencesKey("collab_digest_mode")
        private val COLLAB_ASSIGNED_ENABLED = booleanPreferencesKey("collab_assigned_enabled")
        private val COLLAB_MENTIONED_ENABLED = booleanPreferencesKey("collab_mentioned_enabled")
        private val COLLAB_STATUS_ENABLED = booleanPreferencesKey("collab_status_enabled")
        private val COLLAB_COMMENT_ENABLED = booleanPreferencesKey("collab_comment_enabled")
        private val COLLAB_DUE_SOON_ENABLED = booleanPreferencesKey("collab_due_soon_enabled")

        // Smartwatch
        private val WATCH_SYNC_MODE = stringPreferencesKey("watch_sync_mode")
        private val WATCH_VOLUME_PERCENT = intPreferencesKey("watch_volume_percent")
        private val WATCH_HAPTIC_INTENSITY = stringPreferencesKey("watch_haptic_intensity")

        // Visual badge / toast (cross-platform: desktop/web honor toast pos)
        private val BADGE_MODE = stringPreferencesKey("badge_mode")
        private val TOAST_POSITION = stringPreferencesKey("toast_position")
        private val HIGH_CONTRAST_NOTIFICATIONS =
            booleanPreferencesKey("high_contrast_notifications")

        // Habit nag suppression: suppress habit nag if next occurrence is within N days (0 = disabled)
        private val HABIT_NAG_SUPPRESSION_DAYS = intPreferencesKey("habit_nag_suppression_days")

        // Snooze options (CSV of minute integers)
        private val SNOOZE_DURATIONS_CSV = stringPreferencesKey("snooze_durations_csv")

        // One-time flag for the v1.4.0 WeeklySummaryWorker ->
        // WeeklyHabitSummaryWorker rename migration. Set to true after
        // the scheduler cancels the stale pre-rename unique work so
        // re-enqueue under the new class can bind cleanly.
        private val WEEKLY_HABIT_SUMMARY_MIGRATION_RUN =
            booleanPreferencesKey("weekly_habit_summary_migration_run_v14")

        // Auto-generated weekly reviews (A2): a worker aggregates the
        // past week on Sunday evening and, for Pro users, asks the
        // backend AI for narrative insights.
        private val WEEKLY_REVIEW_AUTO_GENERATE_ENABLED =
            booleanPreferencesKey("weekly_review_auto_generate_enabled")
        private val WEEKLY_REVIEW_NOTIFICATION_ENABLED =
            booleanPreferencesKey("weekly_review_notification_enabled")
        private val WEEKLY_REVIEW_WORKER_SEEDED =
            booleanPreferencesKey("weekly_review_worker_seeded_v14")

        // Weekly analytics roll-up notification (Phase I). Fires Sunday
        // evening with a one-line score + completed-task + habit-rate
        // summary so the user has a passive weekly health check
        // without needing to open the dashboard.
        private val WEEKLY_ANALYTICS_NOTIFICATION_ENABLED =
            booleanPreferencesKey("weekly_analytics_notification_enabled")
        private val WEEKLY_ANALYTICS_WORKER_SEEDED =
            booleanPreferencesKey("weekly_analytics_worker_seeded_v17")

        // One-shot flag for seeding the WeeklyTaskSummaryWorker unique
        // work on first launch post-update. Mirrors the habit-summary
        // migration flag, but keyed independently so the two migrations
        // don't share state.
        private val HAS_SEEDED_WEEKLY_TASK_SUMMARY_WORKER =
            booleanPreferencesKey("has_seeded_weekly_task_summary_worker_v1438")

        // MH-first G4: ad-hoc pause-all expiry. 0 = not paused; any value
        // > now() means notifications are silenced until that wall clock
        // time. Medication reminders are exempt — see [NotificationPauseGate].
        // Composes with scheduled quiet hours (both gates must pass).
        private val PAUSE_NOTIFICATIONS_UNTIL_EPOCH_MS =
            longPreferencesKey("pause_notifications_until_epoch_ms")

        const val IMPORTANCE_MINIMAL = "minimal"
        const val IMPORTANCE_STANDARD = "standard"
        const val IMPORTANCE_URGENT = "urgent"

        const val DEFAULT_IMPORTANCE = IMPORTANCE_STANDARD

        /** Default reminder offset = 15 minutes before the task is due. */
        const val DEFAULT_REMINDER_OFFSET_MS = 900_000L

        /** Default habit nag suppression window in days (0 = disabled). */
        const val DEFAULT_HABIT_NAG_SUPPRESSION_DAYS = 7

        /** Sentinel meaning "user has opted out of any default offset". */
        const val OFFSET_NONE = -1L

        val ALL_IMPORTANCES = listOf(IMPORTANCE_MINIMAL, IMPORTANCE_STANDARD, IMPORTANCE_URGENT)

        val ALL_REMINDER_OFFSETS = listOf(
            0L,
            300_000L,
            900_000L,
            1_800_000L,
            3_600_000L,
            86_400_000L,
            OFFSET_NONE
        )

        // ---- Briefing tone constants ----
        const val BRIEFING_TONE_CONCISE = "concise"
        const val BRIEFING_TONE_CONVERSATIONAL = "conversational"
        const val BRIEFING_TONE_MOTIVATIONAL = "motivational"
        val ALL_BRIEFING_TONES = listOf(
            BRIEFING_TONE_CONCISE,
            BRIEFING_TONE_CONVERSATIONAL,
            BRIEFING_TONE_MOTIVATIONAL
        )

        /** Section IDs toggleable in the AI briefing. */
        const val BRIEFING_SECTION_TODAY_TASKS = "today_tasks"
        const val BRIEFING_SECTION_OVERDUE = "overdue"
        const val BRIEFING_SECTION_UPCOMING = "upcoming"
        const val BRIEFING_SECTION_COLLAB = "collaborator"
        const val BRIEFING_SECTION_STREAK = "streak"
        val DEFAULT_BRIEFING_SECTIONS: Set<String> = setOf(
            BRIEFING_SECTION_TODAY_TASKS,
            BRIEFING_SECTION_OVERDUE,
            BRIEFING_SECTION_UPCOMING,
            BRIEFING_SECTION_STREAK
        )

        // ---- Collaborator digest modes ----
        const val COLLAB_DIGEST_IMMEDIATE = "immediate"
        const val COLLAB_DIGEST_HOURLY = "hourly"
        const val COLLAB_DIGEST_DAILY = "daily"
        const val COLLAB_DIGEST_MUTED = "muted"

        // ---- Watch sync modes ----
        const val WATCH_SYNC_MIRROR = "mirror"
        const val WATCH_SYNC_WATCH_ONLY = "watch_only"
        const val WATCH_SYNC_DIFFERENTIATED = "differentiated"
        const val WATCH_SYNC_DISABLED = "disabled"

        // ---- Briefing defaults ----
        const val DEFAULT_MORNING_HOUR = 8
        const val DEFAULT_EVENING_HOUR = 20

        // ---- Streak defaults ----
        const val DEFAULT_STREAK_AT_RISK_LEAD_HOURS = 4

        // ---- Snooze defaults ----
        val DEFAULT_SNOOZE_MINUTES: List<Int> = listOf(5, 15, 30, 60)
        const val DEFAULT_SNOOZE_CSV = "5,15,30,60"

        /** Factory for non-Hilt callers that only have a [Context]. */
        fun from(context: Context): NotificationPreferences =
            NotificationPreferences(context.notificationDataStore)
    }

    // region Per-type enable flags

    val taskRemindersEnabled: Flow<Boolean> = dataStore.data
        .map { it[TASK_REMINDERS_ENABLED] ?: true }

    suspend fun setTaskRemindersEnabled(enabled: Boolean) {
        dataStore.edit { it[TASK_REMINDERS_ENABLED] = enabled }
    }

    val timerAlertsEnabled: Flow<Boolean> = dataStore.data
        .map { it[TIMER_ALERTS_ENABLED] ?: true }

    suspend fun setTimerAlertsEnabled(enabled: Boolean) {
        dataStore.edit { it[TIMER_ALERTS_ENABLED] = enabled }
    }

    val medicationRemindersEnabled: Flow<Boolean> = dataStore.data
        .map { it[MEDICATION_REMINDERS_ENABLED] ?: true }

    suspend fun setMedicationRemindersEnabled(enabled: Boolean) {
        dataStore.edit { it[MEDICATION_REMINDERS_ENABLED] = enabled }
    }

    val dailyBriefingEnabled: Flow<Boolean> = dataStore.data
        .map { it[DAILY_BRIEFING_ENABLED] ?: true }

    suspend fun setDailyBriefingEnabled(enabled: Boolean) {
        dataStore.edit { it[DAILY_BRIEFING_ENABLED] = enabled }
    }

    val eveningSummaryEnabled: Flow<Boolean> = dataStore.data
        .map { it[EVENING_SUMMARY_ENABLED] ?: true }

    suspend fun setEveningSummaryEnabled(enabled: Boolean) {
        dataStore.edit { it[EVENING_SUMMARY_ENABLED] = enabled }
    }

    val weeklySummaryEnabled: Flow<Boolean> = dataStore.data
        .map { it[WEEKLY_SUMMARY_ENABLED] ?: true }

    suspend fun setWeeklySummaryEnabled(enabled: Boolean) {
        dataStore.edit { it[WEEKLY_SUMMARY_ENABLED] = enabled }
    }

    val weeklyTaskSummaryEnabled: Flow<Boolean> = dataStore.data
        .map { it[WEEKLY_TASK_SUMMARY_ENABLED] ?: true }

    suspend fun setWeeklyTaskSummaryEnabled(enabled: Boolean) {
        dataStore.edit { it[WEEKLY_TASK_SUMMARY_ENABLED] = enabled }
    }

    val overloadAlertsEnabled: Flow<Boolean> = dataStore.data
        .map { it[OVERLOAD_ALERTS_ENABLED] ?: true }

    suspend fun setOverloadAlertsEnabled(enabled: Boolean) {
        dataStore.edit { it[OVERLOAD_ALERTS_ENABLED] = enabled }
    }

    val reengagementEnabled: Flow<Boolean> = dataStore.data
        .map { it[REENGAGEMENT_ENABLED] ?: true }

    suspend fun setReengagementEnabled(enabled: Boolean) {
        dataStore.edit { it[REENGAGEMENT_ENABLED] = enabled }
    }

    // endregion

    // region Intrusive delivery behaviors
    //
    // Three orthogonal toggles that change *how* a fired reminder is
    // delivered. All default OFF so users opt in explicitly — they affect
    // the lock screen, audio stream, and vibrator. Because NotificationChannel
    // sound and vibration are immutable after creation, changing
    // [overrideVolumeEnabled] or [repeatingVibrationEnabled] causes the
    // channel to be recreated under a new ID (see NotificationHelper).

    val fullScreenNotificationsEnabled: Flow<Boolean> = dataStore.data
        .map { it[FULL_SCREEN_NOTIFICATIONS_ENABLED] ?: false }

    suspend fun setFullScreenNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { it[FULL_SCREEN_NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun getFullScreenNotificationsEnabledOnce(): Boolean =
        fullScreenNotificationsEnabled.first()

    val overrideVolumeEnabled: Flow<Boolean> = dataStore.data
        .map { it[OVERRIDE_VOLUME_ENABLED] ?: false }

    suspend fun setOverrideVolumeEnabled(enabled: Boolean) {
        dataStore.edit { it[OVERRIDE_VOLUME_ENABLED] = enabled }
    }

    suspend fun getOverrideVolumeEnabledOnce(): Boolean =
        overrideVolumeEnabled.first()

    val repeatingVibrationEnabled: Flow<Boolean> = dataStore.data
        .map { it[REPEATING_VIBRATION_ENABLED] ?: false }

    suspend fun setRepeatingVibrationEnabled(enabled: Boolean) {
        dataStore.edit { it[REPEATING_VIBRATION_ENABLED] = enabled }
    }

    suspend fun getRepeatingVibrationEnabledOnce(): Boolean =
        repeatingVibrationEnabled.first()

    // endregion

    // region Per-type volume / vibration override
    //
    // Per delivery-type triple of flags that lets the user pick whether
    // each notification type follows the phone's settings or uses the
    // app's overrides (alarm-stream volume, long repeating vibration).
    //
    // Resolution falls back to the legacy globals so a user who had
    // [OVERRIDE_VOLUME_ENABLED] / [REPEATING_VIBRATION_ENABLED] turned on
    // before this feature shipped keeps their previous behavior until
    // they touch a per-type control. Once the user explicitly writes a
    // per-type key, that overrides the legacy fallback.

    private fun followSystemFlow(key: Preferences.Key<Boolean>): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[key] ?: run {
            // No explicit per-type value yet. If the legacy globals are
            // both off, follow phone is the natural default (true). If
            // either legacy global is on, the user previously asked for
            // an override; preserve that by returning false.
            val legacyLoud = prefs[OVERRIDE_VOLUME_ENABLED] ?: false
            val legacyRepeat = prefs[REPEATING_VIBRATION_ENABLED] ?: false
            !(legacyLoud || legacyRepeat)
        }
    }

    private fun loudFlow(key: Preferences.Key<Boolean>): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[key] ?: (prefs[OVERRIDE_VOLUME_ENABLED] ?: false)
    }

    private fun repeatFlow(key: Preferences.Key<Boolean>): Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[key] ?: (prefs[REPEATING_VIBRATION_ENABLED] ?: false)
    }

    val taskRemindersFollowSystem: Flow<Boolean> = followSystemFlow(TASK_FOLLOW_SYSTEM)
    val taskRemindersVolumeLoud: Flow<Boolean> = loudFlow(TASK_VOLUME_LOUD)
    val taskRemindersVibrationRepeat: Flow<Boolean> = repeatFlow(TASK_VIBRATION_REPEAT)

    val timerAlertsFollowSystem: Flow<Boolean> = followSystemFlow(TIMER_FOLLOW_SYSTEM)
    val timerAlertsVolumeLoud: Flow<Boolean> = loudFlow(TIMER_VOLUME_LOUD)
    val timerAlertsVibrationRepeat: Flow<Boolean> = repeatFlow(TIMER_VIBRATION_REPEAT)

    val medicationRemindersFollowSystem: Flow<Boolean> = followSystemFlow(MED_FOLLOW_SYSTEM)
    val medicationRemindersVolumeLoud: Flow<Boolean> = loudFlow(MED_VOLUME_LOUD)
    val medicationRemindersVibrationRepeat: Flow<Boolean> = repeatFlow(MED_VIBRATION_REPEAT)

    suspend fun setTaskRemindersFollowSystem(enabled: Boolean) {
        dataStore.edit { it[TASK_FOLLOW_SYSTEM] = enabled }
    }

    suspend fun setTaskRemindersVolumeLoud(enabled: Boolean) {
        dataStore.edit { it[TASK_VOLUME_LOUD] = enabled }
    }

    suspend fun setTaskRemindersVibrationRepeat(enabled: Boolean) {
        dataStore.edit { it[TASK_VIBRATION_REPEAT] = enabled }
    }

    suspend fun setTimerAlertsFollowSystem(enabled: Boolean) {
        dataStore.edit { it[TIMER_FOLLOW_SYSTEM] = enabled }
    }

    suspend fun setTimerAlertsVolumeLoud(enabled: Boolean) {
        dataStore.edit { it[TIMER_VOLUME_LOUD] = enabled }
    }

    suspend fun setTimerAlertsVibrationRepeat(enabled: Boolean) {
        dataStore.edit { it[TIMER_VIBRATION_REPEAT] = enabled }
    }

    suspend fun setMedicationRemindersFollowSystem(enabled: Boolean) {
        dataStore.edit { it[MED_FOLLOW_SYSTEM] = enabled }
    }

    suspend fun setMedicationRemindersVolumeLoud(enabled: Boolean) {
        dataStore.edit { it[MED_VOLUME_LOUD] = enabled }
    }

    suspend fun setMedicationRemindersVibrationRepeat(enabled: Boolean) {
        dataStore.edit { it[MED_VIBRATION_REPEAT] = enabled }
    }

    /**
     * Resolves the effective (loud, repeat) override pair for [type] at
     * read time. When the user has "follow phone" on for the type, both
     * are forced to false regardless of the individual flag values —
     * that's how the helper turns the channel into a "system defaults"
     * channel that ignores the app's override settings.
     */
    suspend fun getOverridesForOnce(type: NotificationOverrideType): NotificationOverrides {
        val follow: Boolean
        val loud: Boolean
        val repeat: Boolean
        when (type) {
            NotificationOverrideType.TASK_REMINDERS -> {
                follow = taskRemindersFollowSystem.first()
                loud = taskRemindersVolumeLoud.first()
                repeat = taskRemindersVibrationRepeat.first()
            }
            NotificationOverrideType.TIMER_ALERTS -> {
                follow = timerAlertsFollowSystem.first()
                loud = timerAlertsVolumeLoud.first()
                repeat = timerAlertsVibrationRepeat.first()
            }
            NotificationOverrideType.MEDICATION_REMINDERS -> {
                follow = medicationRemindersFollowSystem.first()
                loud = medicationRemindersVolumeLoud.first()
                repeat = medicationRemindersVibrationRepeat.first()
            }
        }
        return if (follow) {
            NotificationOverrides(loud = false, repeat = false)
        } else {
            NotificationOverrides(loud = loud, repeat = repeat)
        }
    }

    // endregion

    // region Importance

    val importance: Flow<String> = dataStore.data.map {
        val stored = it[NOTIFICATION_IMPORTANCE] ?: DEFAULT_IMPORTANCE
        if (stored in ALL_IMPORTANCES) stored else DEFAULT_IMPORTANCE
    }

    suspend fun setImportance(level: String) {
        val normalized = if (level in ALL_IMPORTANCES) level else DEFAULT_IMPORTANCE
        dataStore.edit { it[NOTIFICATION_IMPORTANCE] = normalized }
    }

    suspend fun getImportanceOnce(): String = importance.first()

    val previousImportance: Flow<String?> = dataStore.data.map {
        it[PREVIOUS_IMPORTANCE]
    }

    suspend fun getPreviousImportanceOnce(): String? = dataStore.data
        .first()[PREVIOUS_IMPORTANCE]

    suspend fun setPreviousImportance(level: String) {
        dataStore.edit { it[PREVIOUS_IMPORTANCE] = level }
    }

    // endregion

    // region Default reminder offset

    val defaultReminderOffset: Flow<Long> = dataStore.data.map {
        it[DEFAULT_REMINDER_OFFSET] ?: DEFAULT_REMINDER_OFFSET_MS
    }

    suspend fun setDefaultReminderOffset(offset: Long) {
        dataStore.edit { it[DEFAULT_REMINDER_OFFSET] = offset }
    }

    suspend fun getDefaultReminderOffsetOnce(): Long = defaultReminderOffset.first()

    // endregion

    // region Active profile

    /** -1 = no selection, fall back to the built-in default bundle. */
    val activeProfileId: Flow<Long> = dataStore.data.map {
        it[ACTIVE_PROFILE_ID] ?: -1L
    }

    suspend fun setActiveProfileId(id: Long) {
        dataStore.edit { it[ACTIVE_PROFILE_ID] = id }
    }

    suspend fun getActiveProfileIdOnce(): Long = activeProfileId.first()

    /**
     * Per-category profile overrides: "<category_key>=<profile_id>" strings.
     * A category without an entry inherits the global [activeProfileId].
     */
    val categoryProfileOverrides: Flow<Map<String, Long>> = dataStore.data.map { prefs ->
        (prefs[CATEGORY_PROFILE_OVERRIDES] ?: emptySet()).mapNotNull { s ->
            val parts = s.split("=", limit = 2)
            if (parts.size == 2) {
                val id = parts[1].toLongOrNull()
                if (id != null) parts[0] to id else null
            } else {
                null
            }
        }.toMap()
    }

    suspend fun setCategoryProfileOverride(categoryKey: String, profileId: Long?) {
        dataStore.edit { prefs ->
            val existing = (prefs[CATEGORY_PROFILE_OVERRIDES] ?: emptySet()).filterNot {
                it.startsWith("$categoryKey=")
            }.toMutableSet()
            if (profileId != null) existing += "$categoryKey=$profileId"
            prefs[CATEGORY_PROFILE_OVERRIDES] = existing
        }
    }

    // endregion

    // region Streak alerts

    val streakAlertsEnabled: Flow<Boolean> = dataStore.data
        .map { it[STREAK_ALERTS_ENABLED] ?: true }

    suspend fun setStreakAlertsEnabled(enabled: Boolean) {
        dataStore.edit { it[STREAK_ALERTS_ENABLED] = enabled }
    }

    val streakAtRiskLeadHours: Flow<Int> = dataStore.data
        .map { it[STREAK_AT_RISK_LEAD_HOURS] ?: DEFAULT_STREAK_AT_RISK_LEAD_HOURS }

    suspend fun setStreakAtRiskLeadHours(hours: Int) {
        dataStore.edit { it[STREAK_AT_RISK_LEAD_HOURS] = hours.coerceIn(1, 24) }
    }

    // endregion

    // region Daily briefing

    val briefingMorningHour: Flow<Int> = dataStore.data
        .map { it[BRIEFING_MORNING_HOUR] ?: DEFAULT_MORNING_HOUR }

    suspend fun setBriefingMorningHour(hour: Int) {
        dataStore.edit { it[BRIEFING_MORNING_HOUR] = hour.coerceIn(0, 23) }
    }

    val briefingMiddayEnabled: Flow<Boolean> = dataStore.data
        .map { it[BRIEFING_MIDDAY_ENABLED] ?: false }

    suspend fun setBriefingMiddayEnabled(enabled: Boolean) {
        dataStore.edit { it[BRIEFING_MIDDAY_ENABLED] = enabled }
    }

    val briefingEveningHour: Flow<Int> = dataStore.data
        .map { it[BRIEFING_EVENING_HOUR] ?: DEFAULT_EVENING_HOUR }

    suspend fun setBriefingEveningHour(hour: Int) {
        dataStore.edit { it[BRIEFING_EVENING_HOUR] = hour.coerceIn(0, 23) }
    }

    val briefingTone: Flow<String> = dataStore.data
        .map { it[BRIEFING_TONE] ?: BRIEFING_TONE_CONCISE }

    suspend fun setBriefingTone(tone: String) {
        val normalized = if (tone in ALL_BRIEFING_TONES) tone else BRIEFING_TONE_CONCISE
        dataStore.edit { it[BRIEFING_TONE] = normalized }
    }

    val briefingSections: Flow<Set<String>> = dataStore.data
        .map { it[BRIEFING_SECTIONS] ?: DEFAULT_BRIEFING_SECTIONS }

    suspend fun setBriefingSections(sections: Set<String>) {
        dataStore.edit { it[BRIEFING_SECTIONS] = sections }
    }

    val briefingReadAloudEnabled: Flow<Boolean> = dataStore.data
        .map { it[BRIEFING_READ_ALOUD] ?: false }

    suspend fun setBriefingReadAloudEnabled(enabled: Boolean) {
        dataStore.edit { it[BRIEFING_READ_ALOUD] = enabled }
    }

    // endregion

    // region Collaborator notifications

    val collabDigestMode: Flow<String> = dataStore.data
        .map { it[COLLAB_DIGEST_MODE] ?: COLLAB_DIGEST_IMMEDIATE }

    suspend fun setCollabDigestMode(mode: String) {
        dataStore.edit { it[COLLAB_DIGEST_MODE] = mode }
    }

    val collabAssignedEnabled: Flow<Boolean> = dataStore.data
        .map { it[COLLAB_ASSIGNED_ENABLED] ?: true }

    suspend fun setCollabAssignedEnabled(enabled: Boolean) {
        dataStore.edit { it[COLLAB_ASSIGNED_ENABLED] = enabled }
    }

    val collabMentionedEnabled: Flow<Boolean> = dataStore.data
        .map { it[COLLAB_MENTIONED_ENABLED] ?: true }

    suspend fun setCollabMentionedEnabled(enabled: Boolean) {
        dataStore.edit { it[COLLAB_MENTIONED_ENABLED] = enabled }
    }

    val collabStatusEnabled: Flow<Boolean> = dataStore.data
        .map { it[COLLAB_STATUS_ENABLED] ?: true }

    suspend fun setCollabStatusEnabled(enabled: Boolean) {
        dataStore.edit { it[COLLAB_STATUS_ENABLED] = enabled }
    }

    val collabCommentEnabled: Flow<Boolean> = dataStore.data
        .map { it[COLLAB_COMMENT_ENABLED] ?: true }

    suspend fun setCollabCommentEnabled(enabled: Boolean) {
        dataStore.edit { it[COLLAB_COMMENT_ENABLED] = enabled }
    }

    val collabDueSoonEnabled: Flow<Boolean> = dataStore.data
        .map { it[COLLAB_DUE_SOON_ENABLED] ?: true }

    suspend fun setCollabDueSoonEnabled(enabled: Boolean) {
        dataStore.edit { it[COLLAB_DUE_SOON_ENABLED] = enabled }
    }

    // endregion

    // region Smartwatch

    val watchSyncMode: Flow<String> = dataStore.data
        .map { it[WATCH_SYNC_MODE] ?: WATCH_SYNC_MIRROR }

    suspend fun setWatchSyncMode(mode: String) {
        dataStore.edit { it[WATCH_SYNC_MODE] = mode }
    }

    val watchVolumePercent: Flow<Int> = dataStore.data
        .map { it[WATCH_VOLUME_PERCENT] ?: 70 }

    suspend fun setWatchVolumePercent(percent: Int) {
        dataStore.edit { it[WATCH_VOLUME_PERCENT] = percent.coerceIn(0, 100) }
    }

    val watchHapticIntensity: Flow<String> = dataStore.data
        .map { it[WATCH_HAPTIC_INTENSITY] ?: "medium" }

    suspend fun setWatchHapticIntensity(intensity: String) {
        dataStore.edit { it[WATCH_HAPTIC_INTENSITY] = intensity }
    }

    // endregion

    // region Visual: badge + toast position + contrast

    val badgeMode: Flow<String> = dataStore.data
        .map { it[BADGE_MODE] ?: "total" }

    suspend fun setBadgeMode(mode: String) {
        dataStore.edit { it[BADGE_MODE] = mode }
    }

    val toastPosition: Flow<String> = dataStore.data
        .map { it[TOAST_POSITION] ?: "top_right" }

    suspend fun setToastPosition(position: String) {
        dataStore.edit { it[TOAST_POSITION] = position }
    }

    val highContrastNotificationsEnabled: Flow<Boolean> = dataStore.data
        .map { it[HIGH_CONTRAST_NOTIFICATIONS] ?: false }

    suspend fun setHighContrastNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { it[HIGH_CONTRAST_NOTIFICATIONS] = enabled }
    }

    // endregion

    // region Habit nag suppression

    val habitNagSuppressionDays: Flow<Int> = dataStore.data
        .map { it[HABIT_NAG_SUPPRESSION_DAYS] ?: DEFAULT_HABIT_NAG_SUPPRESSION_DAYS }

    suspend fun setHabitNagSuppressionDays(days: Int) {
        dataStore.edit { it[HABIT_NAG_SUPPRESSION_DAYS] = days.coerceIn(0, 30) }
    }

    suspend fun getHabitNagSuppressionDaysOnce(): Int = habitNagSuppressionDays.first()

    // endregion

    // region Snooze durations

    val snoozeDurationsMinutes: Flow<List<Int>> = dataStore.data.map { prefs ->
        (prefs[SNOOZE_DURATIONS_CSV] ?: DEFAULT_SNOOZE_CSV)
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .takeIf { it.isNotEmpty() }
            ?: DEFAULT_SNOOZE_MINUTES
    }

    suspend fun setSnoozeDurationsMinutes(minutes: List<Int>) {
        dataStore.edit {
            it[SNOOZE_DURATIONS_CSV] = minutes.joinToString(",")
        }
    }

    // endregion

    // region Internal migration flags

    suspend fun getWeeklyHabitSummaryMigrationRunOnce(): Boolean =
        dataStore.data.first()[WEEKLY_HABIT_SUMMARY_MIGRATION_RUN] ?: false

    suspend fun setWeeklyHabitSummaryMigrationRun() {
        dataStore.edit { it[WEEKLY_HABIT_SUMMARY_MIGRATION_RUN] = true }
    }

    suspend fun getHasSeededWeeklyTaskSummaryWorkerOnce(): Boolean =
        dataStore.data.first()[HAS_SEEDED_WEEKLY_TASK_SUMMARY_WORKER] ?: false

    suspend fun setHasSeededWeeklyTaskSummaryWorker() {
        dataStore.edit { it[HAS_SEEDED_WEEKLY_TASK_SUMMARY_WORKER] = true }
    }

    // endregion

    // region Auto-generated weekly reviews (A2)

    val weeklyReviewAutoGenerateEnabled: Flow<Boolean> = dataStore.data
        .map { it[WEEKLY_REVIEW_AUTO_GENERATE_ENABLED] ?: true }

    suspend fun setWeeklyReviewAutoGenerateEnabled(enabled: Boolean) {
        dataStore.edit { it[WEEKLY_REVIEW_AUTO_GENERATE_ENABLED] = enabled }
    }

    val weeklyReviewNotificationEnabled: Flow<Boolean> = dataStore.data
        .map { it[WEEKLY_REVIEW_NOTIFICATION_ENABLED] ?: true }

    suspend fun setWeeklyReviewNotificationEnabled(enabled: Boolean) {
        dataStore.edit { it[WEEKLY_REVIEW_NOTIFICATION_ENABLED] = enabled }
    }

    suspend fun getWeeklyReviewWorkerSeededOnce(): Boolean =
        dataStore.data.first()[WEEKLY_REVIEW_WORKER_SEEDED] ?: false

    suspend fun setWeeklyReviewWorkerSeeded() {
        dataStore.edit { it[WEEKLY_REVIEW_WORKER_SEEDED] = true }
    }

    // endregion

    // region Weekly analytics notification (Phase I)

    val weeklyAnalyticsNotificationEnabled: Flow<Boolean> = dataStore.data
        .map { it[WEEKLY_ANALYTICS_NOTIFICATION_ENABLED] ?: true }

    suspend fun setWeeklyAnalyticsNotificationEnabled(enabled: Boolean) {
        dataStore.edit { it[WEEKLY_ANALYTICS_NOTIFICATION_ENABLED] = enabled }
    }

    suspend fun getWeeklyAnalyticsWorkerSeededOnce(): Boolean =
        dataStore.data.first()[WEEKLY_ANALYTICS_WORKER_SEEDED] ?: false

    suspend fun setWeeklyAnalyticsWorkerSeeded() {
        dataStore.edit { it[WEEKLY_ANALYTICS_WORKER_SEEDED] = true }
    }

    // endregion

    // region Pause-all (MH-first G4)

    /**
     * Wall-clock epoch (ms) at which the current ad-hoc pause expires.
     * Returns `0L` when no pause is active. Persists across app restarts.
     *
     * The pause is treated as **active** when this value is strictly
     * greater than the current wall clock — schedulers/receivers consult
     * the same instant they're about to post a notification, so a stale
     * (expired) pause auto-clears in effect without needing a worker to
     * zero it out. Medication reminders are exempt — see
     * [com.averycorp.prismtask.notifications.NotificationPauseGate].
     */
    val pauseNotificationsUntilEpochMs: Flow<Long> = dataStore.data
        .map { it[PAUSE_NOTIFICATIONS_UNTIL_EPOCH_MS] ?: 0L }

    suspend fun setPauseNotificationsUntilEpochMs(epochMs: Long) {
        dataStore.edit { it[PAUSE_NOTIFICATIONS_UNTIL_EPOCH_MS] = epochMs.coerceAtLeast(0L) }
    }

    suspend fun getPauseNotificationsUntilEpochMsOnce(): Long =
        pauseNotificationsUntilEpochMs.first()

    suspend fun clearPauseNotifications() {
        dataStore.edit { it[PAUSE_NOTIFICATIONS_UNTIL_EPOCH_MS] = 0L }
    }

    // endregion
}
