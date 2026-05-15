package com.averycorp.prismtask.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.billing.BillingManager
import com.averycorp.prismtask.data.billing.BillingPeriod
import com.averycorp.prismtask.data.billing.SubscriptionState
import com.averycorp.prismtask.data.billing.UserTier
import com.averycorp.prismtask.data.calendar.CalendarInfo
import com.averycorp.prismtask.data.calendar.CalendarManager
import com.averycorp.prismtask.data.calendar.CalendarSyncPreferences
import com.averycorp.prismtask.data.export.DataExporter
import com.averycorp.prismtask.data.export.DataImporter
import com.averycorp.prismtask.data.local.database.PrismTaskDatabase
import com.averycorp.prismtask.data.preferences.ArchivePreferences
import com.averycorp.prismtask.data.preferences.AuthTokenPreferences
import com.averycorp.prismtask.data.preferences.BackendSyncPreferences
import com.averycorp.prismtask.data.preferences.DashboardPreferences
import com.averycorp.prismtask.data.preferences.HabitListPreferences
import com.averycorp.prismtask.data.preferences.OnboardingPreferences
import com.averycorp.prismtask.data.preferences.TabPreferences
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.preferences.TemplatePreferences
import com.averycorp.prismtask.data.preferences.ThemePreferences
import com.averycorp.prismtask.data.preferences.TimerPreferences
import com.averycorp.prismtask.data.preferences.TourCardPreferences
import com.averycorp.prismtask.data.preferences.UrgencyWeights
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.ui.navigation.ALL_BOTTOM_NAV_ITEMS
import com.averycorp.prismtask.ui.screens.settings.sections.AccessibilitySettingsViewModel
import com.averycorp.prismtask.ui.screens.settings.sections.NotificationSettingsViewModel
import com.averycorp.prismtask.ui.screens.settings.sections.SyncSettingsViewModel
import com.averycorp.prismtask.ui.screens.settings.sections.ThemeSettingsViewModel
import com.averycorp.prismtask.ui.screens.settings.sections.VoiceSettingsViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import javax.inject.Inject

/**
 * The Settings screen ViewModel. Long-running action handlers for
 * Export/Import have been moved to a companion extension file
 * ([SettingsViewModelExportImport]) to keep this file focused on
 * state exposure and simple setters.
 */
