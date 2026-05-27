package com.averycorp.prismtask.core.startup

import android.util.Log
import com.averycorp.prismtask.data.remote.AuthManager
import com.averycorp.prismtask.data.remote.GenericPreferenceSyncService
import com.averycorp.prismtask.data.remote.SortPreferencesSyncService
import com.averycorp.prismtask.data.remote.SyncService
import com.averycorp.prismtask.data.remote.ThemePreferencesSyncService
import com.averycorp.prismtask.data.remote.sync.BackendSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates app-level startup services after authentication is established.
 * Extracted from MainActivity to reduce its complexity and make startup
 * logic independently testable.
 *
 * Startup sequence on cold start (mirrors MainActivity.onCreate):
 *  1. [syncService].startAutoSync() — Firestore real-time listeners + initial pull
 *  2. [backendSyncService].startAutoSync() — Backend REST sync bootstrapping
 *  3. [sortPreferencesSyncService].startPushObserver() — push sort prefs to Firestore
 *  4. [themePreferencesSyncService].startPushObserver() — push theme prefs to Firestore
 *  5. [themePreferencesSyncService].ensurePullListener() — pull theme prefs from Firestore
 *  6. [genericPreferenceSyncService].startPushObserver() — push generic prefs to Firestore
 *  7. [genericPreferenceSyncService].ensurePullListener() — pull generic prefs from Firestore
 *
 * On interactive sign-in, call [onUserSignedIn] instead — the individual services
 * expose startAfterSignIn() which also force-pushes local state.
 *
 * Call [onUserSignedOut] on logout to remove Firestore listeners.
 */
@Singleton
class AppStartupCoordinator @Inject constructor(
    private val authManager: AuthManager,
    private val syncService: SyncService,
    private val sortPreferencesSyncService: SortPreferencesSyncService,
    private val themePreferencesSyncService: ThemePreferencesSyncService,
    private val genericPreferenceSyncService: GenericPreferenceSyncService,
    private val backendSyncService: BackendSyncService,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Start all sync services on cold-start (equivalent to the sequence in
     * MainActivity.onCreate). Safe to call multiple times — each service is
     * idempotent about duplicate registrations.
     */
    fun onColdStart() {
        try {
            syncService.startAutoSync()
        } catch (e: Exception) {
            Log.e(TAG, "Auto-sync failed to start", e)
        }
        try {
            backendSyncService.startAutoSync()
        } catch (e: Exception) {
            Log.e(TAG, "Backend auto-sync failed to start", e)
        }
        try {
            sortPreferencesSyncService.startPushObserver()
        } catch (e: Exception) {
            Log.e(TAG, "Sort prefs push observer failed to start", e)
        }
        try {
            themePreferencesSyncService.startPushObserver()
        } catch (e: Exception) {
            Log.e(TAG, "Theme prefs push observer failed to start", e)
        }
        try {
            themePreferencesSyncService.ensurePullListener()
        } catch (e: Exception) {
            Log.e(TAG, "Theme prefs pull listener failed to start", e)
        }
        try {
            genericPreferenceSyncService.startPushObserver()
            genericPreferenceSyncService.ensurePullListener()
        } catch (e: Exception) {
            Log.e(TAG, "Generic prefs sync failed to start", e)
        }
    }

    /**
     * Called after an interactive Google Sign-In completes. Each preference
     * sync service's startAfterSignIn() registers the pull listener AND
     * force-pushes local state to Firestore (last-write-wins merge).
     */
    fun onUserSignedIn() {
        scope.launch {
            try {
                sortPreferencesSyncService.startAfterSignIn()
            } catch (e: Exception) {
                Log.e(TAG, "Sort prefs startAfterSignIn failed", e)
            }
        }
        scope.launch {
            try {
                themePreferencesSyncService.startAfterSignIn()
            } catch (e: Exception) {
                Log.e(TAG, "Theme prefs startAfterSignIn failed", e)
            }
        }
        scope.launch {
            try {
                genericPreferenceSyncService.startAfterSignIn()
            } catch (e: Exception) {
                Log.e(TAG, "Generic prefs startAfterSignIn failed", e)
            }
        }
    }

    /**
     * Called on sign-out. Removes Firestore listeners to stop receiving
     * updates for the previous user's data.
     */
    fun onUserSignedOut() {
        try {
            sortPreferencesSyncService.stopAfterSignOut()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping sort prefs sync on sign-out", e)
        }
        try {
            themePreferencesSyncService.stopAfterSignOut()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping theme prefs sync on sign-out", e)
        }
        try {
            genericPreferenceSyncService.stopAfterSignOut()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping generic prefs sync on sign-out", e)
        }
    }

    companion object {
        private const val TAG = "AppStartupCoordinator"
    }
}
