package com.averycorp.prismtask.data.remote

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.preferencesDataStoreFile
import com.averycorp.prismtask.data.local.database.PrismTaskDatabase
import com.averycorp.prismtask.data.remote.api.DeletionRequest
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.notifications.NotificationWorkerScheduler
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the in-app "delete my account" path.
 *
 * The flow is intentionally optimistic-with-a-load-bearing-step: the only
 * operation we let abort the whole flow is the Firestore deletion-pending
 * mark. Once that succeeds, the user is protected by the 30-day grace
 * window regardless of whether the rest of the local cleanup completes —
 * they can sign in on any device to restore.
 *
 * Order of operations (each step's failure mode noted):
 *   1. Mark Firestore ``users/{uid}`` deletion-pending fields. **Abort on failure.**
 *   2. POST /api/v1/auth/me/deletion to mark the backend Postgres user. Best-effort.
 *      Many users don't have a backend account yet (Firebase-only) so 401/404 are
 *      expected and silenced.
 *   3. Stop Firestore real-time listeners so they don't fire against a wiped DB.
 *   4. Wipe Room — every table cleared via [PrismTaskDatabase.clearAllTables].
 *   5. Wipe DataStore preference files so a future sign-in starts fresh.
 *   6. Cancel WorkManager periodic workers.
 *   7. Cancel posted notifications.
 *   8. Sign out of Firebase Auth (does NOT call ``currentUser.delete()`` — that's
 *      the wrong primitive for soft-delete and triggers the recent-login
 *      requirement we'd rather not deal with on the client. Backend handles
 *      the Auth record deletion at /me/purge time, via Firebase Admin SDK,
 *      after the grace window expires.)
 *   9. Clear Credential Manager state.
 *
 * Steps 3-9 are best-effort with individual try/catch + logging — partial
 * failure here leaves the user with stale local state, but the Firestore
 * mark from step 1 still gives them the 30-day restore window. Worse than
 * "perfectly clean" but strictly better than rolling back the deletion mark
 * (which would silently leave their account active despite their explicit
 * request to delete it).
 */
@Singleton
class AccountDeletionService
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val authManager: AuthManager,
    private val syncService: SyncService,
    private val database: PrismTaskDatabase,
    private val notificationWorkerScheduler: NotificationWorkerScheduler,
    private val api: PrismTaskApi
) {
    private val firestore by lazy { FirebaseFirestore.getInstance() }

    /**
     * Mark the current account for deletion + tear down all local state.
     *
     * Returns:
     *   - [Result.success] if the Firestore mark succeeded. Local cleanup
     *     errors past that point are logged but do NOT fail the result —
     *     the user has been deleted as far as the source of truth is
     *     concerned, and aborting here would mislead them about the
     *     deletion status.
     *   - [Result.failure] if the Firestore mark failed (network down,
     *     Firestore unavailable, etc.). No local state has been changed.
     *     The caller should surface this to the user as "Couldn't reach
     *     the server. Try again."
     *
     * The optional ``initiatedFrom`` parameter is for support triage —
     * it gets stored on both the Firestore document and the backend user
     * row so we know where a deletion request originated.
     */
    suspend fun requestAccountDeletion(initiatedFrom: String = "android"): Result<Unit> {
        val uid = authManager.userId
            ?: return Result.failure(IllegalStateException("Not signed in"))

        try {
            markFirestorePending(uid, initiatedFrom)
        } catch (e: Exception) {
            Log.e(TAG, "Firestore deletion mark failed — aborting before any local state changes", e)
            return Result.failure(e)
        }

        // Best-effort: many users may not have a backend account, and
        // backend reachability shouldn't block the local cleanup once
        // the source-of-truth Firestore mark has succeeded.
        try {
            api.requestDeletion(DeletionRequest(initiatedFrom = initiatedFrom))
        } catch (e: Exception) {
            Log.w(TAG, "Backend deletion mark failed (non-fatal — Firestore mark already set)", e)
        }

        cleanLocalState()
        return Result.success(Unit)
    }

    /**
     * Read the current account's deletion-pending status from Firestore.
     *
     * Called from the sign-in handler immediately after Firebase Auth succeeds
     * but before any sync runs. The result drives whether the user lands on
     * Today (NotPending), the restore screen (Pending), or the
     * post-permanent-deletion screen (after [executePermanentPurge] returns).
     *
     * Failures are surfaced as [Result.failure] rather than fail-open — if we
     * can't read the doc, signing the user in could let them keep using an
     * account they explicitly deleted. Caller decides whether to retry or
     * surface an error.
     */
    suspend fun checkDeletionStatus(): Result<DeletionStatus> {
        val uid = authManager.userId ?: return Result.success(DeletionStatus.NotPending)
        return try {
            val doc = firestore.collection("users").document(uid).get().await()
            val pendingAt = doc.getTimestamp("deletion_pending_at")
            val scheduledFor = doc.getTimestamp("deletion_scheduled_for")
            if (pendingAt == null || scheduledFor == null) {
                Result.success(DeletionStatus.NotPending)
            } else if (Date().before(scheduledFor.toDate())) {
                Result.success(DeletionStatus.Pending(scheduledFor.toDate()))
            } else {
                Result.success(DeletionStatus.Expired)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read deletion status from Firestore", e)
            Result.failure(e)
        }
    }

    /**
     * Cancel a pending deletion (the user tapped "Restore" within the
     * grace window). Clears Firestore deletion fields + best-effort
     * backend cancel. After this returns successfully the caller may
     * proceed with normal sign-in.
     */
    suspend fun restoreAccount(): Result<Unit> {
        val uid = authManager.userId
            ?: return Result.failure(IllegalStateException("Not signed in"))

        try {
            firestore.collection("users").document(uid)
                .update(
                    mapOf(
                        "deletion_pending_at" to FieldValue.delete(),
                        "deletion_scheduled_for" to FieldValue.delete(),
                        "deletion_initiated_from" to FieldValue.delete()
                    )
                )
                .await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear Firestore deletion fields", e)
            return Result.failure(e)
        }

        try {
            api.cancelDeletion()
        } catch (e: Exception) {
            Log.w(TAG, "Backend deletion cancel failed (non-fatal — Firestore cleared)", e)
        }
        return Result.success(Unit)
    }

    /**
     * Execute the post-grace permanent deletion. Called when [checkDeletionStatus]
     * returns [DeletionStatus.Expired]. The backend ``/me/purge`` endpoint deletes
     * the Postgres user row (CASCADE handles dependents) and the Firebase Auth
     * record (via Firebase Admin SDK). Local state is then wiped and the user
     * is signed out — by the time this returns the device is in a fresh-install
     * state for this account.
     *
     * Firestore user-collection data is intentionally left orphaned (the Android
     * client lacks recursive-delete; backend would need additional plumbing).
     * After Firebase Auth deletion, no one can re-authenticate as this user, so
     * the orphan data is unreachable. A manual admin sweep covers the long tail.
     */
    suspend fun executePermanentPurge(): Result<Unit> {
        // Backend handles Postgres CASCADE + Firebase Auth deletion. Failure
        // here is non-fatal because the local cleanup still needs to happen
        // (the user must be signed out and their local data wiped regardless).
        try {
            val response = api.purgeAccount()
            if (!response.isSuccessful) {
                Log.w(TAG, "Backend purge returned ${response.code()} — proceeding with local cleanup")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Backend purge call failed (non-fatal — local cleanup proceeds)", e)
        }

        cleanLocalState()
        return Result.success(Unit)
    }

    private suspend fun markFirestorePending(uid: String, initiatedFrom: String) {
        val now = Date()
        val scheduledFor = Date(now.time + GRACE_DAYS_MILLIS)
        val payload = mapOf(
            "deletion_pending_at" to com.google.firebase.Timestamp(now),
            "deletion_scheduled_for" to com.google.firebase.Timestamp(scheduledFor),
            "deletion_initiated_from" to initiatedFrom
        )
        firestore.collection("users").document(uid)
            .set(payload, SetOptions.merge())
            .await()
    }

    private suspend fun cleanLocalState() {
        runCatching { syncService.stopRealtimeListeners() }
            .onFailure { Log.w(TAG, "stopRealtimeListeners failed", it) }

        runCatching { authManager.signOut() }
            .onFailure { Log.w(TAG, "Firebase signOut failed", it) }

        runCatching { withContext(Dispatchers.IO) { database.clearAllTables() } }
            .onFailure { Log.w(TAG, "Room clearAllTables failed", it) }

        runCatching { wipeAllPreferenceFiles() }
            .onFailure { Log.w(TAG, "Wiping DataStore preference files failed", it) }

        runCatching { notificationWorkerScheduler.cancelAllForAccountDeletion() }
            .onFailure { Log.w(TAG, "Worker cancel failed", it) }

        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancelAll()
        }.onFailure { Log.w(TAG, "Notification cancelAll failed", it) }

        runCatching { authManager.clearCredentialState() }
            .onFailure { Log.w(TAG, "clearCredentialState failed", it) }
    }

    /**
     * Delete every DataStore preference file the app owns. Cached
     * DataStore singletons keep their in-memory state, but we've already
     * signed out and the user lands on the auth screen — nothing is
     * actively reading these prefs. On the next process boot the singleton
     * cache is gone and DataStore re-reads from the now-empty files.
     *
     * Listed by file name (the ``name = "..."`` argument passed to
     * ``preferencesDataStore``) rather than by injected pref class so we
     * don't need a 30-singleton constructor. Adding a new DataStore must
     * include adding its name here — the unit test enumerates the directory
     * to catch any miss.
     */
    @Suppress("SpreadOperator")
    private fun wipeAllPreferenceFiles() {
        ALL_PREFERENCE_DATASTORE_NAMES.forEach { name ->
            try {
                val file = context.preferencesDataStoreFile(name)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't delete DataStore file $name", e)
            }
        }
    }

    /** Outcome of [checkDeletionStatus]. */
    sealed class DeletionStatus {
        /** Account is active — sign-in should proceed normally. */
        data object NotPending : DeletionStatus()

        /** Account is pending deletion within the grace window. UI must
         *  offer restore vs. confirm-and-sign-out before any sync runs. */
        data class Pending(val scheduledFor: Date) : DeletionStatus()

        /** Grace window has passed — sign-in handler should call
         *  [executePermanentPurge] and then route to a "your account has
         *  been permanently deleted" screen. */
        data object Expired : DeletionStatus()
    }

    companion object {
        private const val TAG = "AccountDeletion"
        const val GRACE_DAYS = 30
        private const val GRACE_DAYS_MILLIS = GRACE_DAYS * 24L * 60L * 60L * 1000L

        /**
         * All DataStore preference file names the app uses. Keep in sync with
         * ``preferencesDataStore(name = "...")`` declarations across
         * ``data/preferences/``, ``di/PreferencesModule.kt``, ``widget/``,
         * and any other location that creates a DataStore.
         *
         * Excludes ``timer_widget_state`` and ``widget_config`` — those are
         * local widget UI state with no PII, and the widgets themselves
         * are kept on the user's home screen across sign-outs (they show an
         * empty/sign-in prompt when the underlying data is gone).
         */
        val ALL_PREFERENCE_DATASTORE_NAMES: List<String> = listOf(
            "auth_token_prefs",
            "backend_sync_prefs",
            "pro_status_prefs",
            "built_in_sync_prefs",
            "onboarding_prefs",
            "theme_prefs",
            "archive_prefs",
            "dashboard_prefs",
            "tab_prefs",
            "task_behavior_prefs",
            "leisure_prefs",
            "habit_list_prefs",
            "template_prefs",
            "user_prefs",
            "morning_checkin_prefs",
            "medication_prefs",
            "gcal_sync_prefs",
            "coaching_prefs",
            "daily_essentials_prefs",
            "a11y_prefs",
            "voice_prefs",
            "timer_prefs",
            "sort_prefs",
            "nd_prefs",
            "notification_prefs",
            "medication_migration_prefs",
            "reengagement_prefs",
            "sync_device_prefs",
            "advanced_tuning_prefs",
            "productive_streak_prefs",
            "tour_card_prefs",
            "rating_prompt_prefs"
        )
    }
}