@HiltViewModel
class SettingsViewModel
@Inject
constructor(
    @ApplicationContext internal val appContext: Context,
    private val themePreferences: ThemePreferences,
    private val archivePreferences: ArchivePreferences,
    private val dashboardPreferences: DashboardPreferences,
    private val tabPreferences: TabPreferences,
    private val taskBehaviorPreferences: TaskBehaviorPreferences,
    private val timerPreferences: TimerPreferences,
    private val habitListPreferences: HabitListPreferences,
    private val database: PrismTaskDatabase,
    internal val dataExporter: DataExporter,
    internal val dataImporter: DataImporter,
    private val taskRepository: TaskRepository,
    private val habitRepository: com.averycorp.prismtask.data.repository.HabitRepository,
    internal val backendSyncPreferences: BackendSyncPreferences,
    internal val templatePreferences: TemplatePreferences,
    internal val authTokenPreferences: AuthTokenPreferences,
    private val calendarManager: CalendarManager,
    private val calendarSyncPreferences: CalendarSyncPreferences,
    private val billingManager: BillingManager,
    private val userPreferencesDataStore: com.averycorp.prismtask.data.preferences.UserPreferencesDataStore,
    private val boundaryRuleRepository: com.averycorp.prismtask.data.repository.BoundaryRuleRepository,
    private val moodEnergyRepository: com.averycorp.prismtask.data.repository.MoodEnergyRepository,
    private val medicationRefillRepository: com.averycorp.prismtask.data.repository.MedicationRefillRepository,
    private val checkInLogRepository: com.averycorp.prismtask.data.repository.CheckInLogRepository,
    private val clinicalReportPdfWriter: com.averycorp.prismtask.data.export.ClinicalReportPdfWriter,
    private val onboardingPreferences: OnboardingPreferences,
    private val widgetUpdateManager: com.averycorp.prismtask.widget.WidgetUpdateManager,
    private val ndPreferencesDataStore: com.averycorp.prismtask.data.preferences.NdPreferencesDataStore,
    private val templateSeeder: com.averycorp.prismtask.data.seed.TemplateSeeder,
    private val selfCareRepository: com.averycorp.prismtask.data.repository.SelfCareRepository,
    private val tourCardPreferences: TourCardPreferences,
    // Mental-Health-First Audit § G5: partial wipe of mood / check-in /
    // weekly-review / boundary / focus-release data. Distinct from the
    // full account-delete path on [SyncSettingsViewModel].
    private val mentalHealthDataWiper: com.averycorp.prismtask.data.privacy.MentalHealthDataWiper,
    // --- Sub-VMs extracted as part of T1.2 refactor ---
    private val themeSettings: ThemeSettingsViewModel,
    private val notificationSettings: NotificationSettingsViewModel,
    private val syncSettings: SyncSettingsViewModel,
    private val accessibilitySettings: AccessibilitySettingsViewModel,
    private val voiceSettings: VoiceSettingsViewModel
) : ViewModel() {
    // Shared snackbar message channel. Sub-VMs receive a reference to
    // [_messages] in their `attach` call so their operations can surface
    // user-facing strings through this coordinator without each owning a
    // separate SharedFlow.
    internal val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _checkInStreak = kotlinx.coroutines.flow.MutableStateFlow(0)
    val checkInStreak: StateFlow<Int> = _checkInStreak

    init {
        // Wire sub-VMs to viewModelScope so they own their StateFlow lifetimes.
        // Sync receives the shared messages channel so its operations can
        // surface snackbar messages through this coordinator's `messages` flow.
        themeSettings.attach(viewModelScope)
        notificationSettings.attach(viewModelScope)
        syncSettings.attach(viewModelScope, _messages)
        accessibilitySettings.attach(viewModelScope)
        voiceSettings.attach(viewModelScope)

        try {
            com.google.firebase.crashlytics.FirebaseCrashlytics
                .getInstance()
                .setCustomKey("screen", "SettingsScreen")
        } catch (_: Exception) {
        }
        viewModelScope.launch {
            try {
                val todayStart = java.util.Calendar
                    .getInstance()
                    .apply {
                        set(java.util.Calendar.HOUR_OF_DAY, 0)
                        set(java.util.Calendar.MINUTE, 0)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }.timeInMillis
                _checkInStreak.value = checkInLogRepository.currentStreak(todayStart)
            } catch (e: Exception) {
                android.util.Log.e("SettingsVM", "Failed to load check-in streak", e)
            }
        }
    }

    private val _isExportingClinicalReport = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isExportingClinicalReport: StateFlow<Boolean> = _isExportingClinicalReport

    private val _clinicalReportUri = kotlinx.coroutines.flow.MutableStateFlow<android.net.Uri?>(null)
    val clinicalReportUri: StateFlow<android.net.Uri?> = _clinicalReportUri

    fun clearClinicalReportUri() {
        _clinicalReportUri.value = null
    }

    fun exportClinicalReport() {
        if (_isExportingClinicalReport.value) return
        _isExportingClinicalReport.value = true
        viewModelScope.launch {
            try {
                val end = System.currentTimeMillis()
                val start = end - 30L * 24 * 60 * 60 * 1000
                val generator = com.averycorp.prismtask.domain.usecase
                    .ClinicalReportGenerator()
                val inputs = com.averycorp.prismtask.domain.usecase.ClinicalReportInputs(
                    userName = null,
                    dateRangeStart = start,
                    dateRangeEnd = end,
                    tasks = taskRepository.getAllTasksOnce(),
                    moodEnergyLogs = moodEnergyRepository.getRange(start, end),
                    medications = medicationRefillRepository.getAll()
                )
                val report = generator.generate(inputs)
                val uri = clinicalReportPdfWriter.write(appContext, report)
                _clinicalReportUri.value = uri
            } finally {
                _isExportingClinicalReport.value = false
            }
        }
    }

    // --- v1.3.0 User Preferences ---
    val appearancePrefs: StateFlow<com.averycorp.prismtask.data.preferences.AppearancePrefs> =
        userPreferencesDataStore.appearanceFlow
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                com.averycorp.prismtask.data.preferences
                    .AppearancePrefs()
            )

    val swipePrefs: StateFlow<com.averycorp.prismtask.data.preferences.SwipePrefs> =
        userPreferencesDataStore.swipeFlow
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                com.averycorp.prismtask.data.preferences
                    .SwipePrefs()
            )

    val taskDefaultPrefs: StateFlow<com.averycorp.prismtask.data.preferences.TaskDefaults> =
        userPreferencesDataStore.taskDefaultsFlow
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                com.averycorp.prismtask.data.preferences
                    .TaskDefaults()
            )

    val quickAddPrefs: StateFlow<com.averycorp.prismtask.data.preferences.QuickAddPrefs> =
        userPreferencesDataStore.quickAddFlow
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                com.averycorp.prismtask.data.preferences
                    .QuickAddPrefs()
            )

    /** Work-Life Balance preferences (v1.4.0 V1). */
    val workLifeBalancePrefs: StateFlow<com.averycorp.prismtask.data.preferences.WorkLifeBalancePrefs> =
        userPreferencesDataStore.workLifeBalanceFlow
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                com.averycorp.prismtask.data.preferences
                    .WorkLifeBalancePrefs()
            )

    fun setWorkLifeBalancePrefs(prefs: com.averycorp.prismtask.data.preferences.WorkLifeBalancePrefs) {
        viewModelScope.launch { userPreferencesDataStore.setWorkLifeBalance(prefs) }
    }

    /** Eisenhower auto-classification preferences (v1.4.x A2). */
    val eisenhowerPrefs: StateFlow<com.averycorp.prismtask.data.preferences.EisenhowerPrefs> =
        userPreferencesDataStore.eisenhowerFlow
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                com.averycorp.prismtask.data.preferences
                    .EisenhowerPrefs()
            )

    fun setEisenhowerAutoClassifyEnabled(enabled: Boolean) {
        viewModelScope.launch { userPreferencesDataStore.setEisenhowerAutoClassifyEnabled(enabled) }
    }

    /** Master AI-feature opt-out (PII egress audit, 2026-04-26). */
    val aiFeaturePrefs: StateFlow<com.averycorp.prismtask.data.preferences.AiFeaturePrefs> =
        userPreferencesDataStore.aiFeaturePrefsFlow
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                com.averycorp.prismtask.data.preferences
                    .AiFeaturePrefs()
            )

    fun setAiFeaturesEnabled(enabled: Boolean) {
        viewModelScope.launch { userPreferencesDataStore.setAiFeaturesEnabled(enabled) }
    }

    /** Per-feature AI opt-ins (F3 low-risk bundle). */
    val perFeatureAiPrefs: StateFlow<com.averycorp.prismtask.data.preferences.PerFeatureAiPrefs> =
        userPreferencesDataStore.perFeatureAiPrefsFlow
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                com.averycorp.prismtask.data.preferences.PerFeatureAiPrefs()
            )

    fun setAiChatFeatureEnabled(enabled: Boolean) {
        viewModelScope.launch { userPreferencesDataStore.setAiChatEnabled(enabled) }
    }

    fun setDailyBriefingFeatureEnabled(enabled: Boolean) {
        viewModelScope.launch { userPreferencesDataStore.setAiDailyBriefingEnabled(enabled) }
    }

    fun setSmartPomodoroFeatureEnabled(enabled: Boolean) {
        viewModelScope.launch { userPreferencesDataStore.setAiSmartPomodoroEnabled(enabled) }
    }

    fun setWeeklyPlannerFeatureEnabled(enabled: Boolean) {
        viewModelScope.launch { userPreferencesDataStore.setAiWeeklyPlannerEnabled(enabled) }
    }

    fun setMorningCheckInFeatureEnabled(enabled: Boolean) {
        viewModelScope.launch { userPreferencesDataStore.setAiMorningCheckInEnabled(enabled) }
    }

    fun setScreenshotImportFeatureEnabled(enabled: Boolean) {
        viewModelScope.launch { userPreferencesDataStore.setAiScreenshotImportEnabled(enabled) }
    }

    /** Boundary rules (v1.4.0 V3). */
    val boundaryRules: StateFlow<List<com.averycorp.prismtask.domain.model.BoundaryRule>> =
        boundaryRuleRepository
            .observeRules()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { boundaryRuleRepository.seedBuiltInIfEmpty() }
    }

    fun toggleBoundaryRule(rule: com.averycorp.prismtask.domain.model.BoundaryRule, enabled: Boolean) {
        viewModelScope.launch {
            boundaryRuleRepository.update(rule.copy(isEnabled = enabled))
        }
    }

    fun deleteBoundaryRule(rule: com.averycorp.prismtask.domain.model.BoundaryRule) {
        viewModelScope.launch { boundaryRuleRepository.delete(rule.id) }
    }

    fun addBoundaryRuleFromNlp(text: String): Boolean {
        val parsed = com.averycorp.prismtask.domain.usecase.BoundaryRuleParser
            .parse(text)
            ?: return false
        viewModelScope.launch { boundaryRuleRepository.insert(parsed) }
        return true
    }

    fun insertBoundaryRule(rule: com.averycorp.prismtask.domain.model.BoundaryRule) {
        viewModelScope.launch { boundaryRuleRepository.insert(rule) }
    }

    fun updateBoundaryRule(rule: com.averycorp.prismtask.domain.model.BoundaryRule) {
        viewModelScope.launch { boundaryRuleRepository.update(rule) }
    }

    // --- Brain Mode / ND preferences ---
    val ndPrefs: StateFlow<com.averycorp.prismtask.data.preferences.NdPreferences> =
        ndPreferencesDataStore.ndPreferencesFlow
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                com.averycorp.prismtask.data.preferences
                    .NdPreferences()
            )

    fun setAdhdMode(enabled: Boolean) {
        viewModelScope.launch { ndPreferencesDataStore.setAdhdMode(enabled) }
    }

    fun setCalmMode(enabled: Boolean) {
        viewModelScope.launch { ndPreferencesDataStore.setCalmMode(enabled) }
    }

    fun setFocusReleaseMode(enabled: Boolean) {
        viewModelScope.launch { ndPreferencesDataStore.setFocusReleaseMode(enabled) }
    }

    fun setGoodEnoughTimersEnabled(e: Boolean) {
        viewModelScope.launch { ndPreferencesDataStore.setGoodEnoughTimersEnabled(e) }
    }

    fun setDefaultGoodEnoughMinutes(m: Int) {
        viewModelScope.launch { ndPreferencesDataStore.setDefaultGoodEnoughMinutes(m) }
    }

    fun setGoodEnoughEscalation(e: com.averycorp.prismtask.data.preferences.GoodEnoughEscalation) {
        viewModelScope.launch { ndPreferencesDataStore.setGoodEnoughEscalation(e) }
    }

    fun setAntiReworkEnabled(e: Boolean) {
        viewModelScope.launch { ndPreferencesDataStore.setAntiReworkEnabled(e) }
    }

    fun setSoftWarningEnabled(e: Boolean) {
        viewModelScope.launch { ndPreferencesDataStore.setSoftWarningEnabled(e) }
    }

    fun setCoolingOffEnabled(e: Boolean) {
        viewModelScope.launch { ndPreferencesDataStore.setCoolingOffEnabled(e) }
    }

    fun setCoolingOffMinutes(m: Int) {
        viewModelScope.launch { ndPreferencesDataStore.setCoolingOffMinutes(m) }
    }

    fun setRevisionCounterEnabled(e: Boolean) {
        viewModelScope.launch { ndPreferencesDataStore.setRevisionCounterEnabled(e) }
    }

    fun setMaxRevisions(m: Int) {
        viewModelScope.launch { ndPreferencesDataStore.setMaxRevisions(m) }
    }

    fun setShipItCelebrationsEnabled(e: Boolean) {
        viewModelScope.launch { ndPreferencesDataStore.setShipItCelebrationsEnabled(e) }
    }

    fun setCelebrationIntensity(i: com.averycorp.prismtask.data.preferences.CelebrationIntensity) {
        viewModelScope.launch { ndPreferencesDataStore.setCelebrationIntensity(i) }
    }

    /** Forgiveness-first streak preferences (v1.4.0 V5). */
    val forgivenessPrefs: StateFlow<com.averycorp.prismtask.data.preferences.ForgivenessPrefs> =
        userPreferencesDataStore.forgivenessFlow
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                com.averycorp.prismtask.data.preferences
                    .ForgivenessPrefs()
            )

    fun setForgivenessPrefs(prefs: com.averycorp.prismtask.data.preferences.ForgivenessPrefs) {
        viewModelScope.launch { userPreferencesDataStore.setForgivenessPrefs(prefs) }
    }

    fun setCompactMode(enabled: Boolean) {
        viewModelScope.launch { userPreferencesDataStore.setCompactMode(enabled) }
    }

    fun setShowCardBorders(enabled: Boolean) {
        viewModelScope.launch { userPreferencesDataStore.setShowCardBorders(enabled) }
    }

    fun setCardCornerRadius(radius: Int) {
        viewModelScope.launch { userPreferencesDataStore.setCardCornerRadius(radius) }
    }

    fun setSwipeRight(action: com.averycorp.prismtask.domain.model.SwipeAction) {
        viewModelScope.launch { userPreferencesDataStore.setSwipeRight(action) }
    }

    fun setSwipeLeft(action: com.averycorp.prismtask.domain.model.SwipeAction) {
        viewModelScope.launch { userPreferencesDataStore.setSwipeLeft(action) }
    }

    fun setTaskDefaults(defaults: com.averycorp.prismtask.data.preferences.TaskDefaults) {
        viewModelScope.launch { userPreferencesDataStore.setTaskDefaults(defaults) }
    }

    fun setSmartDefaultsEnabled(enabled: Boolean) {
        viewModelScope.launch { userPreferencesDataStore.setSmartDefaultsEnabled(enabled) }
    }

    fun setQuickAddPrefs(prefs: com.averycorp.prismtask.data.preferences.QuickAddPrefs) {
        viewModelScope.launch { userPreferencesDataStore.setQuickAdd(prefs) }
    }

    // --- Voice Input (delegated to VoiceSettingsViewModel) ---
    val voiceInputEnabled: StateFlow<Boolean> get() = voiceSettings.voiceInputEnabled
    val voiceFeedbackEnabled: StateFlow<Boolean> get() = voiceSettings.voiceFeedbackEnabled
    val continuousModeEnabled: StateFlow<Boolean> get() = voiceSettings.continuousModeEnabled

    fun setVoiceInputEnabled(enabled: Boolean) = voiceSettings.setVoiceInputEnabled(enabled)
    fun setVoiceFeedbackEnabled(enabled: Boolean) = voiceSettings.setVoiceFeedbackEnabled(enabled)
    fun setContinuousModeEnabled(enabled: Boolean) = voiceSettings.setContinuousModeEnabled(enabled)

    // --- Accessibility (delegated to AccessibilitySettingsViewModel) ---
    val reduceMotionEnabled: StateFlow<Boolean> get() = accessibilitySettings.reduceMotionEnabled
    val highContrastEnabled: StateFlow<Boolean> get() = accessibilitySettings.highContrastEnabled
    val largeTouchTargetsEnabled: StateFlow<Boolean> get() = accessibilitySettings.largeTouchTargetsEnabled

    fun setReduceMotion(enabled: Boolean) = accessibilitySettings.setReduceMotion(enabled)
    fun setHighContrast(enabled: Boolean) = accessibilitySettings.setHighContrast(enabled)
    fun setLargeTouchTargets(enabled: Boolean) = accessibilitySettings.setLargeTouchTargets(enabled)

    // --- Widgets ---
    fun refreshWidgets() {
        viewModelScope.launch { widgetUpdateManager.updateAllWidgets() }
    }

    /**
     * Debug-only: re-runs the built-in starter seeders, replacing any
     * templates/steps still flagged as built-in with the current source-of-truth
     * lists. Triggered by long-pressing the version label on the Settings About
     * section — the UI wraps the call in a `BuildConfig.DEBUG` check, but the
     * side effects are gated here too so a mis-wired caller can't wipe user
     * data in a release build.
     */
    fun debugReseedDefaults() {
        if (!com.averycorp.prismtask.BuildConfig.DEBUG) return
        viewModelScope.launch {
            try {
                templateSeeder.reseedBuiltIns()
                selfCareRepository.reseedBuiltInDefaults()
                _messages.emit("Re-seeded built-in templates and routine steps.")
            } catch (e: Exception) {
                Log.e("SettingsVM", "Debug re-seed failed", e)
                _messages.emit("Re-seed failed: ${e.message ?: e::class.java.simpleName}")
            }
        }
    }

    // --- Subscription ---
    val userTier: StateFlow<UserTier> = billingManager.userTier
    val billingPeriod: StateFlow<BillingPeriod> = billingManager.billingPeriod
    val subscriptionState: StateFlow<SubscriptionState> = billingManager.proSubscriptionState
    val debugTierOverride: StateFlow<UserTier?> = billingManager.debugTierOverride
    val isAdmin: StateFlow<Boolean> = billingManager.isAdmin

    // --- Notification Settings (delegated to NotificationSettingsViewModel) ---
    val taskRemindersEnabled: StateFlow<Boolean> get() = notificationSettings.taskRemindersEnabled
    val timerAlertsEnabled: StateFlow<Boolean> get() = notificationSettings.timerAlertsEnabled
    val medicationRemindersEnabled: StateFlow<Boolean> get() = notificationSettings.medicationRemindersEnabled
    val habitNagSuppressionDays: StateFlow<Int> get() = notificationSettings.habitNagSuppressionDays
    val dailyBriefingEnabled: StateFlow<Boolean> get() = notificationSettings.dailyBriefingEnabled
    val eveningSummaryEnabled: StateFlow<Boolean> get() = notificationSettings.eveningSummaryEnabled
    val weeklySummaryEnabled: StateFlow<Boolean> get() = notificationSettings.weeklySummaryEnabled
    val weeklyTaskSummaryEnabled: StateFlow<Boolean> get() = notificationSettings.weeklyTaskSummaryEnabled
    val overloadAlertsEnabled: StateFlow<Boolean> get() = notificationSettings.overloadAlertsEnabled
    val reengagementEnabled: StateFlow<Boolean> get() = notificationSettings.reengagementEnabled
    val fullScreenNotificationsEnabled: StateFlow<Boolean> get() = notificationSettings.fullScreenNotificationsEnabled
    val overrideVolumeEnabled: StateFlow<Boolean> get() = notificationSettings.overrideVolumeEnabled
    val repeatingVibrationEnabled: StateFlow<Boolean> get() = notificationSettings.repeatingVibrationEnabled
    val notificationImportance: StateFlow<String> get() = notificationSettings.notificationImportance
    val defaultReminderOffset: StateFlow<Long> get() = notificationSettings.defaultReminderOffset

    fun setTaskRemindersEnabled(enabled: Boolean) = notificationSettings.setTaskRemindersEnabled(enabled)
    fun setTimerAlertsEnabled(enabled: Boolean) = notificationSettings.setTimerAlertsEnabled(enabled)
    fun setMedicationRemindersEnabled(enabled: Boolean) = notificationSettings.setMedicationRemindersEnabled(enabled)
    fun setHabitNagSuppressionDays(days: Int) = notificationSettings.setHabitNagSuppressionDays(days)
    fun setDailyBriefingEnabled(enabled: Boolean) = notificationSettings.setDailyBriefingEnabled(enabled)
    fun setEveningSummaryEnabled(enabled: Boolean) = notificationSettings.setEveningSummaryEnabled(enabled)
    fun setWeeklyHabitSummaryEnabled(enabled: Boolean) = notificationSettings.setWeeklyHabitSummaryEnabled(enabled)
    fun setWeeklyTaskSummaryEnabled(enabled: Boolean) = notificationSettings.setWeeklyTaskSummaryEnabled(enabled)
    fun setOverloadAlertsEnabled(enabled: Boolean) = notificationSettings.setOverloadAlertsEnabled(enabled)
    fun setReengagementEnabled(enabled: Boolean) = notificationSettings.setReengagementEnabled(enabled)
    fun setFullScreenNotificationsEnabled(enabled: Boolean) = notificationSettings.setFullScreenNotificationsEnabled(enabled)
    fun setOverrideVolumeEnabled(enabled: Boolean) = notificationSettings.setOverrideVolumeEnabled(enabled)
    fun setRepeatingVibrationEnabled(enabled: Boolean) = notificationSettings.setRepeatingVibrationEnabled(enabled)
    fun setNotificationImportance(level: String) = notificationSettings.setNotificationImportance(level)
    fun setDefaultReminderOffset(offsetMs: Long) = notificationSettings.setDefaultReminderOffset(offsetMs)

    // --- Theme (delegated to ThemeSettingsViewModel) ---
    val themeMode: StateFlow<String> get() = themeSettings.themeMode
    val accentColor: StateFlow<String> get() = themeSettings.accentColor
    val recentCustomColors: StateFlow<List<String>> get() = themeSettings.recentCustomColors
    val backgroundColor: StateFlow<String> get() = themeSettings.backgroundColor
    val surfaceColor: StateFlow<String> get() = themeSettings.surfaceColor
    val errorColor: StateFlow<String> get() = themeSettings.errorColor
    val fontScale: StateFlow<Float> get() = themeSettings.fontScale
    val priorityColorNone: StateFlow<String> get() = themeSettings.priorityColorNone
    val priorityColorLow: StateFlow<String> get() = themeSettings.priorityColorLow
    val priorityColorMedium: StateFlow<String> get() = themeSettings.priorityColorMedium
    val priorityColorHigh: StateFlow<String> get() = themeSettings.priorityColorHigh
    val priorityColorUrgent: StateFlow<String> get() = themeSettings.priorityColorUrgent

    // --- Dashboard ---
    val sectionOrder: StateFlow<List<String>> = dashboardPreferences
        .getSectionOrder()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardPreferences.DEFAULT_ORDER)

    val hiddenSections: StateFlow<Set<String>> = dashboardPreferences
        .getHiddenSections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val progressStyle: StateFlow<String> = dashboardPreferences
        .getProgressStyle()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "ring")

    // --- Navigation ---
    val tabOrder: StateFlow<List<String>> = tabPreferences
        .getTabOrder()
        .map { order -> order + ALL_BOTTOM_NAV_ITEMS.map { it.route }.filter { it !in order } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TabPreferences.DEFAULT_ORDER)

    val hiddenTabs: StateFlow<Set<String>> = tabPreferences
        .getHiddenTabs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // --- Task Behavior ---
    val defaultSort: StateFlow<String> = taskBehaviorPreferences
        .getDefaultSort()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "DUE_DATE")

    val defaultViewMode: StateFlow<String> = taskBehaviorPreferences
        .getDefaultViewMode()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "UPCOMING")

    val urgencyWeights: StateFlow<UrgencyWeights> = taskBehaviorPreferences
        .getUrgencyWeights()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UrgencyWeights())

    val reminderPresets: StateFlow<List<Long>> = taskBehaviorPreferences
        .getReminderPresets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf(0L, 900_000L, 1_800_000L, 3_600_000L, 86_400_000L))

    val firstDayOfWeek: StateFlow<DayOfWeek> = taskBehaviorPreferences
        .getFirstDayOfWeek()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DayOfWeek.MONDAY)

    val dayStartHour: StateFlow<Int> = taskBehaviorPreferences
        .getDayStartHour()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val dayStartMinute: StateFlow<Int> = taskBehaviorPreferences
        .getDayStartMinute()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // --- Timer / Pomodoro ---
    val timerWorkDurationSeconds: StateFlow<Int> = timerPreferences
        .getWorkDurationSeconds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TimerPreferences.DEFAULT_WORK_SECONDS)

    val timerBreakDurationSeconds: StateFlow<Int> = timerPreferences
        .getBreakDurationSeconds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TimerPreferences.DEFAULT_BREAK_SECONDS)

    val timerLongBreakDurationSeconds: StateFlow<Int> = timerPreferences
        .getLongBreakDurationSeconds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TimerPreferences.DEFAULT_LONG_BREAK_SECONDS)

    val timerCustomDurationSeconds: StateFlow<Int> = timerPreferences
        .getCustomDurationSeconds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TimerPreferences.DEFAULT_CUSTOM_SECONDS)

    val pomodoroAvailableMinutes: StateFlow<Int> = timerPreferences
        .getPomodoroAvailableMinutes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TimerPreferences.DEFAULT_AVAILABLE_MINUTES)

    val pomodoroFocusPreference: StateFlow<String> = timerPreferences
        .getPomodoroFocusPreference()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TimerPreferences.DEFAULT_FOCUS_PREFERENCE)

    val timerKeepScreenOn: StateFlow<Boolean> = timerPreferences
        .getKeepScreenOn()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val timerBuzzUntilDismissed: StateFlow<Boolean> = timerPreferences
        .getBuzzUntilDismissed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val timerOverrideVolume: StateFlow<Boolean> = timerPreferences
        .getOverrideVolume()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val timerAlarmVolumePercent: StateFlow<Int> = timerPreferences
        .getAlarmVolumePercent()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            TimerPreferences.DEFAULT_ALARM_VOLUME_PERCENT
        )

    val timerAlarmSoundId: StateFlow<String> = timerPreferences
        .getAlarmSoundId()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            TimerPreferences.DEFAULT_ALARM_SOUND_ID
        )

    val timerRingDurationSeconds: StateFlow<Int> = timerPreferences
        .getRingDurationSeconds()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            TimerPreferences.DEFAULT_RING_DURATION_SECONDS
        )

    val timerVibrateEnabled: StateFlow<Boolean> = timerPreferences
        .getVibrateEnabled()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            TimerPreferences.DEFAULT_VIBRATE_ENABLED
        )

    val timerVibrationDurationSeconds: StateFlow<Int> = timerPreferences
        .getVibrationDurationSeconds()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            TimerPreferences.DEFAULT_VIBRATION_DURATION_SECONDS
        )

    // ---- A2 Pomodoro+ AI Coaching toggles ---------------------------
    // All three default true — UI reflects that via the initial value.
    val pomodoroPreSessionCoachingEnabled: StateFlow<Boolean> = timerPreferences
        .getPomodoroPreSessionCoachingEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val pomodoroBreakCoachingEnabled: StateFlow<Boolean> = timerPreferences
        .getPomodoroBreakCoachingEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val pomodoroRecapCoachingEnabled: StateFlow<Boolean> = timerPreferences
        .getPomodoroRecapCoachingEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setTimerWorkDurationMinutes(minutes: Int) {
        viewModelScope.launch { timerPreferences.setWorkDurationSeconds(minutes * 60) }
    }

    fun setTimerBreakDurationMinutes(minutes: Int) {
        viewModelScope.launch { timerPreferences.setBreakDurationSeconds(minutes * 60) }
    }

    fun setTimerLongBreakDurationMinutes(minutes: Int) {
        viewModelScope.launch { timerPreferences.setLongBreakDurationSeconds(minutes * 60) }
    }

    fun setTimerCustomDurationMinutes(minutes: Int) {
        viewModelScope.launch { timerPreferences.setCustomDurationSeconds(minutes * 60) }
    }

    fun setPomodoroAvailableMinutes(minutes: Int) {
        viewModelScope.launch { timerPreferences.setPomodoroAvailableMinutes(minutes) }
    }

    fun setPomodoroFocusPreference(preference: String) {
        viewModelScope.launch { timerPreferences.setPomodoroFocusPreference(preference) }
    }

    fun setTimerKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { timerPreferences.setKeepScreenOn(enabled) }
    }

    fun setTimerBuzzUntilDismissed(enabled: Boolean) {
        viewModelScope.launch { timerPreferences.setBuzzUntilDismissed(enabled) }
    }

    fun setTimerOverrideVolume(enabled: Boolean) {
        viewModelScope.launch { timerPreferences.setOverrideVolume(enabled) }
    }

    fun setTimerAlarmVolumePercent(percent: Int) {
        viewModelScope.launch { timerPreferences.setAlarmVolumePercent(percent) }
    }

    fun setTimerAlarmSoundId(soundId: String) {
        viewModelScope.launch { timerPreferences.setAlarmSoundId(soundId) }
    }

    fun setTimerRingDurationSeconds(seconds: Int) {
        viewModelScope.launch { timerPreferences.setRingDurationSeconds(seconds) }
    }

    fun setTimerVibrateEnabled(enabled: Boolean) {
        viewModelScope.launch { timerPreferences.setVibrateEnabled(enabled) }
    }

    fun setTimerVibrationDurationSeconds(seconds: Int) {
        viewModelScope.launch { timerPreferences.setVibrationDurationSeconds(seconds) }
    }

    fun setPomodoroPreSessionCoachingEnabled(enabled: Boolean) {
        viewModelScope.launch { timerPreferences.setPomodoroPreSessionCoachingEnabled(enabled) }
    }

    fun setPomodoroBreakCoachingEnabled(enabled: Boolean) {
        viewModelScope.launch { timerPreferences.setPomodoroBreakCoachingEnabled(enabled) }
    }

    fun setPomodoroRecapCoachingEnabled(enabled: Boolean) {
        viewModelScope.launch { timerPreferences.setPomodoroRecapCoachingEnabled(enabled) }
    }

    // --- Habits / Streaks ---
    val streakMaxMissedDays: StateFlow<Int> = habitListPreferences
        .getStreakMaxMissedDays()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HabitListPreferences.DEFAULT_STREAK_MAX_MISSED_DAYS)

    fun setStreakMaxMissedDays(days: Int) {
        viewModelScope.launch { habitListPreferences.setStreakMaxMissedDays(days) }
    }

    val todaySkipAfterCompleteDays: StateFlow<Int> = habitListPreferences
        .getTodaySkipAfterCompleteDays()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            HabitListPreferences.DEFAULT_TODAY_SKIP_AFTER_COMPLETE_DAYS
        )

    fun setTodaySkipAfterCompleteDays(days: Int) {
        viewModelScope.launch { habitListPreferences.setTodaySkipAfterCompleteDays(days) }
    }

    val todaySkipBeforeScheduleDays: StateFlow<Int> = habitListPreferences
        .getTodaySkipBeforeScheduleDays()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            HabitListPreferences.DEFAULT_TODAY_SKIP_BEFORE_SCHEDULE_DAYS
        )

    fun setTodaySkipBeforeScheduleDays(days: Int) {
        viewModelScope.launch { habitListPreferences.setTodaySkipBeforeScheduleDays(days) }
    }

    // --- Modes ---
    val selfCareEnabled: StateFlow<Boolean> = habitListPreferences
        .isSelfCareEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val medicationEnabled: StateFlow<Boolean> = habitListPreferences
        .isMedicationEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val schoolEnabled: StateFlow<Boolean> = habitListPreferences
        .isSchoolEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val leisureEnabled: StateFlow<Boolean> = habitListPreferences
        .isLeisureEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val houseworkEnabled: StateFlow<Boolean> = habitListPreferences
        .isHouseworkEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setSelfCareEnabled(enabled: Boolean) {
        viewModelScope.launch { habitListPreferences.setSelfCareEnabled(enabled) }
    }

    fun setMedicationEnabled(enabled: Boolean) {
        viewModelScope.launch { habitListPreferences.setMedicationEnabled(enabled) }
    }

    fun setSchoolEnabled(enabled: Boolean) {
        viewModelScope.launch { habitListPreferences.setSchoolEnabled(enabled) }
    }

    fun setLeisureEnabled(enabled: Boolean) {
        viewModelScope.launch { habitListPreferences.setLeisureEnabled(enabled) }
    }

    fun setHouseworkEnabled(enabled: Boolean) {
        viewModelScope.launch { habitListPreferences.setHouseworkEnabled(enabled) }
    }

    // --- Archive / Data ---
    val autoArchiveDays: StateFlow<Int> = archivePreferences
        .getAutoArchiveDays()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 7)

    val archivedCount: StateFlow<Int> = taskRepository
        .getArchivedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // --- Google Calendar API Sync (delegated to SyncSettingsViewModel) ---
    val isGCalConnected: StateFlow<Boolean> get() = syncSettings.isGCalConnected
    val gCalAccountEmail: StateFlow<String?> get() = syncSettings.gCalAccountEmail
    val gCalSyncEnabled: StateFlow<Boolean> get() = syncSettings.gCalSyncEnabled
    val gCalSyncCalendarId: StateFlow<String> get() = syncSettings.gCalSyncCalendarId
    val gCalSyncDirection: StateFlow<String> get() = syncSettings.gCalSyncDirection
    val gCalShowEvents: StateFlow<Boolean> get() = syncSettings.gCalShowEvents
    val gCalSyncCompletedTasks: StateFlow<Boolean> get() = syncSettings.gCalSyncCompletedTasks
    val gCalSyncFrequency: StateFlow<String> get() = syncSettings.gCalSyncFrequency
    val gCalLastSyncTimestamp: StateFlow<Long> get() = syncSettings.gCalLastSyncTimestamp
    val gCalAvailableCalendars: StateFlow<List<CalendarInfo>> get() = syncSettings.gCalAvailableCalendars
    val isGCalSyncing: StateFlow<Boolean> get() = syncSettings.isGCalSyncing
    val calendarConsentIntent: SharedFlow<Intent> get() = syncSettings.calendarConsentIntent

    fun connectGoogleCalendar() = syncSettings.connectGoogleCalendar()
    fun handleCalendarConsentResult(data: Intent?) = syncSettings.handleCalendarConsentResult(data)
    fun disconnectGoogleCalendar() = syncSettings.disconnectGoogleCalendar()
    fun loadGCalCalendars() = syncSettings.loadGCalCalendars()
    fun setGCalSyncEnabled(enabled: Boolean) = syncSettings.setGCalSyncEnabled(enabled)
    fun setGCalSyncCalendarId(calendarId: String) = syncSettings.setGCalSyncCalendarId(calendarId)
    fun setGCalSyncDirection(direction: String) = syncSettings.setGCalSyncDirection(direction)
    fun setGCalShowEvents(show: Boolean) = syncSettings.setGCalShowEvents(show)
    fun setGCalSyncCompletedTasks(sync: Boolean) = syncSettings.setGCalSyncCompletedTasks(sync)
    fun setGCalSyncFrequency(frequency: String) = syncSettings.setGCalSyncFrequency(frequency)
    fun syncGCalNow() = syncSettings.syncGCalNow()

    // --- Auth + Shared State (delegated to SyncSettingsViewModel where applicable) ---
    val isSignedIn: StateFlow<Boolean> get() = syncSettings.isSignedIn
    val userEmail: String? get() = syncSettings.userEmail

    internal val _pendingJsonExport = MutableStateFlow<String?>(null)
    val pendingJsonExport: StateFlow<String?> = _pendingJsonExport

    internal val _pendingCsvExport = MutableStateFlow<String?>(null)
    val pendingCsvExport: StateFlow<String?> = _pendingCsvExport

    val isSyncing: StateFlow<Boolean> get() = syncSettings.isSyncing

    internal val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting

    internal val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting

    // --- Theme setters (delegated to ThemeSettingsViewModel) ---
    fun setThemeMode(mode: String) = themeSettings.setThemeMode(mode)
    fun setAccentColor(hex: String) = themeSettings.setAccentColor(hex)
    fun setCustomAccentColor(hex: String) = themeSettings.setCustomAccentColor(hex)
    fun setBackgroundColor(hex: String) = themeSettings.setBackgroundColor(hex)
    fun setSurfaceColor(hex: String) = themeSettings.setSurfaceColor(hex)
    fun setErrorColor(hex: String) = themeSettings.setErrorColor(hex)
    fun setFontScale(scale: Float) = themeSettings.setFontScale(scale)
    fun setPriorityColor(level: Int, hex: String) = themeSettings.setPriorityColor(level, hex)
    fun resetColorOverrides() = themeSettings.resetColorOverrides()

    // --- Dashboard setters ---
    fun setSectionOrder(order: List<String>) {
        viewModelScope.launch { dashboardPreferences.setSectionOrder(order) }
    }

    fun setHiddenSections(hidden: Set<String>) {
        viewModelScope.launch { dashboardPreferences.setHiddenSections(hidden) }
    }

    fun setProgressStyle(style: String) {
        viewModelScope.launch { dashboardPreferences.setProgressStyle(style) }
    }

    fun resetDashboardDefaults() {
        viewModelScope.launch { dashboardPreferences.resetToDefaults() }
    }

    // --- Navigation setters ---
    fun setTabOrder(order: List<String>) {
        viewModelScope.launch { tabPreferences.setTabOrder(order) }
    }

    fun setHiddenTabs(hidden: Set<String>) {
        viewModelScope.launch { tabPreferences.setHiddenTabs(hidden) }
    }

    fun resetTabDefaults() {
        viewModelScope.launch { tabPreferences.resetToDefaults() }
    }

    // --- Task Behavior setters ---
    fun setDefaultSort(sort: String) {
        viewModelScope.launch { taskBehaviorPreferences.setDefaultSort(sort) }
    }

    fun setDefaultViewMode(mode: String) {
        viewModelScope.launch { taskBehaviorPreferences.setDefaultViewMode(mode) }
    }

    fun setUrgencyWeights(weights: UrgencyWeights) {
        viewModelScope.launch { taskBehaviorPreferences.setUrgencyWeights(weights) }
    }

    fun setReminderPresets(presets: List<Long>) {
        viewModelScope.launch { taskBehaviorPreferences.setReminderPresets(presets) }
    }

    fun setFirstDayOfWeek(day: DayOfWeek) {
        viewModelScope.launch { taskBehaviorPreferences.setFirstDayOfWeek(day) }
    }

    fun setDayStartHour(hour: Int) {
        viewModelScope.launch { taskBehaviorPreferences.setDayStartHour(hour) }
    }

    fun setStartOfDay(hour: Int, minute: Int) {
        viewModelScope.launch {
            taskBehaviorPreferences.setStartOfDay(hour, minute)
            // Reschedule the daily rollover worker to fire at the new boundary.
            com.averycorp.prismtask.workers.DailyResetWorker.schedule(
                appContext,
                hour,
                minute
            )
        }
    }

    fun resetTaskBehaviorDefaults() {
        viewModelScope.launch { taskBehaviorPreferences.resetToDefaults() }
    }

    // --- Archive ---
    fun setAutoArchiveDays(days: Int) {
        viewModelScope.launch { archivePreferences.setAutoArchiveDays(days) }
    }

    // Firebase sync / sign-out / account deletion — all delegated to
    // SyncSettingsViewModel.
    fun onSync() = syncSettings.onSync()
    fun onSignOut() = syncSettings.onSignOut()

    val isDeletingAccount: StateFlow<Boolean> get() = syncSettings.isDeletingAccount
    val accountDeletionCompleted: SharedFlow<Unit> get() = syncSettings.accountDeletionCompleted

    /** See [SyncSettingsViewModel.onRequestAccountDeletion]. */
    fun onRequestAccountDeletion() = syncSettings.onRequestAccountDeletion()

    private val _isResetting = MutableStateFlow(false)
    val isResetting: StateFlow<Boolean> = _isResetting

    // --- Mental-Health-First Audit § G5 ---
    // Partial-wipe state for the "Delete Mental Health Data" privacy
    // action. Disambiguated from [isResetting] (which is the full
    // app-data reset) and [isDeletingAccount] (which is the full
    // account-delete path).
    private val _isWipingMentalHealthData = MutableStateFlow(false)
    val isWipingMentalHealthData: StateFlow<Boolean> = _isWipingMentalHealthData

    /**
     * Run the Mental-Health-First § G5 partial wipe — mood logs, check-ins,
     * weekly reviews, boundary rules, focus-release logs (+ their cloud
     * mirror). Local wipe is transactional and load-bearing; cloud wipe is
     * best-effort. Surfaces a snackbar message with the outcome so the user
     * knows whether the cloud copy was reached.
     *
     * Also clears [BackendSyncPreferences.lastSyncAtFlow] so the next
     * incremental pull doesn't carry forward a high-water mark from before
     * the wipe — re-syncing under the new mark won't try to re-download
     * the rows the user just asked to delete (their cloud copies are gone
     * too if the cloud step succeeded; if it didn't, the user is signed
     * out or offline and there is nothing to re-pull from).
     */
    fun wipeMentalHealthData() {
        if (_isWipingMentalHealthData.value) return
        _isWipingMentalHealthData.value = true
        viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    mentalHealthDataWiper.wipeMentalHealthData()
                }
                when (outcome) {
                    is com.averycorp.prismtask.data.privacy.MentalHealthDataWiper.WipeResult.Success -> {
                        backendSyncPreferences.clear()
                        _messages.emit("Mental health data deleted.")
                    }
                    is com.averycorp.prismtask.data.privacy.MentalHealthDataWiper.WipeResult.LocalOnly -> {
                        backendSyncPreferences.clear()
                        _messages.emit(
                            "Local data cleared; cloud data will sync when you reconnect."
                        )
                    }
                    is com.averycorp.prismtask.data.privacy.MentalHealthDataWiper.WipeResult.Failed -> {
                        Log.e("SettingsVM", "MH data wipe failed", outcome.cause)
                        _messages.emit("Couldn't delete mental health data. Try again.")
                    }
                }
            } finally {
                _isWipingMentalHealthData.value = false
            }
        }
    }

    /**
     * UI state for the "Clean Up Duplicates" flow. When the user taps the
     * button, the app scans for duplicate tasks/habits and exposes the
     * result here so the Settings UI can show a confirmation dialog.
     */
    data class DuplicateCleanupState(
        val isScanning: Boolean = false,
        val isDeleting: Boolean = false,
        val pendingPreview: Preview? = null,
        val noDuplicatesFound: Boolean = false
    ) {
        data class Preview(val taskCount: Int, val habitCount: Int, val projectCount: Int) {
            val total: Int get() = taskCount + habitCount + projectCount
        }
    }

    private val _duplicateCleanupState = MutableStateFlow(DuplicateCleanupState())
    val duplicateCleanupState: StateFlow<DuplicateCleanupState> = _duplicateCleanupState

    /**
     * Scans active tasks and habits for duplicates (same title + due date /
     * same name + frequency, or same templateKey for built-in habits). Updates
     * [duplicateCleanupState] with the result so the UI can show a confirmation
     * dialog or a "no duplicates" message.
     */
    fun scanForDuplicates() {
        if (_duplicateCleanupState.value.isScanning || _duplicateCleanupState.value.isDeleting) return
        viewModelScope.launch {
            _duplicateCleanupState.value = DuplicateCleanupState(isScanning = true)
            try {
                val result = findDuplicateIds()
                val habitCount = result.habitMerges.sumOf { it.loserIds.size }
                _duplicateCleanupState.value =
                    if (result.taskIds.isEmpty() && habitCount == 0 && result.projectIds.isEmpty()) {
                        DuplicateCleanupState(noDuplicatesFound = true)
                    } else {
                        DuplicateCleanupState(
                            pendingPreview = DuplicateCleanupState.Preview(
                                result.taskIds.size, habitCount, result.projectIds.size
                            )
                        )
                    }
            } catch (e: Exception) {
                Log.e("SettingsVM", "Duplicate scan failed", e)
                _duplicateCleanupState.value = DuplicateCleanupState()
                _messages.emit("Couldn't scan for duplicates")
            }
        }
    }

    /**
     * Re-runs detection and deletes every duplicate task/habit/project found,
     * keeping the "most complete" entry in each group. For habits, completions
     * are reassigned to the keeper before deletion so streak history is
     * preserved.
     */
    fun confirmDeleteDuplicates() {
        val current = _duplicateCleanupState.value
        if (current.pendingPreview == null || current.isDeleting) return
        viewModelScope.launch {
            _duplicateCleanupState.value = current.copy(isDeleting = true)
            try {
                val result = findDuplicateIds()
                val completionDao = database.habitCompletionDao()
                result.taskIds.forEach { taskRepository.deleteTask(it) }
                for (merge in result.habitMerges) {
                    for (loserId in merge.loserIds) {
                        completionDao.reassignHabitId(
                            oldHabitId = loserId,
                            newHabitId = merge.keeperId
                        )
                        habitRepository.deleteHabit(loserId)
                    }
                }
                result.projectIds.forEach { database.projectDao().deleteById(it) }
                val habitCount = result.habitMerges.sumOf { it.loserIds.size }
                _messages.emit(
                    buildString {
                        val parts = listOfNotNull(
                            if (result.taskIds.isNotEmpty()) {
                                "${result.taskIds.size} duplicate task${if (result.taskIds.size == 1) "" else "s"}"
                            } else {
                                null
                            },
                            if (habitCount > 0) "$habitCount duplicate habit${if (habitCount == 1) "" else "s"}" else null,
                            if (result.projectIds.isNotEmpty()) {
                                "${result.projectIds.size} duplicate project${if (result.projectIds.size == 1) "" else "s"}"
                            } else {
                                null
                            }
                        )
                        append("Deleted ")
                        append(parts.joinToString(" and "))
                    }
                )
            } catch (e: Exception) {
                Log.e("SettingsVM", "Duplicate cleanup failed", e)
                _messages.emit("Duplicate cleanup failed")
            } finally {
                _duplicateCleanupState.value = DuplicateCleanupState()
            }
        }
    }

    /** Dismisses the confirmation / "no duplicates found" dialog. */
    fun dismissDuplicateDialog() {
        _duplicateCleanupState.value = DuplicateCleanupState()
    }

    private data class DuplicateScanResult(
        val taskIds: List<Long>,
        val habitMerges: List<com.averycorp.prismtask.domain.usecase.DuplicateCleanupPlanner.HabitMerge>,
        val projectIds: List<Long>
    )

    private suspend fun findDuplicateIds(): DuplicateScanResult =
        withContext(Dispatchers.IO) {
            val taskDao = database.taskDao()
            val tagDao = database.tagDao()
            val habitDao = database.habitDao()
            val completionDao = database.habitCompletionDao()
            val logDao = database.habitLogDao()
            val projectDao = database.projectDao()

            val tasks = taskDao.getAllTasksOnce()
            val taskCandidates = tasks.filter {
                it.archivedAt == null && !it.isCompleted && it.parentTaskId == null
            }
            val subtasksByParent = tasks.filter { it.parentTaskId != null }
                .groupBy { it.parentTaskId!! }
            val taskExtras = taskCandidates.associate { t ->
                t.id to com.averycorp.prismtask.domain.usecase.DuplicateCleanupPlanner.TaskExtras(
                    subtaskCount = subtasksByParent[t.id]?.size ?: 0,
                    tagCount = tagDao.getTagIdsForTaskOnce(t.id).size
                )
            }
            val taskIds = com.averycorp.prismtask.domain.usecase.DuplicateCleanupPlanner
                .findTaskDuplicatesToDelete(tasks, taskExtras)

            val habits = habitDao.getAllHabitsOnce()
            val compsByHabit = completionDao.getAllCompletionsOnce().groupBy { it.habitId }
            val logsByHabit = logDao.getAllLogsOnce().groupBy { it.habitId }
            val habitExtras = habits.associate { h ->
                h.id to com.averycorp.prismtask.domain.usecase.DuplicateCleanupPlanner.HabitExtras(
                    completionCount = compsByHabit[h.id]?.size ?: 0,
                    logCount = logsByHabit[h.id]?.size ?: 0
                )
            }
            val habitMerges = com.averycorp.prismtask.domain.usecase.DuplicateCleanupPlanner
                .planHabitDuplicates(habits, habitExtras)

            val projects = projectDao.getAllProjectsOnce()
            val projectIds = com.averycorp.prismtask.domain.usecase.DuplicateCleanupPlanner
                .findProjectDuplicatesToDelete(projects)

            DuplicateScanResult(taskIds, habitMerges, projectIds)
        }

    /**
     * Granular reset based on [options]. Executes each selected category in
     * order, clears local Room tables and DataStore prefs as needed, then calls
     * [onDone] with a flag indicating whether to navigate to Onboarding.
     * Backend errors do not block local deletion; a partial-success message is
     * shown instead.
     */
    fun resetAppData(
        options: com.averycorp.prismtask.ui.components.dialogs.ResetOptions,
        onDone: (navigateToOnboarding: Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            _isResetting.value = true
            try {
                withContext(Dispatchers.IO) {
                    if (options.tasksAndProjects) {
                        database.taskDao().deleteAll()
                        database.taskDao().deleteAllTaskTagCrossRefs()
                        database.projectDao().deleteAll()
                        database.attachmentDao().deleteAll()
                        database.taskCompletionDao().deleteAll()
                    }
                    if (options.habitsAndHistory) {
                        database.habitDao().deleteAll()
                        database.habitCompletionDao().deleteAll()
                        database.habitLogDao().deleteAll()
                    }
                    if (options.tags) {
                        database.tagDao().deleteAll()
                        database.tagDao().deleteAllCrossRefs()
                    }
                    if (options.templates) {
                        database.taskTemplateDao().deleteAll()
                        database.habitTemplateDao().deleteAll()
                        database.projectTemplateDao().deleteAll()
                    }
                    if (options.calendarSyncData) {
                        database.calendarSyncDao().deleteAll()
                    }
                    // Any data wipe invalidates the local↔cloud_id mappings; drop
                    // pending sync actions so a future sign-in doesn't try to
                    // delete or update entities that no longer exist locally.
                    val anyDataReset = options.tasksAndProjects ||
                        options.habitsAndHistory ||
                        options.tags ||
                        options.templates ||
                        options.calendarSyncData
                    if (anyDataReset) {
                        database.syncMetadataDao().deleteAll()
                    }
                }
                if (options.calendarSyncData) {
                    calendarSyncPreferences.clearAll()
                    calendarManager.disconnectCalendar()
                }
                if (options.preferencesAndSettings) {
                    themePreferences.clearAll()
                    archivePreferences.clearAll()
                    dashboardPreferences.resetToDefaults()
                    tabPreferences.resetToDefaults()
                    taskBehaviorPreferences.resetToDefaults()
                    // Leisure Budget v2.0: v1.x leisure prefs file
                    // retired; no-op here. The new LeisureBudgetPreferences
                    // is wiped via Room table clear if Settings → Wipe
                    // Data eventually adds DAO-level clears.
                    habitListPreferences.clearAll()
                    backendSyncPreferences.clear()
                    templatePreferences.clear()
                    userPreferencesDataStore.clearAll()
                    // Auth tokens and pro status cache are intentionally preserved.
                }
                if (options.restartOnboarding) {
                    onboardingPreferences.resetOnboarding()
                    tourCardPreferences.resetTourCard()
                }
                _messages.emit("App data has been reset")
                onDone(options.restartOnboarding)
            } catch (e: Exception) {
                Log.e("SettingsVM", "Reset failed", e)
                _messages.emit("Reset failed")
            } finally {
                _isResetting.value = false
            }
        }
    }

    fun launchUpgrade(activity: android.app.Activity, period: BillingPeriod = BillingPeriod.MONTHLY) {
        viewModelScope.launch {
            try {
                billingManager.launchPurchaseFlow(activity, period)
            } catch (e: Exception) {
                _messages.emit("Couldn't start purchase")
            }
        }
    }

    fun restorePurchases() {
        viewModelScope.launch {
            try {
                billingManager.restorePurchases()
                _messages.emit("Purchases restored")
            } catch (e: Exception) {
                _messages.emit("Couldn't restore purchases")
            }
        }
    }

    /** Debug-only: set an in-memory tier override for testing gated features. */
    fun setDebugTier(tier: UserTier) {
        billingManager.setDebugTier(tier)
    }

    /** Debug-only: clear the tier override and revert to the real billing state. */
    fun clearDebugTier() {
        billingManager.clearDebugTier()
    }

}
