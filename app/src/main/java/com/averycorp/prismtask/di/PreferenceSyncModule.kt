package com.averycorp.prismtask.di

import android.content.Context
import com.averycorp.prismtask.data.preferences.a11yDataStore
import com.averycorp.prismtask.data.preferences.advancedTuningDataStore
import com.averycorp.prismtask.data.preferences.archiveDataStore
import com.averycorp.prismtask.data.preferences.coachingDataStore
import com.averycorp.prismtask.data.preferences.dailyEssentialsDataStore
import com.averycorp.prismtask.data.preferences.dashboardDataStore
import com.averycorp.prismtask.data.preferences.habitListDataStore
import com.averycorp.prismtask.data.preferences.leisureBudgetDataStore
import com.averycorp.prismtask.data.preferences.medicationDataStore
import com.averycorp.prismtask.data.preferences.morningCheckInDataStore
import com.averycorp.prismtask.data.preferences.notificationDataStore
import com.averycorp.prismtask.data.preferences.onboardingDataStore
import com.averycorp.prismtask.data.preferences.tabDataStore
import com.averycorp.prismtask.data.preferences.taskBehaviorDataStore
import com.averycorp.prismtask.data.preferences.templateDataStore
import com.averycorp.prismtask.data.preferences.timerDataStore
import com.averycorp.prismtask.data.preferences.voiceDataStore
import com.averycorp.prismtask.data.remote.sync.PreferenceSyncSpec
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ElementsIntoSet

/**
 * Registers every DataStore preference file that should sync via
 * [com.averycorp.prismtask.data.remote.GenericPreferenceSyncService].
 *
 * Excluded on purpose:
 * - `auth_token_prefs`   — Firebase/Google tokens; per-device, sensitive.
 * - `pro_status_prefs`   — billing cache; server-authoritative.
 * - `backend_sync_prefs` — pull-watermark; per-device.
 * - `built_in_sync_prefs`, `medication_migration_prefs` — one-time migration flags.
 * - `gcal_sync_prefs`    — Google Calendar sync tokens; per-device.
 * - `sync_device_prefs`  — the sync service's own device identity.
 * - `theme_prefs` / `sort_prefs` — covered by dedicated sync services that do
 *   extra work (sort does per-project cloud-id translation; theme pre-dates
 *   the generic layer).
 *
 * Renaming a firestoreDocName in this file orphans already-synced state for
 * existing installs — add migration logic before changing any of these strings.
 */
@Module
@InstallIn(SingletonComponent::class)
object PreferenceSyncModule {
    @Provides
    @ElementsIntoSet
    fun provideSpecs(
        @ApplicationContext context: Context
    ): Set<PreferenceSyncSpec> = setOf(
        PreferenceSyncSpec("a11y_prefs", context.a11yDataStore),
        PreferenceSyncSpec("advanced_tuning_prefs", context.advancedTuningDataStore),
        PreferenceSyncSpec("archive_prefs", context.archiveDataStore),
        PreferenceSyncSpec("coaching_prefs", context.coachingDataStore),
        PreferenceSyncSpec("daily_essentials_prefs", context.dailyEssentialsDataStore),
        PreferenceSyncSpec("dashboard_prefs", context.dashboardDataStore),
        PreferenceSyncSpec("habit_list_prefs", context.habitListDataStore),
        PreferenceSyncSpec("leisure_budget_prefs", context.leisureBudgetDataStore),
        PreferenceSyncSpec("medication_prefs", context.medicationDataStore),
        PreferenceSyncSpec("morning_checkin_prefs", context.morningCheckInDataStore),
        PreferenceSyncSpec("nd_prefs", context.ndPrefsDataStore),
        PreferenceSyncSpec("notification_prefs", context.notificationDataStore),
        PreferenceSyncSpec("onboarding_prefs", context.onboardingDataStore),
        PreferenceSyncSpec("tab_prefs", context.tabDataStore),
        PreferenceSyncSpec("task_behavior_prefs", context.taskBehaviorDataStore),
        PreferenceSyncSpec("template_prefs", context.templateDataStore),
        PreferenceSyncSpec("timer_prefs", context.timerDataStore),
        PreferenceSyncSpec("user_prefs", context.userPrefsDataStore),
        PreferenceSyncSpec("voice_prefs", context.voiceDataStore)
    )
}
