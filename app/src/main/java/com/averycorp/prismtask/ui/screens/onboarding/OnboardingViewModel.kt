package com.averycorp.prismtask.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.preferences.A11yPreferences
import com.averycorp.prismtask.data.preferences.ForgivenessPrefs
import com.averycorp.prismtask.data.preferences.HabitListPreferences
import com.averycorp.prismtask.data.preferences.LeisurePreferences
import com.averycorp.prismtask.data.preferences.LeisureSlotId
import com.averycorp.prismtask.data.preferences.NdPreferencesDataStore
import com.averycorp.prismtask.data.preferences.NotificationPreferences
import com.averycorp.prismtask.data.preferences.OnboardingPreferences
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.preferences.ThemePreferences
import com.averycorp.prismtask.data.preferences.UserPreferencesDataStore
import com.averycorp.prismtask.data.preferences.VoicePreferences
import com.averycorp.prismtask.data.remote.AuthManager
import com.averycorp.prismtask.data.remote.CanonicalOnboardingSync
import com.averycorp.prismtask.data.remote.SyncService
import com.averycorp.prismtask.data.remote.sync.PrismSyncLogger
import com.averycorp.prismtask.data.repository.SelfCareRepository
import com.averycorp.prismtask.data.repository.TaskRepository
import com.averycorp.prismtask.ui.screens.leisure.LeisureViewModel
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
    private val leisurePreferences: LeisurePreferences,
    private val userPreferencesDataStore: UserPreferencesDataStore,
    private val taskBehaviorPreferences: TaskBehaviorPreferences,
    private val canonicalOnboardingSync: CanonicalOnboardingSync,
    private val habitListPreferences: HabitListPreferences,
    private val a11yPreferences: A11yPreferences,
    private val notificationPreferences: NotificationPreferences,
    private val voicePreferences: VoicePreferences,
    private val logger: PrismSyncLogger
) : ViewModel() {
    val hasCompletedOnboarding: StateFlow<Boolean> = onboardingPreferences
        .hasCompletedOnboarding()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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

    fun onEmailSignUp(email: String, password: String) {
        // TODO(email-verification): call user.sendEmailVerification() and
        // gate sync until verified once the verification flow lands.
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

    fun onEmailSignIn(email: String, password: String) {
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
        viewModelScope.launch { themePreferences.setThemeMode(mode) }
    }

    fun setAccentColor(hex: String) {
        viewModelScope.launch { themePreferences.setAccentColor(hex) }
    }

    fun createQuickTask(title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            taskRepository.addTask(title = title)
        }
    }

    fun setAdhdMode(enabled: Boolean) {
        viewModelScope.launch { ndPreferencesDataStore.setAdhdMode(enabled) }
    }

    fun setCalmMode(enabled: Boolean) {
        viewModelScope.launch { ndPreferencesDataStore.setCalmMode(enabled) }
    }

    fun setFocusReleaseMode(enabled: Boolean) {
        viewModelScope.launch { ndPreferencesDataStore.setFocusReleaseMode(enabled) }
    }

    fun updateTemplateSelections(selections: TemplateSelections) {
        _templateSelections.value = selections
    }

    fun setSelfCareEnabled(enabled: Boolean) {
        viewModelScope.launch { habitListPreferences.setSelfCareEnabled(enabled) }
    }

    fun setMedicationEnabled(enabled: Boolean) {
        viewModelScope.launch { habitListPreferences.setMedicationEnabled(enabled) }
    }

    fun setSchoolEnabled(enabled: Boolean) {
        viewModelScope.launch { habitListPreferences.setSchoolEnabled(enabled) }
    }

    fun setHouseworkEnabled(enabled: Boolean) {
        viewModelScope.launch { habitListPreferences.setHouseworkEnabled(enabled) }
    }

    fun setLeisureEnabled(enabled: Boolean) {
        viewModelScope.launch { habitListPreferences.setLeisureEnabled(enabled) }
    }

    fun setReduceMotion(enabled: Boolean) {
        viewModelScope.launch { a11yPreferences.setReduceMotion(enabled) }
    }

    fun setHighContrast(enabled: Boolean) {
        viewModelScope.launch { a11yPreferences.setHighContrast(enabled) }
    }

    fun setLargeTouchTargets(enabled: Boolean) {
        viewModelScope.launch { a11yPreferences.setLargeTouchTargets(enabled) }
    }

    fun setVoiceInputEnabled(enabled: Boolean) {
        viewModelScope.launch { voicePreferences.setVoiceInputEnabled(enabled) }
    }

    fun setAiFeaturesEnabled(enabled: Boolean) {
        viewModelScope.launch { userPreferencesDataStore.setAiFeaturesEnabled(enabled) }
    }

    fun setForgivenessStreaksEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesDataStore.setForgivenessPrefs(ForgivenessPrefs(enabled = enabled))
        }
    }

    fun setStreakMaxMissedDays(days: Int) {
        viewModelScope.launch { habitListPreferences.setStreakMaxMissedDays(days) }
    }

    fun setDailyBriefingEnabled(enabled: Boolean) {
        viewModelScope.launch { notificationPreferences.setDailyBriefingEnabled(enabled) }
    }

    fun setEveningSummaryEnabled(enabled: Boolean) {
        viewModelScope.launch { notificationPreferences.setEveningSummaryEnabled(enabled) }
    }

    /**
     * Toggles the *combined* "weekly summary" stream — sets both the habit-
     * weekly-summary and task-weekly-summary flags together so a single
     * onboarding switch matches the user's mental model. Settings still
     * exposes the two flags separately for fine-grained control.
     */
    fun setWeeklySummaryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            notificationPreferences.setWeeklySummaryEnabled(enabled)
            notificationPreferences.setWeeklyTaskSummaryEnabled(enabled)
        }
    }

    fun setOverloadAlertsEnabled(enabled: Boolean) {
        viewModelScope.launch { notificationPreferences.setOverloadAlertsEnabled(enabled) }
    }

    fun setStreakAlertsEnabled(enabled: Boolean) {
        viewModelScope.launch { notificationPreferences.setStreakAlertsEnabled(enabled) }
    }

    fun setReengagementEnabled(enabled: Boolean) {
        viewModelScope.launch { notificationPreferences.setReengagementEnabled(enabled) }
    }

    /**
     * Atomically sets the start-of-day hour + minute and flips
     * `hasSetStartOfDay = true`. Wired to the Day Setup onboarding page so a
     * user who passes this page never sees the legacy MainActivity StartOfDay
     * modal afterwards. The modal stays in place as deny-recovery for legacy
     * installs that completed onboarding before this page existed.
     */
    fun setStartOfDay(hour: Int, minute: Int) {
        viewModelScope.launch { taskBehaviorPreferences.setStartOfDay(hour, minute) }
    }

    fun completeOnboarding() {
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
        applyLeisureSelection(
            LeisureSlotId.MUSIC,
            LeisureViewModel.DEFAULT_INSTRUMENTS.map { it.id },
            selections.musicIds
        )
        applyLeisureSelection(
            LeisureSlotId.FLEX,
            LeisureViewModel.DEFAULT_FLEX_OPTIONS.map { it.id },
            selections.flexIds
        )
        applyLeisureSelection(
            LeisureSlotId.LANGUAGE,
            LeisureViewModel.DEFAULT_LANGUAGE_OPTIONS.map { it.id },
            selections.languageIds
        )
        // LANGUAGE defaults to disabled (see LeisureSlotConfig.defaultFor) so
        // existing installs keep their meta-habit completion definition
        // unchanged. Enable the slot only when the user actually picked a
        // language in onboarding — otherwise leave it off and let the user
        // opt in later via the leisure-settings screen.
        if (selections.languageIds.isNotEmpty()) {
            leisurePreferences.updateSlotConfig(LeisureSlotId.LANGUAGE, enabled = true)
        }
        applyRoutineSelection("morning", selections)
        applyRoutineSelection("bedtime", selections)
        applyRoutineSelection("housework", selections)
    }

    private suspend fun applyLeisureSelection(
        slot: LeisureSlotId,
        defaultIds: List<String>,
        selectedIds: Set<String>
    ) {
        defaultIds.forEach { id ->
            leisurePreferences.setBuiltInHidden(slot, id, hidden = id !in selectedIds)
        }
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
