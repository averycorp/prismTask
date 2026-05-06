package com.averycorp.prismtask.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.BuildConfig
import com.averycorp.prismtask.data.remote.AccountDeletionService
import com.averycorp.prismtask.data.remote.AuthManager
import com.averycorp.prismtask.data.remote.GenericPreferenceSyncService
import com.averycorp.prismtask.data.remote.SortPreferencesSyncService
import com.averycorp.prismtask.data.remote.SyncService
import com.averycorp.prismtask.data.remote.ThemePreferencesSyncService
import com.averycorp.prismtask.testing.EmulatorAuthHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Inject

sealed class AuthState {
    data object SignedOut : AuthState()

    data object Loading : AuthState()

    data object SignedIn : AuthState()

    /** User signed in but their account is pending deletion. UI must offer
     *  Restore vs. confirm-and-sign-out. Sync has NOT been started yet. */
    data class RestorePending(val scheduledFor: Date) : AuthState()

    /** User signed in after the deletion grace window expired. Permanent
     *  purge has already been executed and the user is now signed out;
     *  the UI shows a final "your account has been deleted" screen. */
    data object AccountPurged : AuthState()

    data class Error(
        val message: String
    ) : AuthState()
}

@HiltViewModel
class AuthViewModel
@Inject
constructor(
    private val authManager: AuthManager,
    private val syncService: SyncService,
    private val sortPreferencesSyncService: SortPreferencesSyncService,
    private val themePreferencesSyncService: ThemePreferencesSyncService,
    private val genericPreferenceSyncService: GenericPreferenceSyncService,
    private val accountDeletionService: AccountDeletionService
) : ViewModel() {
    private val _authState = MutableStateFlow<AuthState>(
        if (authManager.isSignedIn.value) AuthState.SignedIn else AuthState.SignedOut
    )
    val authState: StateFlow<AuthState> = _authState

    val isSignedIn = authManager.isSignedIn
        .stateIn(viewModelScope, SharingStarted.Eagerly, authManager.isSignedIn.value)

    val userEmail: String? get() = authManager.currentUser.value?.email

    private val _skippedSignIn = MutableStateFlow(false)
    val skippedSignIn: StateFlow<Boolean> = _skippedSignIn

    fun onGoogleSignIn(idToken: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            val result = authManager.signInWithGoogle(idToken)
            if (result.isFailure) {
                // Firebase rejected the token (commonly a stale/revoked
                // credential from Credential Manager auto-select). Clear the
                // cached credential so the next attempt shows the account
                // picker instead of silently reusing the bad one.
                authManager.clearCredentialState()
                _authState.value = AuthState.Error("Sign-in failed")
                return@launch
            }
            handlePostAuthDeletionGuard()
        }
    }

    fun onEmailSignUp(email: String, password: String) {
        // TODO(email-verification): call user.sendEmailVerification() and
        // gate sync until verified once the verification flow lands.
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            val result = authManager.signUpWithEmail(email, password)
            if (result.isFailure) {
                _authState.value = AuthState.Error(
                    result.exceptionOrNull()?.localizedMessage ?: "Sign-up failed"
                )
                return@launch
            }
            handlePostAuthDeletionGuard()
        }
    }

    fun onEmailSignIn(email: String, password: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            val result = authManager.signInWithEmail(email, password)
            if (result.isFailure) {
                _authState.value = AuthState.Error(
                    result.exceptionOrNull()?.localizedMessage ?: "Sign-in failed"
                )
                return@launch
            }
            handlePostAuthDeletionGuard()
        }
    }

    /**
     * After Firebase Auth succeeds, check whether the account has been
     * marked for deletion before triggering any sync. Three outcomes:
     *
     *   - **NotPending**: normal sign-in — proceed to [runPostSignInSync].
     *   - **Pending**: route to RestoreAccountScreen via [AuthState.RestorePending].
     *     Critically, sync is NOT started — we don't want to pull data
     *     into a Room DB that's about to be wiped if the user confirms
     *     deletion.
     *   - **Expired**: grace window has passed. Execute permanent purge
     *     (backend deletes Postgres + Firebase Auth via Admin SDK + we
     *     wipe local state) and route to [AuthState.AccountPurged].
     *
     * Read failures fail closed: if we can't determine deletion status,
     * surface an error rather than silently letting the user back in to
     * an account they may have explicitly deleted.
     */
    private suspend fun handlePostAuthDeletionGuard() {
        val statusResult = accountDeletionService.checkDeletionStatus()
        val status = statusResult.getOrElse {
            authManager.signOut()
            _authState.value = AuthState.Error("Couldn't verify account status — try again")
            return
        }
        when (status) {
            AccountDeletionService.DeletionStatus.NotPending -> {
                _authState.value = AuthState.SignedIn
                runPostSignInSync()
            }
            is AccountDeletionService.DeletionStatus.Pending -> {
                _authState.value = AuthState.RestorePending(scheduledFor = status.scheduledFor)
            }
            AccountDeletionService.DeletionStatus.Expired -> {
                accountDeletionService.executePermanentPurge()
                _authState.value = AuthState.AccountPurged
            }
        }
    }

    /**
     * User tapped "Restore Account" on the post-sign-in restore prompt.
     * Clears the Firestore + backend deletion-pending state and proceeds
     * with normal sign-in (sync, listeners). On failure stays on the
     * restore screen so the user can retry — they're already signed in
     * and protected by the grace window, so no urgency to surface a
     * destructive choice on transient network failure.
     */
    fun onRestoreAccount() {
        viewModelScope.launch {
            val result = accountDeletionService.restoreAccount()
            if (result.isSuccess) {
                _authState.value = AuthState.SignedIn
                runPostSignInSync()
            } else {
                _authState.value = AuthState.Error(
                    "Couldn't restore account — check your connection and try again"
                )
            }
        }
    }

    /**
     * User tapped "Sign out" on the restore prompt — they want the
     * scheduled deletion to proceed. Sign them out without restoring;
     * the grace window keeps ticking and the next post-grace sign-in
     * (if any) executes the permanent purge.
     */
    fun onAbandonRestore() {
        viewModelScope.launch {
            authManager.signOut()
            _authState.value = AuthState.SignedOut
        }
    }

    fun onSignInError(message: String) {
        _authState.value = AuthState.Error(message)
    }

    /**
     * Debug-only: sign in against the Firebase Auth emulator as the default
     * test user so two-device sync can be exercised without a real Google
     * account. The UI gates this behind the same compile-time flags, but the
     * early return here guards against accidental calls from release code.
     */
    fun signInAsEmulatorTestUser() {
        if (!BuildConfig.DEBUG || !BuildConfig.USE_FIREBASE_EMULATOR) return
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            try {
                EmulatorAuthHelper.signInAsTestUser()
                handlePostAuthDeletionGuard()
            } catch (e: Exception) {
                _authState.value = AuthState.Error(
                    "Emulator sign-in failed: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    fun onSignOut() {
        viewModelScope.launch {
            syncService.stopRealtimeListeners()
            sortPreferencesSyncService.stopAfterSignOut()
            themePreferencesSyncService.stopAfterSignOut()
            genericPreferenceSyncService.stopAfterSignOut()
            authManager.signOut()
            _authState.value = AuthState.SignedOut
        }
    }

    /**
     * Fix B — pull-before-upload post-sign-in sequence.
     *
     * The former flow launched initialUpload and startRealtimeListeners
     * back-to-back on the same scope. Firestore's initial snapshot for the
     * just-registered listener then raced the upload loop: the upload's
     * `tagDao.getAllTagsOnce()` / `taskDao.getAllTasksOnce()` picked up
     * rows the concurrent pull had just inserted, and re-uploaded them as
     * brand-new cloud docs. That produced the 403× tasks / 722× tags /
     * 15× task_completions Firestore corruption.
     *
     * New order:
     *   1. fullSync — pushes nothing on first sign-in (no pending actions)
     *      and pulls the canonical cloud state into Room. Holds isSyncing,
     *      so any in-flight listener pull (if one somehow arrives) defers.
     *   2. initialUpload — guarded by
     *      [com.averycorp.prismtask.data.preferences.BuiltInSyncPreferences.isInitialUploadDone]
     *      via Fix A. Repeat sign-ins become no-ops here. On first sign-in,
     *      uploads local-only rows to the cloud and sets the flag.
     *   3. startRealtimeListeners last — at this point, local and cloud
     *      are already in agreement, so the listener's initial snapshot
     *      produces at most a benign duplicate-detection pull.
     *
     * Each step swallows exceptions locally (they're already logged inside
     * the sync service). Continuing past a failure lets the user operate
     * locally-only while a retry happens on the next sign-in or app boot.
     */
    private suspend fun runPostSignInSync() {
        try {
            syncService.fullSync(trigger = "signIn")
        } catch (_: Exception) {
            // Error already logged by fullSync/markSyncCompleted.
        }
        try {
            syncService.initialUpload()
        } catch (_: Exception) {
            // Error already logged by initialUpload.
        }
        syncService.startRealtimeListeners()
        sortPreferencesSyncService.startAfterSignIn()
        themePreferencesSyncService.startAfterSignIn()
        genericPreferenceSyncService.startAfterSignIn()
    }

    fun onSkipSignIn() {
        _skippedSignIn.value = true
    }
}
