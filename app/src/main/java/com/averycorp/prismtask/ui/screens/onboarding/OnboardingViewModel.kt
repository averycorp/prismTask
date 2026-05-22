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
import com.averycorp.prismtask.data.remote.sync.BackendSyncService
import com.averycorp.prismtask.data.remote.sync.PrismSyncLogger
import com.averycorp.prismtask.data.repository.SelfCareRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.ui.screens.templates.TemplateSelections
import com.google.firebase.firestore.FirebaseFirestore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
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
    private val backendSyncService: BackendSyncService,
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
     * Preview mode — admin replay of the tutorial. When true, *persistent*
     * mutations (DataStore / Room / Firestore writes, task creation, sign-in,
     * template seeding, and `completeOnboarding()`) short-circuit so the
     * visual flow runs end to end without changing the account. The pref
     * StateFlows are still mutated locally via [mirror] so toggles, sliders,
     * pickers, and clock controls visually respond to the admin's taps —
     * see "preview-friendly UI" caveat on each setter. The screen sets the
     * mode once via [setPreviewMode] from the route's `preview` arg.
     */
    private val _isPreviewMode = MutableStateFlow(false)
    val isPreviewMode: StateFlow<Boolean> = _isPreviewMode.asStateFlow()

    fun setPreviewMode(enabled: Boolean) {
        _isPreviewMode.value = enabled
    }

    private inline fun ifNotPreview(block: () -> Unit) {
        if (!_isPreviewMode.value) block()
    }

    /**
     * Creates a [MutableStateFlow] seeded with [initial] and kept in sync
     * with [upstream] via an eager `viewModelScope` collector. Setters that
     * own a mirror write the optimistic value into it BEFORE the gated
     * DataStore call — in production the upstream emit loops back and the
     * second `state.value = it` is a no-op; in preview mode the DataStore
     * write never runs, so the mirror is the sole source of UI truth and
     * the Switch / Card / Slider thumbs respond to admin taps. The eager
     * collector replaces the prior `stateIn(WhileSubscribed(5000))` pattern,
     * which only collects when the StateFlow has subscribers — fine for
     * read-only state, but fatal for optimistic writes because the mirror
     * would be discarded between page swipes.
     */
    private fun <T> mirror(initial: T, upstream: Flow<T>): MutableStateFlow<T> {
        val state = MutableStateFlow(initial)
        viewModelScope.launch { upstream.collect { state.value = it } }
        return state
    }

    private val _signInState = MutableStateFlow<SignInState>(SignInState.NotSignedIn)
    val signInState: StateFlow<SignInState> = _signInState.asStateFlow()

    private val _themeMode = mirror("system", themePreferences.getThemeMode())
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _accentColor = mirror("#2563EB", themePreferences.getAccentColor())
    val accentColor: StateFlow<String> = _accentColor.asStateFlow()

    private val _templateSelections = MutableStateFlow(TemplateSelections())
    val templateSelections: StateFlow<TemplateSelections> = _templateSelections.asStateFlow()

    /**
     * Mental-Health-First § G6: user's onboarding "tuning" picks. Multi-select.
     * The set is the in-memory selection for the page; persistence happens in
     * [applyTuningSelections], which is invoked from [completeOnboarding] (or
     * explicitly by the page on apply). Selections are NOT pre-loaded from
     * persisted state — the page is one-shot and skippable, and the user can
     * always change the underlying prefs in Settings afterwards.
     */
    private val _tuningSelections = MutableStateFlow<Set<OnboardingPreferenceMapper.TuningOption>>(emptySet())
    val tuningSelections: StateFlow<Set<OnboardingPreferenceMapper.TuningOption>> = _tuningSelections.asStateFlow()

    private val _selfCareEnabled = mirror(true, habitListPreferences.isSelfCareEnabled())
    val selfCareEnabled: StateFlow<Boolean> = _selfCareEnabled.asStateFlow()
    private val _medicationEnabled = mirror(true, habitListPreferences.isMedicationEnabled())
    val medicationEnabled: StateFlow<Boolean> = _medicationEnabled.asStateFlow()
    private val _schoolEnabled = mirror(true, habitListPreferences.isSchoolEnabled())
    val schoolEnabled: StateFlow<Boolean> = _schoolEnabled.asStateFlow()
    private val _houseworkEnabled = mirror(true, habitListPreferences.isHouseworkEnabled())
    val houseworkEnabled: StateFlow<Boolean> = _houseworkEnabled.asStateFlow()
    private val _leisureEnabled = mirror(true, habitListPreferences.isLeisureEnabled())
    val leisureEnabled: StateFlow<Boolean> = _leisureEnabled.asStateFlow()

    private val _reduceMotion = mirror(false, a11yPreferences.getReduceMotion())
    val reduceMotion: StateFlow<Boolean> = _reduceMotion.asStateFlow()
    private val _highContrast = mirror(false, a11yPreferences.getHighContrast())
    val highContrast: StateFlow<Boolean> = _highContrast.asStateFlow()
    private val _largeTouchTargets = mirror(false, a11yPreferences.getLargeTouchTargets())
    val largeTouchTargets: StateFlow<Boolean> = _largeTouchTargets.asStateFlow()

    private val _voiceInputEnabled = mirror(true, voicePreferences.getVoiceInputEnabled())
    val voiceInputEnabled: StateFlow<Boolean> = _voiceInputEnabled.asStateFlow()

    // BrainModePage state flows. Replace per-page `var ... by remember`
    // local state with reactive prefs (F8 idiom drift fix). Also lets the
    // page reflect the persisted state if the user backs up to it.
    // Initial mirror value matches the default-on DataStore fallback so
    // the pre-emission frame doesn't flash OFF for fresh installs.
    private val _adhdMode = mirror(
        true,
        ndPreferencesDataStore.ndPreferencesFlow.map { it.adhdModeEnabled }
    )
    val adhdMode: StateFlow<Boolean> = _adhdMode.asStateFlow()
    private val _calmMode = mirror(
        true,
        ndPreferencesDataStore.ndPreferencesFlow.map { it.calmModeEnabled }
    )
    val calmMode: StateFlow<Boolean> = _calmMode.asStateFlow()
    private val _focusReleaseMode = mirror(
        true,
        ndPreferencesDataStore.ndPreferencesFlow.map { it.focusReleaseModeEnabled }
    )
    val focusReleaseMode: StateFlow<Boolean> = _focusReleaseMode.asStateFlow()
    private val _aiFeaturesEnabled = mirror(
        true,
        userPreferencesDataStore.aiFeaturePrefsFlow.map { it.enabled }
    )
    val aiFeaturesEnabled: StateFlow<Boolean> = _aiFeaturesEnabled.asStateFlow()
    private val _forgivenessStreaksEnabled = mirror(
        true,
        userPreferencesDataStore.forgivenessFlow.map { it.enabled }
    )
    val forgivenessStreaksEnabled: StateFlow<Boolean> = _forgivenessStreaksEnabled.asStateFlow()
    private val _streakMaxMissedDays = mirror(
        HabitListPreferences.DEFAULT_STREAK_MAX_MISSED_DAYS,
        habitListPreferences.getStreakMaxMissedDays()
    )
    val streakMaxMissedDays: StateFlow<Int> = _streakMaxMissedDays.asStateFlow()

    private val _dailyBriefingEnabled = mirror(true, notificationPreferences.dailyBriefingEnabled)
    val dailyBriefingEnabled: StateFlow<Boolean> = _dailyBriefingEnabled.asStateFlow()
    private val _eveningSummaryEnabled = mirror(true, notificationPreferences.eveningSummaryEnabled)
    val eveningSummaryEnabled: StateFlow<Boolean> = _eveningSummaryEnabled.asStateFlow()
    private val _weeklySummaryEnabled = mirror(true, notificationPreferences.weeklySummaryEnabled)
    val weeklySummaryEnabled: StateFlow<Boolean> = _weeklySummaryEnabled.asStateFlow()
    private val _overloadAlertsEnabled = mirror(true, notificationPreferences.overloadAlertsEnabled)
    val overloadAlertsEnabled: StateFlow<Boolean> = _overloadAlertsEnabled.asStateFlow()
    private val _streakAlertsEnabled = mirror(true, notificationPreferences.streakAlertsEnabled)
    val streakAlertsEnabled: StateFlow<Boolean> = _streakAlertsEnabled.asStateFlow()
    private val _reengagementEnabled = mirror(true, notificationPreferences.reengagementEnabled)
    val reengagementEnabled: StateFlow<Boolean> = _reengagementEnabled.asStateFlow()

    // Per-type notification toggles surfaced on the onboarding
    // NotificationsPage. Storage already lives in NotificationPreferences;
    // onboarding writes through the existing setters.
    private val _taskRemindersEnabled = mirror(true, notificationPreferences.taskRemindersEnabled)
    val taskRemindersEnabled: StateFlow<Boolean> = _taskRemindersEnabled.asStateFlow()
    private val _timerAlertsEnabled = mirror(true, notificationPreferences.timerAlertsEnabled)
    val timerAlertsEnabled: StateFlow<Boolean> = _timerAlertsEnabled.asStateFlow()
    private val _medicationRemindersEnabled = mirror(
        true,
        notificationPreferences.medicationRemindersEnabled
    )
    val medicationRemindersEnabled: StateFlow<Boolean> = _medicationRemindersEnabled.asStateFlow()

    // Widget theme override surfaced on the onboarding ThemePickerPage.
    // Empty / default value = follow the app theme; a non-default override
    // is set elsewhere (Settings → Appearance). Onboarding only exposes the
    // "follow app theme" toggle.
    private val _widgetThemeFollowsApp = mirror(
        true,
        themePreferences.getWidgetThemeOverride().map { it.isBlank() || it == "default" }
    )
    val widgetThemeFollowsApp: StateFlow<Boolean> = _widgetThemeFollowsApp.asStateFlow()

    private val _startOfDayHour = mirror(4, taskBehaviorPreferences.getDayStartHour())
    val startOfDayHour: StateFlow<Int> = _startOfDayHour.asStateFlow()
    private val _startOfDayMinute = mirror(0, taskBehaviorPreferences.getDayStartMinute())
    val startOfDayMinute: StateFlow<Int> = _startOfDayMinute.asStateFlow()

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
                        backendSyncService.startAutoSync()
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
        ifNotPreview {
            viewModelScope.launch {
                _signInState.value = SignInState.Loading
                val result = authManager.signUpWithEmail(email, password)
                result.fold(
                    onSuccess = { user ->
                        try {
                            user.sendEmailVerification().await()
                        } catch (e: Exception) {
                            // Ignore failures to send verification email so signup completes
                        }
                        _signInState.value = SignInState.SignedIn(user.email ?: email)
                        // TODO(email-verification): gate sync until verified once the verification flow lands.
                        syncService.startAutoSync()
                        backendSyncService.startAutoSync()
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
                        backendSyncService.startAutoSync()
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
        _themeMode.value = mode
        ifNotPreview { viewModelScope.launch { themePreferences.setThemeMode(mode) } }
    }

    fun setAccentColor(hex: String) {
        _accentColor.value = hex
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
        _adhdMode.value = enabled
        ifNotPreview { viewModelScope.launch { ndPreferencesDataStore.setAdhdMode(enabled) } }
    }

    fun setCalmMode(enabled: Boolean) {
        _calmMode.value = enabled
        ifNotPreview { viewModelScope.launch { ndPreferencesDataStore.setCalmMode(enabled) } }
    }

    fun setFocusReleaseMode(enabled: Boolean) {
        _focusReleaseMode.value = enabled
        ifNotPreview { viewModelScope.launch { ndPreferencesDataStore.setFocusReleaseMode(enabled) } }
    }

    /**
     * Mental-Health-First § G6: toggle one [OnboardingPreferenceMapper.TuningOption].
     *
     * Selection rules (mirrored in the mapper):
     * - [OnboardingPreferenceMapper.TuningOption.NONE_OF_THESE] is single-select. Toggling it on
     *   clears the other selections; toggling it off leaves an empty set.
     * - Picking any other option while NONE_OF_THESE is selected clears
     *   NONE_OF_THESE first.
     *
     * The page is intentionally tolerant of taps even while previewing — this
     * setter updates in-memory state only. Persistence happens via
     * [applyTuningSelections], which IS gated by preview mode.
     */
    fun toggleTuningOption(option: OnboardingPreferenceMapper.TuningOption) {
        val current = _tuningSelections.value
        _tuningSelections.value = when {
            option == OnboardingPreferenceMapper.TuningOption.NONE_OF_THESE && option in current ->
                emptySet()
            option == OnboardingPreferenceMapper.TuningOption.NONE_OF_THESE ->
                setOf(OnboardingPreferenceMapper.TuningOption.NONE_OF_THESE)
            option in current ->
                current - option
            else ->
                (current - OnboardingPreferenceMapper.TuningOption.NONE_OF_THESE) + option
        }
    }

    /**
     * Mental-Health-First § G6: resolve the user's tuning picks via
     * [OnboardingPreferenceMapper] and persist the resulting defaults.
     *
     * Skip path: pass [skip] = true (or just don't call this method). The
     * page's "Skip" button calls this with `skip = true` so we leave today's
     * hardcoded defaults untouched. Per the audit hard rule: the user must
     * NOT be forced to disclose anything.
     *
     * Scope (post-2026-05 onboarding overlap audit, findings #2 + #6 of
     * `docs/audits/ONBOARDING_OVERLAP_AUDIT.md`): TuningPage writes ONLY the
     * behavior sub-flags and small tuning knobs it uniquely owns —
     * `reduceAnimations`, `mutedColorPalette`, `goodEnoughTimers`,
     * `compactMode`, `primeRestDay`, and `checkInIntervalMinutes`. It does
     * NOT touch the parent ND-mode flags (`adhdMode`, `calmMode`,
     * `focusReleaseMode`) or `ForgivenessPrefs.enabled`. Those are owned by
     * the BrainModePage (page 9) and the HabitsPage Forgiveness Switch
     * (page 6) respectively. Cascading them from here silently overrode
     * users who had explicitly opted OUT on the earlier pages.
     *
     * No-op when the resolved [OnboardingPreferenceMapper.Result] is empty
     * (skip / "None of these" / empty pick), and a no-op in preview mode.
     */
    fun applyTuningSelections(skip: Boolean = false) {
        if (skip) return
        ifNotPreview {
            val result = OnboardingPreferenceMapper.resolve(_tuningSelections.value)
            if (result.isNoOp) return@ifNotPreview
            viewModelScope.launch {
                // Explicit sub-flag writes only. The parent ND-mode flags
                // (adhdMode / calmMode / focusReleaseMode) and
                // ForgivenessPrefs.enabled are intentionally NOT touched
                // here — see the KDoc above for the rationale.
                if (result.reduceAnimations) ndPreferencesDataStore.setReduceAnimations(true)
                if (result.mutedColorPalette) ndPreferencesDataStore.setMutedColorPalette(true)
                if (result.goodEnoughTimers) ndPreferencesDataStore.setGoodEnoughTimersEnabled(true)
                result.checkInIntervalMinutes?.let {
                    ndPreferencesDataStore.setCheckInIntervalMinutes(it)
                }
                if (result.compactMode) userPreferencesDataStore.setCompactMode(true)
                if (result.primeRestDay) onboardingPreferences.setRestDayPrimed(true)
            }
        }
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
        _selfCareEnabled.value = enabled
        ifNotPreview { viewModelScope.launch { habitListPreferences.setSelfCareEnabled(enabled) } }
    }

    fun setMedicationEnabled(enabled: Boolean) {
        _medicationEnabled.value = enabled
        ifNotPreview { viewModelScope.launch { habitListPreferences.setMedicationEnabled(enabled) } }
    }

    fun setSchoolEnabled(enabled: Boolean) {
        _schoolEnabled.value = enabled
        ifNotPreview { viewModelScope.launch { habitListPreferences.setSchoolEnabled(enabled) } }
    }

    fun setHouseworkEnabled(enabled: Boolean) {
        _houseworkEnabled.value = enabled
        ifNotPreview { viewModelScope.launch { habitListPreferences.setHouseworkEnabled(enabled) } }
    }

    fun setLeisureEnabled(enabled: Boolean) {
        _leisureEnabled.value = enabled
        ifNotPreview { viewModelScope.launch { habitListPreferences.setLeisureEnabled(enabled) } }
    }

    fun setReduceMotion(enabled: Boolean) {
        _reduceMotion.value = enabled
        ifNotPreview { viewModelScope.launch { a11yPreferences.setReduceMotion(enabled) } }
    }

    fun setHighContrast(enabled: Boolean) {
        _highContrast.value = enabled
        ifNotPreview { viewModelScope.launch { a11yPreferences.setHighContrast(enabled) } }
    }

    fun setLargeTouchTargets(enabled: Boolean) {
        _largeTouchTargets.value = enabled
        ifNotPreview { viewModelScope.launch { a11yPreferences.setLargeTouchTargets(enabled) } }
    }

    fun setVoiceInputEnabled(enabled: Boolean) {
        _voiceInputEnabled.value = enabled
        ifNotPreview { viewModelScope.launch { voicePreferences.setVoiceInputEnabled(enabled) } }
    }

    fun setAiFeaturesEnabled(enabled: Boolean) {
        _aiFeaturesEnabled.value = enabled
        ifNotPreview { viewModelScope.launch { userPreferencesDataStore.setAiFeaturesEnabled(enabled) } }
    }

    fun setForgivenessStreaksEnabled(enabled: Boolean) {
        _forgivenessStreaksEnabled.value = enabled
        ifNotPreview {
            viewModelScope.launch {
                userPreferencesDataStore.setForgivenessPrefs(ForgivenessPrefs(enabled = enabled))
            }
        }
    }

    fun setStreakMaxMissedDays(days: Int) {
        _streakMaxMissedDays.value = days
        ifNotPreview { viewModelScope.launch { habitListPreferences.setStreakMaxMissedDays(days) } }
    }

    fun setDailyBriefingEnabled(enabled: Boolean) {
        _dailyBriefingEnabled.value = enabled
        ifNotPreview { viewModelScope.launch { notificationPreferences.setDailyBriefingEnabled(enabled) } }
    }

    fun setEveningSummaryEnabled(enabled: Boolean) {
        _eveningSummaryEnabled.value = enabled
        ifNotPreview { viewModelScope.launch { notificationPreferences.setEveningSummaryEnabled(enabled) } }
    }

    /**
     * Toggles the *combined* "weekly summary" stream — sets both the habit-
     * weekly-summary and task-weekly-summary flags together so a single
     * onboarding switch matches the user's mental model. Settings still
     * exposes the two flags separately for fine-grained control.
     */
    fun setWeeklySummaryEnabled(enabled: Boolean) {
        _weeklySummaryEnabled.value = enabled
        ifNotPreview {
            viewModelScope.launch {
                notificationPreferences.setWeeklySummaryEnabled(enabled)
                notificationPreferences.setWeeklyTaskSummaryEnabled(enabled)
            }
        }
    }

    fun setOverloadAlertsEnabled(enabled: Boolean) {
        _overloadAlertsEnabled.value = enabled
        ifNotPreview { viewModelScope.launch { notificationPreferences.setOverloadAlertsEnabled(enabled) } }
    }

    fun setStreakAlertsEnabled(enabled: Boolean) {
        _streakAlertsEnabled.value = enabled
        ifNotPreview { viewModelScope.launch { notificationPreferences.setStreakAlertsEnabled(enabled) } }
    }

    fun setReengagementEnabled(enabled: Boolean) {
        _reengagementEnabled.value = enabled
        ifNotPreview { viewModelScope.launch { notificationPreferences.setReengagementEnabled(enabled) } }
    }

    fun setTaskRemindersEnabled(enabled: Boolean) {
        _taskRemindersEnabled.value = enabled
        ifNotPreview { viewModelScope.launch { notificationPreferences.setTaskRemindersEnabled(enabled) } }
    }

    fun setTimerAlertsEnabled(enabled: Boolean) {
        _timerAlertsEnabled.value = enabled
        ifNotPreview { viewModelScope.launch { notificationPreferences.setTimerAlertsEnabled(enabled) } }
    }

    fun setMedicationRemindersEnabled(enabled: Boolean) {
        _medicationRemindersEnabled.value = enabled
        ifNotPreview { viewModelScope.launch { notificationPreferences.setMedicationRemindersEnabled(enabled) } }
    }

    /**
     * "Use app theme for widgets" — when true, widgets render with the active
     * PrismTheme palette. When false, the user can pick a separate widget
     * theme in Settings → Appearance. Onboarding only exposes the toggle.
     */
    fun setWidgetThemeFollowsApp(follow: Boolean) {
        _widgetThemeFollowsApp.value = follow
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
        _startOfDayHour.value = hour
        _startOfDayMinute.value = minute
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
            // Mental-Health-First § G6: persist any tuning-step picks that
            // the user did not already commit via the page's "Apply" button.
            // Safe to call regardless — `applyTuningSelections` is a no-op
            // for an empty selection (skip path) and preview mode.
            try {
                applyTuningSelections(skip = false)
                logger.info(operation = "onboarding.tuning_applied", status = "success")
            } catch (e: Exception) {
                logger.error(operation = "onboarding.tuning_applied", throwable = e)
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
        applyRoutineSelection("workday", selections)
        applyRoutineSelection("winddown", selections)
        applyRoutineSelection("errands", selections)
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
