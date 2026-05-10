package com.averycorp.prismtask.data.remote

import com.averycorp.prismtask.data.preferences.BuiltInSyncPreferences
import com.averycorp.prismtask.data.remote.sync.PrismSyncLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * D8 Item 7 Strangler Fig 7d — initial-upload surface (orchestrator
 * slice). Owns the one-shot guard, the `isSyncing` lifecycle, the
 * completion/failure telemetry, and the post-release single-pull
 * recovery that were previously the body of [SyncService.initialUpload].
 * The actual per-entity upload work (the body of `doInitialUpload`
 * which fans out across every DAO and pushes each row to Firestore)
 * remains on SyncService for now; extracting that fan-out requires the
 * same DAO + mapper threading as 7b's full extraction and is a
 * follow-on PR.
 *
 * Three lambdas are passed in:
 *  - [isSyncing] — read the orchestrator's @Volatile flag so the guard
 *    short-circuits when another sync is running.
 *  - [setSyncing] — set the flag while the upload window is held; the
 *    caller still owns the @Volatile field on SyncService because other
 *    pull / listener paths read it directly.
 *  - [doUpload] — the still-inlined `doInitialUpload` body.
 *  - [postReleasePull] — exactly-one pullRemoteChanges() after release,
 *    so any cloud state that changed during the upload window still
 *    lands locally.
 *
 * The orchestrator does NOT call back into Firestore directly; that
 * stays on the lambdas. This keeps the surface clean for now and the
 * follow-on PR can absorb `doInitialUpload` into the same class
 * without further dependency wiring.
 */
@Singleton
class SyncInitialUploadOrchestrator
@Inject
constructor(
    private val builtInSyncPreferences: BuiltInSyncPreferences,
    private val logger: PrismSyncLogger
) {
    /**
     * Run the initial upload pass if not already done. Returns false if
     * the run was skipped (already done OR another sync running), true
     * on successful upload. Throws on upload failure so the caller can
     * surface to telemetry; the `isSyncing` flag is released in
     * either case.
     */
    suspend fun runIfNeeded(
        isSyncing: () -> Boolean,
        setSyncing: (Boolean) -> Unit,
        doUpload: suspend () -> Unit,
        postReleasePull: suspend () -> Unit
    ): Boolean {
        // Fix A — one-shot guard. Every sign-in used to re-run the entire
        // upload loop and mint brand-new Firestore docs for every local
        // row, fueling the duplication spiral. The flag is only set on
        // successful completion so a mid-run failure stays retryable on
        // the next sign-in.
        if (builtInSyncPreferences.isInitialUploadDone()) {
            logger.info(operation = "initialUpload.skipped", detail = "reason=already_done")
            return false
        }

        // Fix B — hold isSyncing for the duration of the upload loop so
        // listener-triggered pulls defer. AuthViewModel serializes
        // fullSync → initialUpload so there is normally no contention
        // here, but the guard is kept as defense-in-depth.
        if (isSyncing()) {
            logger.info(
                operation = "initialUpload.deferred",
                detail = "reason=another_sync_running"
            )
            return false
        }
        setSyncing(true)
        logger.info(operation = "initialUpload.started")
        val uploadStart = System.currentTimeMillis()
        var success = false
        try {
            doUpload()
            builtInSyncPreferences.setInitialUploadDone(true)
            success = true
            logger.info(
                operation = "initialUpload.completed",
                status = "success",
                durationMs = System.currentTimeMillis() - uploadStart
            )
        } catch (e: Throwable) {
            logger.error(
                operation = "initialUpload.failed",
                durationMs = System.currentTimeMillis() - uploadStart,
                throwable = e
            )
            throw e
        } finally {
            setSyncing(false)
        }

        // Fix B mitigation — while isSyncing was held above, any
        // listener-triggered pull callback short-circuited at
        // `if (isSyncing) return@launch`. Those callbacks are
        // fire-and-forget; once dropped, they don't re-run by themselves.
        // Run exactly one pullRemoteChanges() after release so any cloud
        // state that changed during the upload window still lands
        // locally. Realtime listeners keep firing normally after this.
        if (success) {
            try {
                postReleasePull()
            } catch (e: Throwable) {
                logger.error(
                    operation = "initialUpload.post_release_pull",
                    throwable = e
                )
            }
        }
        return success
    }
}
