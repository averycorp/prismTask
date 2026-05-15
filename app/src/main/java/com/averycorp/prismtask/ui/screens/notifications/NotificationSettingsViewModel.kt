package com.averycorp.prismtask.ui.screens.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.entity.CustomSoundEntity
import com.averycorp.prismtask.data.local.entity.NotificationProfileEntity
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.data.repository.CustomSoundRepository
import com.averycorp.prismtask.data.repository.NotificationProfileRepository
import com.averycorp.prismtask.domain.model.notifications.NotificationProfile
import com.averycorp.prismtask.domain.usecase.NotificationProfileResolver
import com.averycorp.prismtask.notifications.NotificationTester
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the entire Notifications settings surface:
 *  - active profile + library of profiles
 *  - global preferences (snooze list, briefing schedule, streak, collab, watch)
 *  - custom sound library CRUD
 *  - preview/test actions
 *
 * Keeps the hub composable and every sub-screen simple: they subscribe
 * to the combined [state] and dispatch through action methods.
 */
@HiltViewModel
class NotificationSettingsViewModel
@Inject
constructor(
    private val prefs: NotificationPreferences,
    private val profileRepo: NotificationProfileRepository,
    private val customSoundRepo: CustomSoundRepository,
    private val tester: NotificationTester
) : ViewModel() {
    private val resolver = NotificationProfileResolver.DEFAULT

    val profiles: StateFlow<List<NotificationProfileEntity>> = profileRepo.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val customSounds: StateFlow<List<CustomSoundEntity>> = customSoundRepo.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeProfileId: StateFlow<Long> = prefs.activeProfileId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1L)

    val activeProfile: StateFlow<NotificationProfile> = combine(
        profiles,
        activeProfileId
    ) { list, id ->
        val entity = list.firstOrNull { it.id == id } ?: list.firstOrNull()
        if (entity != null) resolver.resolve(entity) else NotificationProfile.builtInDefault()
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        NotificationProfile.builtInDefault()
    )

    // Global per-type toggles (re-export existing preferences unchanged)
    val taskRemindersEnabled = prefs.taskRemindersEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val timerAlertsEnabled = prefs.timerAlertsEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val medicationRemindersEnabled = prefs.medicationRemindersEnabled.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        true
    )
    val dailyBriefingEnabled = prefs.dailyBriefingEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val eveningSummaryEnabled = prefs.eveningSummaryEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val weeklySummaryEnabled = prefs.weeklySummaryEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val weeklyReviewAutoGenerateEnabled = prefs.weeklyReviewAutoGenerateEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val weeklyReviewNotificationEnabled = prefs.weeklyReviewNotificationEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val weeklyAnalyticsNotificationEnabled = prefs.weeklyAnalyticsNotificationEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val weeklyTaskSummaryEnabled = prefs.weeklyTaskSummaryEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val streakAlertsEnabled = prefs.streakAlertsEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val reengagementEnabled = prefs.reengagementEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val overloadAlertsEnabled = prefs.overloadAlertsEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    // Per-type volume + vibration override. Each type owns three flags:
    // "follow phone settings" (the alignment switch) plus the two app
    // overrides (loud / repeating). When follow-system is true the
    // overrides are ignored at notify time; the toggles still persist so
    // disabling the switch restores the user's previous choices.
    val taskRemindersFollowSystem = prefs.taskRemindersFollowSystem
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val taskRemindersVolumeLoud = prefs.taskRemindersVolumeLoud
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val taskRemindersVibrationRepeat = prefs.taskRemindersVibrationRepeat
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val timerAlertsFollowSystem = prefs.timerAlertsFollowSystem
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val timerAlertsVolumeLoud = prefs.timerAlertsVolumeLoud
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val timerAlertsVibrationRepeat = prefs.timerAlertsVibrationRepeat
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val medicationRemindersFollowSystem = prefs.medicationRemindersFollowSystem
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val medicationRemindersVolumeLoud = prefs.medicationRemindersVolumeLoud
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val medicationRemindersVibrationRepeat = prefs.medicationRemindersVibrationRepeat
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Habit nag suppression
    val habitNagSuppressionDays = prefs.habitNagSuppressionDays.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        NotificationPreferences.DEFAULT_HABIT_NAG_SUPPRESSION_DAYS
    )

    // Briefing
    val briefingMorningHour = prefs.briefingMorningHour.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        NotificationPreferences.DEFAULT_MORNING_HOUR
    )
    val briefingEveningHour = prefs.briefingEveningHour.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        NotificationPreferences.DEFAULT_EVENING_HOUR
    )
    val briefingMiddayEnabled = prefs.briefingMiddayEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val briefingTone = prefs.briefingTone.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        NotificationPreferences.BRIEFING_TONE_CONCISE
    )
    val briefingSections = prefs.briefingSections.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        NotificationPreferences.DEFAULT_BRIEFING_SECTIONS
    )
    val briefingReadAloudEnabled = prefs.briefingReadAloudEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Streak
    val streakAtRiskLeadHours = prefs.streakAtRiskLeadHours.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        NotificationPreferences.DEFAULT_STREAK_AT_RISK_LEAD_HOURS
    )

    // Collaborator
    val collabDigestMode = prefs.collabDigestMode.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        NotificationPreferences.COLLAB_DIGEST_IMMEDIATE
    )
    val collabAssignedEnabled = prefs.collabAssignedEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val collabMentionedEnabled = prefs.collabMentionedEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val collabStatusEnabled = prefs.collabStatusEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val collabCommentEnabled = prefs.collabCommentEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val collabDueSoonEnabled = prefs.collabDueSoonEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    // Watch
    val watchSyncMode = prefs.watchSyncMode.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        NotificationPreferences.WATCH_SYNC_MIRROR
    )
    val watchVolumePercent = prefs.watchVolumePercent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 70)
    val watchHapticIntensity = prefs.watchHapticIntensity.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "medium")

    // Visual
    val badgeMode = prefs.badgeMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "total")
    val toastPosition = prefs.toastPosition.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "top_right")
    val highContrastEnabled = prefs.highContrastNotificationsEnabled.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        false
    )

    // Snooze options
    val snoozeDurationsMinutes = prefs.snoozeDurationsMinutes.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        NotificationPreferences.DEFAULT_SNOOZE_MINUTES
    )

    // Transient status for the tester button
    private val _testStatus = MutableStateFlow<String?>(null)
    val testStatus: StateFlow<String?> = _testStatus

    // ---------- Actions ----------

    fun setActiveProfile(id: Long) = viewModelScope.launch { prefs.setActiveProfileId(id) }

    fun saveProfile(entity: NotificationProfileEntity) = viewModelScope.launch {
        if (entity.id == 0L) profileRepo.insert(entity) else profileRepo.update(entity)
    }

    fun deleteProfile(entity: NotificationProfileEntity) = viewModelScope.launch {
        if (!entity.isBuiltIn) profileRepo.delete(entity)
    }

    fun setTaskRemindersEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setTaskRemindersEnabled(enabled) }

    fun setTimerAlertsEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setTimerAlertsEnabled(enabled) }

    fun setMedicationRemindersEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setMedicationRemindersEnabled(enabled)
    }

    fun setDailyBriefingEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setDailyBriefingEnabled(enabled) }

    fun setEveningSummaryEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setEveningSummaryEnabled(enabled) }

    fun setWeeklySummaryEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setWeeklySummaryEnabled(enabled) }

    fun setWeeklyTaskSummaryEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setWeeklyTaskSummaryEnabled(enabled) }

    // Auto-generated weekly reviews (A2). Pref-only flip; the
    // WeeklyReviewWorker checks [NotificationPreferences.weeklyReviewAutoGenerateEnabled]
    // inside doWork() on its next Sunday run, so stale schedules
    // no-op until [NotificationWorkerScheduler.applyAll] re-aligns
    // WorkManager on the next app launch.
    fun setWeeklyReviewAutoGenerateEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setWeeklyReviewAutoGenerateEnabled(enabled)
    }

    fun setWeeklyReviewNotificationEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setWeeklyReviewNotificationEnabled(enabled)
    }

    /**
     * Toggle for the Sunday-evening weekly analytics summary notification.
     * Pref-only flip — the next [NotificationWorkerScheduler.applyAll]
     * call will re-align WorkManager. The worker also re-checks the pref
     * inside doWork() so any in-flight enqueue stays consistent.
     */
    fun setWeeklyAnalyticsNotificationEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setWeeklyAnalyticsNotificationEnabled(enabled)
    }

    fun setStreakAlertsEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setStreakAlertsEnabled(enabled) }

    fun setReengagementEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setReengagementEnabled(enabled) }

    fun setOverloadAlertsEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setOverloadAlertsEnabled(enabled) }

    fun setTaskRemindersFollowSystem(enabled: Boolean) = viewModelScope.launch {
        prefs.setTaskRemindersFollowSystem(enabled)
    }

    fun setTaskRemindersVolumeLoud(enabled: Boolean) = viewModelScope.launch {
        prefs.setTaskRemindersVolumeLoud(enabled)
    }

    fun setTaskRemindersVibrationRepeat(enabled: Boolean) = viewModelScope.launch {
        prefs.setTaskRemindersVibrationRepeat(enabled)
    }

    fun setTimerAlertsFollowSystem(enabled: Boolean) = viewModelScope.launch {
        prefs.setTimerAlertsFollowSystem(enabled)
    }

    fun setTimerAlertsVolumeLoud(enabled: Boolean) = viewModelScope.launch {
        prefs.setTimerAlertsVolumeLoud(enabled)
    }

    fun setTimerAlertsVibrationRepeat(enabled: Boolean) = viewModelScope.launch {
        prefs.setTimerAlertsVibrationRepeat(enabled)
    }

    fun setMedicationRemindersFollowSystem(enabled: Boolean) = viewModelScope.launch {
        prefs.setMedicationRemindersFollowSystem(enabled)
    }

    fun setMedicationRemindersVolumeLoud(enabled: Boolean) = viewModelScope.launch {
        prefs.setMedicationRemindersVolumeLoud(enabled)
    }

    fun setMedicationRemindersVibrationRepeat(enabled: Boolean) = viewModelScope.launch {
        prefs.setMedicationRemindersVibrationRepeat(enabled)
    }

    fun setHabitNagSuppressionDays(days: Int) = viewModelScope.launch { prefs.setHabitNagSuppressionDays(days) }

    fun setBriefingMorningHour(hour: Int) = viewModelScope.launch { prefs.setBriefingMorningHour(hour) }

    fun setBriefingEveningHour(hour: Int) = viewModelScope.launch { prefs.setBriefingEveningHour(hour) }

    fun setBriefingMiddayEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setBriefingMiddayEnabled(enabled) }

    fun setBriefingTone(tone: String) = viewModelScope.launch { prefs.setBriefingTone(tone) }

    fun toggleBriefingSection(section: String, enabled: Boolean) = viewModelScope.launch {
        val current = briefingSections.value.toMutableSet()
        if (enabled) current += section else current -= section
        prefs.setBriefingSections(current)
    }

    fun setBriefingReadAloud(enabled: Boolean) = viewModelScope.launch { prefs.setBriefingReadAloudEnabled(enabled) }

    fun setStreakAtRiskLeadHours(hours: Int) = viewModelScope.launch { prefs.setStreakAtRiskLeadHours(hours) }

    fun setCollabDigestMode(mode: String) = viewModelScope.launch { prefs.setCollabDigestMode(mode) }

    fun setCollabAssignedEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setCollabAssignedEnabled(enabled) }

    fun setCollabMentionedEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setCollabMentionedEnabled(enabled) }

    fun setCollabStatusEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setCollabStatusEnabled(enabled) }

    fun setCollabCommentEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setCollabCommentEnabled(enabled) }

    fun setCollabDueSoonEnabled(enabled: Boolean) = viewModelScope.launch { prefs.setCollabDueSoonEnabled(enabled) }

    fun setWatchSyncMode(mode: String) = viewModelScope.launch { prefs.setWatchSyncMode(mode) }

    fun setWatchVolumePercent(percent: Int) = viewModelScope.launch { prefs.setWatchVolumePercent(percent) }

    fun setWatchHapticIntensity(intensity: String) = viewModelScope.launch { prefs.setWatchHapticIntensity(intensity) }

    fun setBadgeMode(mode: String) = viewModelScope.launch { prefs.setBadgeMode(mode) }

    fun setToastPosition(position: String) = viewModelScope.launch { prefs.setToastPosition(position) }

    fun setHighContrastEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setHighContrastNotificationsEnabled(enabled)
    }

    fun setSnoozeDurationsMinutes(minutes: List<Int>) = viewModelScope.launch {
        prefs.setSnoozeDurationsMinutes(minutes.sorted().distinct())
    }

    fun seedBuiltInProfilesIfEmpty() = viewModelScope.launch {
        profileRepo.seedBuiltInsIfEmpty()
    }

    fun previewProfile(profile: NotificationProfile) = viewModelScope.launch {
        tester.previewSoundAndVibration(profile, customSounds.value)
        _testStatus.value = "Preview playing \u2014 ${profile.name}"
    }

    fun testProfile(profile: NotificationProfile) = viewModelScope.launch {
        tester.fireTestNotification(profile)
        _testStatus.value = "Test notification posted \u2014 check the shade"
    }

    fun stopPreview() {
        tester.stopPreview()
        _testStatus.value = null
    }

    fun clearTestStatus() {
        _testStatus.value = null
    }

    fun deleteCustomSound(sound: CustomSoundEntity) = viewModelScope.launch {
        customSoundRepo.delete(sound)
    }

    /** Helper for the profile editor: rebuild the entity from an in-memory copy. */
    fun commitProfileEdit(entity: NotificationProfileEntity) = saveProfile(entity)

    /** Expose the count of enabled per-type toggles — used by the hub subtitle. */
    val enabledTypeCount: StateFlow<Int> = combine(
        taskRemindersEnabled,
        timerAlertsEnabled,
        medicationRemindersEnabled,
        dailyBriefingEnabled,
        eveningSummaryEnabled,
        weeklySummaryEnabled,
        weeklyTaskSummaryEnabled,
        weeklyReviewAutoGenerateEnabled,
        streakAlertsEnabled,
        reengagementEnabled
    ) { flags: Array<Boolean> -> flags.count { it } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        // Ensure the built-in profile library exists — matches the
        // pattern used by TemplateSeeder during first-run.
        seedBuiltInProfilesIfEmpty()
    }
}
