package com.averycorp.prismtask.ui.screens.settings.sections

import android.content.Intent
import android.util.Log
import com.averycorp.prismtask.data.calendar.CalendarConnectionResult
import com.averycorp.prismtask.data.calendar.CalendarInfo
import com.averycorp.prismtask.data.calendar.CalendarManager
import com.averycorp.prismtask.data.calendar.CalendarSyncPreferences
import com.averycorp.prismtask.data.calendar.DIRECTION_BOTH
import com.averycorp.prismtask.data.calendar.FREQUENCY_15MIN
import com.averycorp.prismtask.data.remote.AccountDeletionService
import com.averycorp.prismtask.data.remote.AuthManager
import com.averycorp.prismtask.data.remote.SyncService
import com.averycorp.prismtask.data.repository.CalendarSyncRepository
import com.averycorp.prismtask.workers.CalendarSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Sync-related Settings state and setters — Firebase Auth + Sync, Google Calendar
 * sync, and account deletion. The coordinator forwards user-visible messages
 * through a shared `MutableSharedFlow<String>` injected via [attach].
 *
 * Extracted from [com.averycorp.prismtask.ui.screens.settings.SettingsViewModel]
 * as part of T1.2 of REFACTOR_TIERS_1_3_AUDIT.
 */
class SyncSettingsViewModel @Inject constructor(
    private val authManager: AuthManager,
    private val syncService: SyncService,
    private val calendarManager: CalendarManager,
    private val calendarSyncPreferences: CalendarSyncPreferences,
    private val calendarSyncRepository: CalendarSyncRepository,
    private val calendarSyncScheduler: CalendarSyncScheduler,
    private val accountDeletionService: AccountDeletionService
) {
    private lateinit var scope: CoroutineScope
    private lateinit var messages: MutableSharedFlow<String>

    // --- Auth ---
    val isSignedIn: StateFlow<Boolean> get() = authManager.isSignedIn
    val userEmail: String? get() = authManager.currentUser.value?.email

    // --- Sync state ---
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    private val _isDeletingAccount = MutableStateFlow(false)
    val isDeletingAccount: StateFlow<Boolean> = _isDeletingAccount

    private val _accountDeletionCompleted = MutableSharedFlow<Unit>()
    val accountDeletionCompleted: SharedFlow<Unit> = _accountDeletionCompleted.asSharedFlow()

    // --- Calendar state ---
    val isGCalConnected: StateFlow<Boolean> get() = calendarManager.isCalendarConnected
    val gCalAccountEmail: StateFlow<String?> get() = calendarManager.connectedAccountEmail

    lateinit var gCalSyncEnabled: StateFlow<Boolean>
        private set
    lateinit var gCalSyncCalendarId: StateFlow<String>
        private set
    lateinit var gCalSyncDirection: StateFlow<String>
        private set
    lateinit var gCalShowEvents: StateFlow<Boolean>
        private set
    lateinit var gCalSyncCompletedTasks: StateFlow<Boolean>
        private set
    lateinit var gCalSyncFrequency: StateFlow<String>
        private set
    lateinit var gCalLastSyncTimestamp: StateFlow<Long>
        private set

    private val _gCalAvailableCalendars = MutableStateFlow<List<CalendarInfo>>(emptyList())
    val gCalAvailableCalendars: StateFlow<List<CalendarInfo>> = _gCalAvailableCalendars

    private val _isGCalSyncing = MutableStateFlow(false)
    val isGCalSyncing: StateFlow<Boolean> = _isGCalSyncing

    private val _calendarConsentIntent = MutableSharedFlow<Intent>()
    val calendarConsentIntent: SharedFlow<Intent> = _calendarConsentIntent.asSharedFlow()

    fun attach(scope: CoroutineScope, messages: MutableSharedFlow<String>) {
        this.scope = scope
        this.messages = messages
        val started = SharingStarted.WhileSubscribed(5000)
        gCalSyncEnabled = calendarSyncPreferences.isCalendarSyncEnabled().stateIn(scope, started, false)
        gCalSyncCalendarId = calendarSyncPreferences.getSyncCalendarId().stateIn(scope, started, "primary")
        gCalSyncDirection = calendarSyncPreferences.getSyncDirection().stateIn(scope, started, DIRECTION_BOTH)
        gCalShowEvents = calendarSyncPreferences.getShowCalendarEvents().stateIn(scope, started, true)
        gCalSyncCompletedTasks =
            calendarSyncPreferences.getSyncCompletedTasks().stateIn(scope, started, false)
        gCalSyncFrequency =
            calendarSyncPreferences.getSyncFrequency().stateIn(scope, started, FREQUENCY_15MIN)
        gCalLastSyncTimestamp = calendarSyncPreferences.getLastSyncTimestamp().stateIn(scope, started, 0L)
    }

    fun onSync() {
        scope.launch {
            _isSyncing.value = true
            try {
                syncService.fullSync()
                messages.emit("Sync complete")
            } catch (e: Exception) {
                Log.e("SettingsVM", "Sync failed", e)
                messages.emit("Sync failed")
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun onSignOut() {
        scope.launch {
            authManager.signOut()
            messages.emit("Signed out")
        }
    }

    /**
     * Triggered from the Settings → Account & Sync → Delete Account flow
     * after the user types DELETE and confirms.
     *
     * Returns through [accountDeletionCompleted] on success — the UI then
     * navigates back to Today (which detects the signed-out state and
     * routes to AuthScreen). Errors emit on the shared messages flow.
     */
    fun onRequestAccountDeletion() {
        if (_isDeletingAccount.value) return
        _isDeletingAccount.value = true
        scope.launch {
            try {
                val result = accountDeletionService.requestAccountDeletion(initiatedFrom = "android")
                if (result.isSuccess) {
                    _accountDeletionCompleted.emit(Unit)
                } else {
                    messages.emit(
                        "Couldn't delete account — check your connection and try again"
                    )
                }
            } finally {
                _isDeletingAccount.value = false
            }
        }
    }

    // --- Google Calendar API Sync ---
    fun connectGoogleCalendar() {
        scope.launch {
            when (val result = calendarManager.connectCalendar()) {
                is CalendarConnectionResult.Connected -> {
                    loadGCalCalendars()
                    messages.emit("Google Calendar connected")
                }
                is CalendarConnectionResult.NeedsConsent -> {
                    _calendarConsentIntent.emit(result.signInIntent)
                }
                is CalendarConnectionResult.Failed -> {
                    messages.emit(result.message)
                }
            }
        }
    }

    fun handleCalendarConsentResult(data: Intent?) {
        scope.launch {
            calendarManager.handleSignInResult(data)
                .onSuccess {
                    loadGCalCalendars()
                    messages.emit("Google Calendar connected")
                }
                .onFailure { _ ->
                    messages.emit("Couldn't connect Google Calendar")
                }
        }
    }

    fun disconnectGoogleCalendar() {
        scope.launch {
            calendarManager.disconnectCalendar()
            calendarSyncPreferences.clearAll()
            _gCalAvailableCalendars.value = emptyList()
            messages.emit("Google Calendar disconnected")
        }
    }

    fun loadGCalCalendars() {
        scope.launch {
            // Prefer the backend-mediated list so the Android client
            // doesn't need the Calendar scope long-term. Fall back to the
            // legacy direct fetch only when the backend call fails so the
            // calendar picker still works during the transition period.
            val backendCalendars = calendarSyncRepository.listCalendars().getOrNull()
            _gCalAvailableCalendars.value = backendCalendars
                ?: calendarManager.getUserCalendars()
        }
    }

    fun setGCalSyncEnabled(enabled: Boolean) {
        scope.launch {
            calendarSyncPreferences.setCalendarSyncEnabled(enabled)
            calendarSyncRepository.syncSettingsToBackend()
            calendarSyncScheduler.applyPreferences()
            if (enabled) {
                messages.emit("Google Calendar sync enabled")
            } else {
                messages.emit("Google Calendar sync disabled")
            }
        }
    }

    fun setGCalSyncCalendarId(calendarId: String) {
        scope.launch {
            calendarSyncPreferences.setSyncCalendarId(calendarId)
            calendarSyncRepository.syncSettingsToBackend()
        }
    }

    fun setGCalSyncDirection(direction: String) {
        scope.launch {
            calendarSyncPreferences.setSyncDirection(direction)
            calendarSyncRepository.syncSettingsToBackend()
        }
    }

    fun setGCalShowEvents(show: Boolean) {
        scope.launch {
            calendarSyncPreferences.setShowCalendarEvents(show)
            calendarSyncRepository.syncSettingsToBackend()
        }
    }

    fun setGCalSyncCompletedTasks(sync: Boolean) {
        scope.launch {
            calendarSyncPreferences.setSyncCompletedTasks(sync)
            calendarSyncRepository.syncSettingsToBackend()
        }
    }

    fun setGCalSyncFrequency(frequency: String) {
        scope.launch {
            calendarSyncPreferences.setSyncFrequency(frequency)
            calendarSyncRepository.syncSettingsToBackend()
            calendarSyncScheduler.applyPreferences()
        }
    }

    fun syncGCalNow() {
        scope.launch {
            _isGCalSyncing.value = true
            try {
                val result = calendarSyncRepository.syncNow()
                if (result.isSuccess) {
                    messages.emit("Google Calendar sync complete")
                } else {
                    messages.emit("Calendar sync failed")
                }
            } catch (_: Exception) {
                messages.emit("Calendar sync failed")
            } finally {
                _isGCalSyncing.value = false
            }
        }
    }
}
