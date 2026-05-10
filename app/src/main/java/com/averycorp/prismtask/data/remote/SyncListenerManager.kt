package com.averycorp.prismtask.data.remote

import com.averycorp.prismtask.data.remote.sync.PrismSyncLogger
import com.averycorp.prismtask.data.remote.sync.SyncStateRepository
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * D8 Item 7 Strangler Fig 7c — Firestore realtime listener surface
 * extracted from [SyncService]. Manages the registration / teardown of
 * `onSnapshot` listeners across all synced collections, and routes
 * incoming changes back into the orchestrator via passed-in lambdas
 * (`pull` + `onRemoteDeletes`) so this class does not need to know
 * about the rest of SyncService's god-class state.
 *
 * Behaviour-identical to the inline `startRealtimeListeners` /
 * `stopRealtimeListeners` it replaced; the surface still uses
 * [SyncDispatchTables] for collection names + entity routing, and the
 * pending-write / empty-changes guards mirror the original.
 *
 * Constructor injection takes the small set of dependencies the
 * listener surface actually needs: auth (for the per-user collection
 * path), Firestore, the sync-state repository (for listener-active +
 * sync lifecycle telemetry), and the logger.
 */
@Singleton
class SyncListenerManager
@Inject
constructor(
    private val authManager: AuthManager,
    private val firestore: FirebaseFirestore,
    private val syncStateRepository: SyncStateRepository,
    private val logger: PrismSyncLogger
) {
    private val listeners = mutableListOf<ListenerRegistration>()

    /**
     * Registered Firestore collections — kept in lockstep with
     * [SyncDispatchTables.collectionNameFor]; any new entity must be
     * added here for its delete-listener to fire.
     */
    internal companion object {
        const val SOURCE_FIREBASE: String = "firebase"

        val SYNCED_COLLECTIONS: List<String> = listOf(
            "tasks", "projects", "tags", "habits", "habit_completions",
            "habit_logs", "task_completions", "task_timings", "milestones",
            "project_phases", "project_risks", "external_anchors", "task_dependencies", "task_templates",
            "courses", "course_completions", "leisure_logs", "self_care_steps", "self_care_logs",
            "medications", "medication_doses",
            "medication_slots", "medication_slot_overrides", "medication_tier_states",
            "notification_profiles", "custom_sounds", "saved_filters", "nlp_shortcuts",
            "habit_templates", "project_templates", "boundary_rules",
            "automation_rules",
            "check_in_logs", "mood_energy_logs", "focus_release_logs",
            "medication_refills", "weekly_reviews", "daily_essential_slot_completions",
            "assignments", "attachments", "study_logs"
        )
    }

    private fun userCollection(collection: String) =
        authManager.userId?.let { firestore.collection("users").document(it).collection(collection) }

    /**
     * Re-register all snapshot listeners. Any active listeners are torn
     * down first to keep the active set in lockstep with the latest
     * auth state. The lambdas:
     *  - [isSyncing] — short-circuit handler if a sync is already
     *    running (the listener's job is to nudge a pull, not enqueue
     *    parallel ones).
     *  - [onRemoteDeletes] — called with `(collection, cloudIds)` when
     *    snapshot reports `DocumentChange.Type.REMOVED`.
     *  - [pull] — runs the actual pull pass; returns a summary used for
     *    the telemetry record.
     */
    fun start(
        scope: CoroutineScope,
        isSyncing: () -> Boolean,
        onRemoteDeletes: suspend (collection: String, cloudIds: List<String>) -> Unit,
        pull: suspend () -> SyncService.PullSummary
    ) {
        stop()
        SYNCED_COLLECTIONS.forEach { collection ->
            val reg = userCollection(collection)?.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    logger.warn(
                        operation = "listener.error",
                        entity = "collection",
                        id = collection,
                        throwable = error
                    )
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                if (snapshot.metadata.hasPendingWrites()) return@addSnapshotListener
                if (snapshot.documentChanges.isEmpty()) return@addSnapshotListener
                syncStateRepository.recordListenerSnapshot(collection, snapshot.documentChanges.size)
                val removedCloudIds = snapshot.documentChanges
                    .filter { it.type == DocumentChange.Type.REMOVED }
                    .map { it.document.id }
                scope.launch {
                    if (isSyncing()) return@launch
                    val start = System.currentTimeMillis()
                    syncStateRepository.markSyncStarted(
                        source = SOURCE_FIREBASE,
                        trigger = "listener:$collection"
                    )
                    try {
                        if (removedCloudIds.isNotEmpty()) {
                            onRemoteDeletes(collection, removedCloudIds)
                        }
                        val pullSummary = pull()
                        syncStateRepository.markSyncCompleted(
                            source = SOURCE_FIREBASE,
                            success = true,
                            durationMs = System.currentTimeMillis() - start,
                            pulled = pullSummary.applied,
                            permanentlyFailed = pullSummary.skippedPermanent
                        )
                    } catch (e: Exception) {
                        syncStateRepository.markSyncCompleted(
                            source = SOURCE_FIREBASE,
                            success = false,
                            durationMs = System.currentTimeMillis() - start,
                            throwable = e
                        )
                        try {
                            com.google.firebase.crashlytics.FirebaseCrashlytics
                                .getInstance()
                                .recordException(e)
                        } catch (_: Exception) {
                            // crashlytics unavailable in tests — swallow
                        }
                    }
                }
            }
            if (reg != null) listeners.add(reg)
        }
        syncStateRepository.markListenersActive(listeners.isNotEmpty())
    }

    fun stop() {
        listeners.forEach { it.remove() }
        listeners.clear()
        syncStateRepository.markListenersActive(false)
    }
}
