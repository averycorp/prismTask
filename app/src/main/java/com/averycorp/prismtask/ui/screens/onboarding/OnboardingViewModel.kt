package com.averycorp.prismtask.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.preferences.A11yPreferences
import com.averycorp.prismtask.data.preferences.ForgivenessPrefs
import com.averycorp.prismtask.data.preferences.HabitListPreferences
import com.averycorp.prismtask.data.preferences.NdPreferencesDataStore
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.data.preferences.OnboardingPreferences
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.preferences.ThemePreferences
import com.averycorp.prismtask.data.preferences.TourCardPreferences
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.preferences.VoicePreferences
import com.averycorp.prismtask.data.remote.AuthManager
import com.averycorp.prismtask.data.remote.CanonicalOnboardingSync
import com.averycorp.prismtask.data.remote.SyncService
import com.averycorp.prismtask.data.remote.sync.PrismSyncLogger
import com.averycorp.prismtask.data.repository.SelfCareRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.ui.screens.templates.TemplateSelections
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel
@Inject
constructor(
    private val onboardingPreferences: OnboardingPreferences,
    private val themePreferences: ThemePreferences,
    private val ndPreferencesDataStore: NdPreferencesDataStore,
    private val authManager: AuthManager,
    private val syncService: SyncService,
    private val taskRepository: TaskRepository,
    private val selfCareRepository: SelfCareRepository,
    private val userPreferencesDataStore: UserPreferencesDataStore,
    private val taskBehaviorPreferences: TaskBehaviorPreferences,
    private val canonicalOnboardingSync: CanonicalOnboardingSync,
    private val habitListPreferences: HabitListPreferences,
    private val a11yPreferences: A11yPreferences,
    private val notificationPreferences: NotificationPreferences,
    private val voicePreferences: VoicePreferences,
    private val logger: PrismSyncLogger,
    private val tourCardPreferences: TourCardPreferences
) : ViewModel() {
    val hasCompletedOnboarding: StateFlow<Boolean> = onboardingPreferences
        .hasCompletedOnboarding()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Preview mode — admin replay of the tutorial. When true, every mutating
     * method (preference setters, task creation, sign-in, template seeding,
     * and `completeOnboarding()`) short-circuits so the visual flow runs end
     * to end without changing the account. The screen sets this once via
     * [setPreviewMode] from the route's `preview` arg.
     */
    private val _isPreviewMode = MutableStateFlow(false)
    val isPreviewMode: StateFlow<Boolean> = _isPreviewMode.asStateFlow()

    fun setPreviewMode(enabled: Boolean) {
        _isPreviewMode.value = enabled
    }

    private inline fun ifNotPreview(block: () -> Unit) {
        if (!_isPreviewMode.value) block()
    }

    private val _signInState = MutableStateFlow<SignInState>(SignInState.NotSignedIn)
    val signInState: StateFlow<SignInState> = _signInState.asStateFlow()

    val themeMode: StateFlow<String> = themePreferences
        .getThemeMode()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "system")

    val accentColor: StateFlow<String> = themePreferences
        .getAccentColor()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "#2563EB")

    private val _templateSelections = MutableStateFlow(TemplateSelections())
    val templateSelections: StateFlow<TemplateSelections> = _templateSelections.asStateFlow()

    val selfCareEnabled: StateFlow<Boolean> = habitListPreferences
        .isSelfCareEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val medicationEnabled: StateFlow<Boolean> = habitListPreferences
        .isMedicationEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val schoolEnabled: StateFlow<Boolean> = habitListPreferences
        .isSchoolEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val houseworkEnabled: StateFlow<Boolean> = habitListPreferences
        .isHouseworkEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val leisureEnabled: StateFlow<Boolean> = habitListPreferences
        .isLeisureEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val reduceMotion: StateFlow<Boolean> = a11yPreferences
        .getReduceMotion()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val highContrast: StateFlow<Boolean> = a11yPreferences
        .getHighContrast()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val largeTouchTargets: StateFlow<Boolean> = a11yPreferences
        .getLargeTouchTargets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val voiceInputEnabled: StateFlow<Boolean> = voicePreferences
        .getVoiceInputEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // BrainModePage state flows. Replace per-page `var ... by remember`
    // local state with reactive prefs (F8 idiom drift fix). Also lets the
    // page reflect the persisted state if the user backs up to it.
    // `stateIn` initial value matches the default-on DataStore fallback so
    // the pre-emission frame doesn't flash OFF for fresh installs.
    val adhdMode: StateFlow<Boolean> = ndPreferencesDataStore
        .ndPreferencesFlow
        .map { it.adhdModeEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val calmMode: StateFlow<Boolean> = ndPreferencesDataStore
        .ndPreferencesFlow
        .map { it.calmModeEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val focusReleaseMode: StateFlow<Boolean> = ndPreferencesDataStore
        .ndPreferencesFlow
        .map { it.focusReleaseModeEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val aiFeaturesEnabled: StateFlow<Boolean> = userPreferencesDataStore
        .aiFeaturePrefsFlow
        .map { it.enabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val forgivenessStreaksEnabled: StateFlow<Boolean> = userPreferencesDataStore
        .forgivenessFlow
        .map { it.enabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val streakMaxMissedDays: StateFlow<Int> = habitListPreferences
        .getStreakMaxMissedDays()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            HabitListPreferences.DEFAULT_STREAK_MAX_MISSED_DAYS
        )

    val dailyBriefingEnabled: StateFlow<Boolean> = notificationPreferences
        .dailyBriefingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val eveningSummaryEnabled: StateFlow<Boolean> = notificationPreferences
        .eveningSummaryEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val weeklySummaryEnabled: StateFlow<Boolean> = notificationPreferences
        .weeklySummaryEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val overloadAlertsEnabled: StateFlow<Boolean> = notificationPreferences
        .overloadAlertsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val streakAlertsEnabled: StateFlow<Boolean> = notificationPreferences
        .streakAlertsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val reengagementEnabled: StateFlow<Boolean> = notificationPreferences
        .reengagementEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // Per-type notification toggles surfaced on the onboarding
    // NotificationsPage. Storage already lives in NotificationPreferences;
    // onboarding writes through the existing setters.
    val taskRemindersEnabled: StateFlow<Boolean> = notificationPreferences
        .taskRemindersEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val timerAlertsEnabled: StateFlow<Boolean> = notificationPreferences
        .timerAlertsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val medicationRemindersEnabled: StateFlow<Boolean> = notificationPreferences
        .medicationRemindersEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // Widget theme override surfaced on the onboarding ThemePickerPage.
    // Empty / default value = follow the app theme; a non-default override
    // is set elsewhere (Settings → Appearance). Onboarding only exposes the
    // "follow app theme" toggle.
    val widgetThemeFollowsApp: StateFlow<Boolean> = themePreferences
        .getWidgetThemeOverride()
        .map { it.isBlank() || it == "default" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val startOfDayHour: StateFlow<Int> = taskBehaviorPreferences
        .getDayStartHour()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 4)
    val startOfDayMinute: StateFlow<Int> = taskBehaviorPreferences
        .getDayStartMinute()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        if (authManager.isSignedIn.value) {
            _signInState.value = SignInState.SignedIn(
                authManager.currentUser.value?.email ?: ""
            )
        }
    }

    fun onGoogleSignIn(idToken: String) {
        ifNotPreview {
            viewModelScope.launch {
                _signInState.value = SignInState.Loading
                val result = authManager.signInWithGoogle(idToken)
                result.fold(
                    onSuccess = { user ->
                        _signInState.value = SignInState.SignedIn(user.email ?: "")
                        syncService.startAutoSync()
                        checkExistingUserAndMaybeSkip()
                    },
                    onFailure = {
                        _signInState.value = SignInState.Error("Sign-in failed")
                    }
                )
            }
        }
    }

    fun onEmailSignUp(email: String, password: String) {
        // TODO(email-verification): call user.sendEmailVerification() and
        // gate sync until verified once the verification flow lands.
        ifNotPreview {
            viewModelScope.launch {
                _signInState.value = SignInState.Loading
                val result = authManager.signUpWithEmail(email, password)
                result.fold(
                    onSuccess = { user ->
                        _signInState.value = SignInState.SignedIn(user.email ?: email)
                        syncService.startAutoSync()
                        checkExistingUserAndMaybeSkip()
                    },
                    onFailure = {
                        _signInState.value = SignInState.Error(
                            it.localizedMessage ?: "Sign-up failed"
                        )
                    }
                )
            }
        }
    }

    fun onEmailSignIn(email: String, password: String) {
        ifNotPreview {
            viewModelScope.launch {
                _signInState.value = SignInState.Loading
                val result = authManager.signInWithEmail(email, password)
                result.fold(
                    onSuccess = { user ->
                        _signInState.value = SignInState.SignedIn(user.email ?: email)
                        syncService.startAutoSync()
                        checkExistingUserAndMaybeSkip()
                    },
                    onFailure = {
                        _signInState.value = SignInState.Error(
                            it.localizedMessage ?: "Sign-in failed"
                        )
                    }
                )
            }
        }
    }

    private suspend fun checkExistingUserAndMaybeSkip() {
        val uid = authManager.userId ?: return
        val signedInEmail = (_signInState.value as? SignInState.SignedIn)?.email
            ?: authManager.currentUser.value?.email
            ?: ""
        _signInState.value = SignInState.CheckingExistingUser
        try {
            val snapshot = FirebaseFirestore.getInstance()
                .collection("users").document(uid).collection("tasks")
                .limit(1).get().await()
            if (!snapshot.isEmpty) {
                logger.info(operation = "onboarding.check", detail = "existing=true skipping")
                // Write ordering invariant (v1.4.0 SoD skip-race fix):
                // `onboardingPreferences.setOnboardingCompleted()` is the write whose
                // DataStore emission flips `hasCompletedOnboarding` to true in
                // MainActivity and re-keys the SoD gate LaunchedEffect. Any
                // preference that gate reads MUST already be persisted by the
                // time that emission fires — which means any such flag write
                // MUST come before `setOnboardingCompleted()` in this block.
                // Keep `setOnboardingCompleted()` last among the preference
                // writes; `_signInState` stays at the very end.
                taskBehaviorPreferences.setHasSetStartOfDay(true)
                val completedAt = System.currentTimeMillis()
                onboardingPreferences.setOnboardingCompleted(completedAt)
                // Cross-platform canonical write — see CanonicalOnboardingSync
                // KDoc. Best-effort; failure is logged inside the helper and
                // does not block completion (local DataStore is the device-
                // local source of truth, GenericPreferenceSyncService still
                // syncs `onboarding_prefs` between Android devices).
                canonicalOnboardingSync.writeCompletedAt(uid, completedAt)
                _signInState.value = SignInState.ExistingUserDetected
            } else {
                logger.info(operation = "onboarding.check", detail = "existing=false routing=onboarding")
                _signInState.value = SignInState.SignedIn(signedInEmail)
            }
        } catch (e: Exception) {
            logger.error(
                operation = "onboarding.check",
                detail = "failed fallback=onboarding error=${e.message}",
                throwable = e
            )
            // Surface the failure so the UI can show a non-blocking message,
            // but keep the signed-in email so the flow can continue normally.
            _signInState.value = SignInState.ExistingUserCheckFailed(signedInEmail)
        }
    }

    fun setThemeMode(mode: String) {
        ifNotPreview { viewModelScope.launch { themePreferences.setThemeMode(mode) } }
    }

    fun setAccentColor(hex: String) {
        ifNotPreview { viewModelScope.launch { themePreferences.setAccentColor(hex) } }
    }

    fun createQuickTask(title: String) {
        if (title.isBlank()) return
        ifNotPreview {
            viewModelScope.launch {
                taskRepository.addTask(title = title)
            }
        }
    }

    fun setAdhdMode(enabled: Boolean) {
        ifNotPreview { viewModelScope.launch { ndPreferencesDataStore.setAdhdMode(enabled) } }
    }

    fun setCalmMode(enabled: Boolean) {
        ifNotPreview { viewModelScope.launch { ndPreferencesDataStore.setCalmMode(enabled) } }
    }

    fun setFocusReleaseMode(enabled: Boolean) {
        ifNotPreview { viewModelScope.launch { ndPreferencesDataStore.setFocusReleaseMode(enabled) } }
    }

    /**
     * Updates the in-memory template selection used by the UI to render which
     * templates the user picked. Intentionally NOT gated by preview mode —
     * the preview tutorial still shows selection state as the user clicks
     * through. Persistence happens in [applyTemplateSelections] (called from
     * [completeOnboarding]) which IS gated.
     */
    fun updateTemplateSelections(selections: TemplateSelections) {
        _templateSelections.value = selections
    }

    fun setSelfCareEnabled(enabled: Boolean) {
        ifNotPreview { viewModelScope.launch { habitListPreferences.setSelfCareEnabled(enabled) } }
    }

    fun setMedicationEnabled(enabled: Boolean) {
        ifNotPreview { viewModelScope.launch { habitListPreferences.setMedicationEnabled(enabled) } }
    }

    fun setSchoolEnabled(enabled: Boolean) {
        ifNotPreview { viewModelScope.launch { habitListPreferences.setSchoolEnabled(enabled) } }
    }

    fun setHouseworkEnabled(enabled: Boolean) {
        ifNotPreview { viewModelScope.launch { habitListPreferences.setHouseworkEnabled(enabled) } }
    }

    fun setLeisureEnabled(enabled: Boolean) {
        ifNotPreview { viewModelScope.launch { habitListPreferences.setLeisureEnabled(enabled) } }
    }

    fun setReduceMotion(enabled: Boolean) {
        ifNotPreview { viewModelScope.launch { a11yPreferences.setReduceMotion(enabled) } }
    }

    fun setHighContrast(enabled: Boolean) {
        ifNotPreview { viewModelScope.launch { a11yPreferences.setHighContrast(enabled) } }
    }

    fun setLargeTouchTargets(enabled: Boolean) {
        ifNotPreview { viewModelScope.launch { a11yPreferences.setLargeTouchTargets(enabled) } }
    }

    fun setVoiceInputEnabled(enabled: Boolean) {
        ifNotPreview { viewModelScope.launch { voicePreferences.setVoiceInputEnabled(enabled) } }
    }

    fun setAiFeaturesEnabled(enabled: Boolean) {
        ifNotPreview { viewModelScope.launch { userPreferencesDataStore.setAiFeaturesEnabled(enabled) } }
    }

    fun setForgivenessStreaksEnabled(enabled: Boolean) {
        ifNotPreview {
            viewModelScope.launch {
                userPreferencesDataStore.setForgivenessPrefs(ForgivenessPrefs(enabled = enabled))
            }
        }
    }

    fun setStreakMaxMissedDays(days: Int) {
        ifNotPreview { viewModelScope.launch { habitListPreferences.setStreakMaxMissedDays(days) } }
    }

    fun setDailyBriefingEnabled(enabled: Boolean) {
        ifNotPreview { viewModelScope.launch { notificationPreferences.setDailyBriefingEnabled(enabled) } }
    }

    fun setEveningSummaryEnabled(enabled: Boolean) {
        ifNotPreview { viewModelScope.launch { notificationPreferences.setEveningSummaryEnabled(enabled) } }
    }

    /**
     * Toggles the *combined* "weekly summary" stream — sets both the habit-
     * weekly-summary and task-weekly-summary flags together so a single
     * onboarding switch matches the user's mental model. Settings still
     * exposes the two flags separately for fine-grained control.
     */
    fun setWeeklySummaryEnabled(enabled: Boolean) {
        ifNotPreview {
            viewModelScope.launch {
                notificationPreferences.setWeeklySummaryEnabled(enabled)
                notificationPreferences.setWeeklyTaskSummaryEnabled(enabled)
            }
        }
    }

    fun setOverloadAlertsEnabled(enabled: Boolean) {
        ifNotPreview { viewModelScope.launch { notificationPreferences.setOverloadAlertsEnabled(enabled) } }
    }

    fun setStreakAlertsEnabled(enabled: Boolean) {
        ifNotPreview { viewModelScope.launch { notificationPreferences.setStreakAlertsEnabled(enabled) } }
    }

    fun setReengagementEnabled(enabled: Boolean) {
        ifNotPreview { viewModelScope.launch { notificationPreferences.setReengagementEnabled(enabled) } }
    }

    fun setTaskRemindersEnabled(enabled: Boolean) {
        ifNotPreview { viewModelScope.launch { notificationPreferences.setTaskRemindersEnabled(enabled) } }
    }

    fun setTimerAlertsEnabled(enabled: Boolean) {
        ifNotPreview { viewModelScope.launch { notificationPreferences.setTimerAlertsEnabled(enabled) } }
    }

    fun setMedicationRemindersEnabled(enabled: Boolean) {
        ifNotPreview { viewModelScope.launch { notificationPreferences.setMedicationRemindersEnabled(enabled) } }
    }

    /**
     * "Use app theme for widgets" — when true, widgets render with the active
     * PrismTheme palette. When false, the user can pick a separate widget
     * theme in Settings → Appearance. Onboarding only exposes the toggle.
     */
    fun setWidgetThemeFollowsApp(follow: Boolean) {
        ifNotPreview {
            viewModelScope.launch {
                themePreferences.setWidgetThemeOverride(if (follow) null else "custom")
            }
        }
    }

    /**
     * Atomically sets the start-of-day hour + minute and flips
     * `hasSetStartOfDay = true`. Wired to the Day Setup onboarding page so a
     * user who passes this page never sees the legacy MainActivity StartOfDay
     * modal afterwards. The modal stays in place as deny-recovery for legacy
     * installs that completed onboarding before this page existed.
     */
    fun setStartOfDay(hour: Int, minute: Int) {
        ifNotPreview { viewModelScope.launch { taskBehaviorPreferences.setStartOfDay(hour, minute) } }
    }

    fun completeOnboarding() {
        // Preview replay (admin "Show Tutorial") must not re-write the
        // completion flag, re-mark tour eligibility, re-sync canonical
        // Firestore, or re-seed templates. The screen still calls onComplete()
        // independently, so navigation back to MainTabs is unaffected.
        if (_isPreviewMode.value) return
        viewModelScope.launch {
            // Write the flag first so it is durable before viewModelScope is
            // cancelled by the navigation that fires immediately after this call.
            val completedAt = System.currentTimeMillis()
            try {
                onboardingPreferences.setOnboardingCompleted(completedAt)
                logger.info(operation = "onboarding.flag_written", status = "success")
            } catch (e: Exception) {
                logger.error(operation = "onboarding.flag_written", throwable = e)
            }
            // Mirror to the cross-platform canonical Firestore field so
            // the same account on web (or a fresh Android install) sees
            // onboarding as done. Best-effort, isolated from the local
            // write so a Firestore outage doesn't strand the user back
            // on the onboarding gate after they finished.
            authManager.userId?.let { uid ->
                try {
                    canonicalOnboardingSync.writeCompletedAt(uid, completedAt)
                    logger.info(operation = "onboarding.canonical_written", status = "success")
                } catch (e: Exception) {
                    logger.error(operation = "onboarding.canonical_written", throwable = e)
                }
            }
            // Mark the user eligible for the post-onboarding Guided Tour
            // card on Today. Deliberately set ONLY here (not in
            // [checkExistingUserAndMaybeSkip]) so returning users with
            // prior cloud data never see the tour. See
            // [TourCardPreferences] KDoc.
            try {
                tourCardPreferences.markEligible()
                logger.info(operation = "onboarding.tour_card_eligible", status = "success")
            } catch (e: Exception) {
                logger.error(operation = "onboarding.tour_card_eligible", throwable = e)
            }
            // Template selections are non-critical — if viewModelScope is
            // cancelled here the user sees default prefs, not an onboarding loop.
            try {
                applyTemplateSelections(_templateSelections.value)
                logger.info(operation = "onboarding.templates_applied", status = "success")
            } catch (e: Exception) {
                logger.error(operation = "onboarding.templates_applied", throwable = e)
            }
        }
    }

    /**
     * Persists the user's template picks. For leisure, any default not in the
     * selection is added to `hiddenBuiltInIds` so it won't appear in the
     * Leisure screen. For self-care / bedtime / housework, the effective step
     * ids are written to the DB via [SelfCareRepository.seedSelfCareSteps]
     * (idempotent, safe to run on an already-populated DB).
     */
    internal suspend fun applyTemplateSelections(selections: TemplateSelections) {
        // Leisure Budget v2.0: per-slot music/flex/language template
        // application retired — onboarding no longer seeds the
        // (deprecated) slot pool. Users add activities via
        // LeisurePoolScreen instead. (Item 11 / onboarding addition is
        // YELLOW-DEFER in the v2.0 bundle.)
        applyRoutineSelection("morning", selections)
        applyRoutineSelection("bedtime", selections)
        applyRoutineSelection("housework", selections)
    }

    private suspend fun applyRoutineSelection(routineType: String, selections: TemplateSelections) {
        val stepIds = selections.effectiveStepIds(routineType)
        if (stepIds.isEmpty()) return
        selfCareRepository.seedSelfCareSteps(routineType, stepIds.toList())
    }
}

sealed class SignInState {
    data object NotSignedIn : SignInState()

    data object Loading : SignInState()

    data class SignedIn(
        val email: String
    ) : SignInState()

    data class Error(
        val message: String
    ) : SignInState()

    data object ExistingUserDetected : SignInState()

    /**
     * Transient state while the post-sign-in Firestore lookup that decides
     * whether to skip onboarding for returning users is in flight. UI observes
     * this to render a spinner; the ViewModel clears it on success or failure.
     */
    data object CheckingExistingUser : SignInState()

    /**
     * Firestore existing-user check failed (network, permissions, etc.). The
     * flow is NOT blocked — the user continues through onboarding, but the UI
     * surfaces a non-blocking message so the failure isn't silent. [email] is
     * preserved so downstream UI can still show the signed-in account.
     */
    data class ExistingUserCheckFailed(
        val email: String
    ) : SignInState()
}
