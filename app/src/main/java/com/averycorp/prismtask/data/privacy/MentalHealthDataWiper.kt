package com.averycorp.prismtask.data.privacy

import android.util.Log
import com.averycorp.prismtask.data.local.database.DatabaseTransactionRunner
import com.averycorp.prismtask.data.local.database.PrismTaskDatabase
import com.averycorp.prismtask.data.remote.AuthManager
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Atomic partial-wipe of the user's mental-health-flavored data.
 *
 * Per Mental-Health-First Audit § G5 (`docs/audits/MENTAL_HEALTH_FIRST_AUDIT.md`):
 * mental-health rows have different privacy stakes than task data
 * (employer / insurance / family access risk), so the user must be able
 * to purge mood / check-in / boundary / weekly-review / focus-release
 * data without nuking their tasks, habits, or projects.
 *
 * Tables wiped (Room):
 *  - `mood_energy_logs`
 *  - `check_in_logs`
 *  - `weekly_reviews`
 *  - `boundary_rules`
 *  - `focus_release_logs`
 *  - matching rows in `sync_metadata` (so a re-sync doesn't try to push
 *    delete operations against rows that are already gone)
 *
 * Untouched (load-bearing invariant the test pins):
 *  - `tasks`, `habits`, `projects`, `tags`, every other row in Room.
 *
 * `ClinicalReportGenerator` builds its output on-the-fly from the rows
 * above — there is no cached `clinical_report` table to wipe (confirmed
 * by grep at audit time; if a cache ever lands it must be appended to
 * the wipe list and to the atomicity test).
 *
 * The local Room deletes run inside a single `withTransaction { }` block
 * so the wipe is all-or-nothing. The Firestore deletes are best-effort:
 * the load-bearing correctness property is the local wipe — the user
 * can rotate / delete their Firebase account later if the cloud-side
 * delete fails today.
 *
 * @see com.averycorp.prismtask.data.remote.AccountDeletionService
 *     for the full-account wipe path (different primitive — soft-deletes
 *     the account, wipes every table, signs out).
 */
@Singleton
class MentalHealthDataWiper @Inject constructor(
    private val database: PrismTaskDatabase,
    private val transactionRunner: DatabaseTransactionRunner,
    private val authManager: AuthManager,
    private val firestore: FirebaseFirestore
) {

    /**
     * Outcome of [wipeMentalHealthData]. The local wipe is always required
     * to succeed for the operation to be considered done; the cloud step
     * is best-effort and reported back so the caller can show a "synced"
     * vs "will sync later" snackbar.
     */
    sealed class WipeResult {
        /** Local wipe succeeded; cloud delete also completed cleanly. */
        data object Success : WipeResult()

        /**
         * Local wipe succeeded but the cloud-side delete couldn't run —
         * either because the user is signed out, or Firestore raised. The
         * caller should tell the user the local data is gone and the
         * cloud will catch up on next reconnect.
         */
        data class LocalOnly(val reason: String) : WipeResult()

        /**
         * The local transaction itself failed. No data was deleted.
         * Caller should surface a retry prompt.
         */
        data class Failed(val cause: Throwable) : WipeResult()
    }

    /**
     * Run the wipe. Local Room deletes are wrapped in a single Room
     * transaction so partial deletion is impossible — either every target
     * table is empty after this returns, or none of them are.
     *
     * Returns:
     *  - [WipeResult.Success] when local + cloud are both done.
     *  - [WipeResult.LocalOnly] when local succeeded but cloud failed or
     *    was unreachable. Cloud delete is best-effort by design.
     *  - [WipeResult.Failed] when the local transaction itself raised. No
     *    rows were deleted in that case (Room rolls back on throw).
     */
    suspend fun wipeMentalHealthData(): WipeResult {
        try {
            transactionRunner.withTransaction {
                database.moodEnergyLogDao().deleteAll()
                database.checkInLogDao().deleteAll()
                database.weeklyReviewDao().deleteAll()
                database.boundaryRuleDao().deleteAll()
                database.focusReleaseLogDao().deleteAll()

                // Drop the sync-metadata rows for these entity types so a
                // future push doesn't try to upsert against rows that no
                // longer exist locally, and a future pull doesn't reuse
                // stale cloud IDs to re-create them.
                val syncMetadataDao = database.syncMetadataDao()
                MH_ENTITY_TYPES.forEach { entityType ->
                    syncMetadataDao.deleteAllForType(entityType)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Local mental-health data wipe failed", t)
            return WipeResult.Failed(t)
        }

        // Cloud delete is best-effort. If the user is signed out, or
        // Firestore is unreachable, the local wipe still stands and the
        // cloud copy will be reconciled on next sync (the deleted local
        // rows have no metadata pointing at the cloud docs, so the
        // standard pull path will overwrite them — see G5 audit note).
        val uid = authManager.userId
        if (uid == null) {
            return WipeResult.LocalOnly("Not signed in")
        }

        return try {
            MH_FIRESTORE_COLLECTIONS.forEach { collection ->
                deleteCollection(uid, collection)
            }
            WipeResult.Success
        } catch (t: Throwable) {
            Log.w(TAG, "Cloud mental-health data wipe failed (local wipe stands)", t)
            WipeResult.LocalOnly(t.message ?: "Couldn't reach the server")
        }
    }

    /**
     * Delete every doc under `users/{uid}/{collection}`. Paginates with
     * a generous limit to keep the round-trip count down, and stops once
     * Firestore returns an empty page. Per-doc deletes (rather than a
     * batched WriteBatch) so a single bad doc doesn't kill the whole run
     * — `wipeMentalHealthData` swallows the throw at the call site, but
     * a partial cloud-side wipe is still better than zero.
     */
    private suspend fun deleteCollection(uid: String, collection: String) {
        val ref = firestore.collection("users").document(uid).collection(collection)
        while (true) {
            val snapshot = ref.limit(FIRESTORE_PAGE_SIZE).get().await()
            if (snapshot.isEmpty) break
            snapshot.documents.forEach { doc ->
                runCatching { doc.reference.delete().await() }
                    .onFailure { Log.w(TAG, "Failed to delete $collection/${doc.id}", it) }
            }
            // Last page is shorter than the page size — bail to avoid an
            // extra empty round-trip.
            if (snapshot.size() < FIRESTORE_PAGE_SIZE.toInt()) break
        }
    }

    companion object {
        private const val TAG = "MHDataWiper"

        /** Page size for the per-collection Firestore delete loop. */
        private const val FIRESTORE_PAGE_SIZE: Long = 100L

        /**
         * Entity-type strings (singular form) used in `sync_metadata.entity_type`
         * for the mental-health-flavored tables. Mirrors
         * `SyncDispatchTables.tableForEntityType` so the dispatch table and the
         * wiper stay in sync. New MH-flavored tables MUST be appended here AND
         * to the atomicity test in `MentalHealthDataWiperTest`.
         */
        val MH_ENTITY_TYPES: List<String> = listOf(
            "mood_energy_log",
            "check_in_log",
            "weekly_review",
            "boundary_rule",
            "focus_release_log"
        )

        /**
         * Firestore subcollection names under `users/{uid}/` for the same set
         * of tables. Plural form to match the actual collection paths in
         * `SyncService` / `SyncListenerManager`.
         */
        val MH_FIRESTORE_COLLECTIONS: List<String> = listOf(
            "mood_energy_logs",
            "check_in_logs",
            "weekly_reviews",
            "boundary_rules",
            "focus_release_logs"
        )
    }
}
