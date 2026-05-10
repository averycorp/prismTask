package com.averycorp.prismtask.data.remote

import com.averycorp.prismtask.data.local.dao.SyncMetadataDao
import com.averycorp.prismtask.data.local.entity.SyncMetadataEntity
import com.averycorp.prismtask.data.remote.sync.PrismSyncLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * D8 Item 7 Strangler Fig 7b — push surface (orchestrator slice). Owns
 * the pending-action sort + iteration + per-row success/failure
 * bookkeeping that was previously the body of
 * [SyncService.pushLocalChanges]. The 36-branch create / update / delete
 * dispatch remains on SyncService for now — extracting it requires
 * threading ~25 DAOs + the SyncMapper objects through a new class,
 * which is its own follow-on PR per STOP-7D (sub-PR ≤ 800 LOC).
 *
 * The orchestrator takes the dispatch as a single lambda
 * (`pushOne: suspend (SyncMetadataEntity) -> Unit`); SyncService still
 * holds pushCreate/pushUpdate/pushDelete and routes the lambda through
 * a tiny `when (meta.pendingAction)`. That gives us a clean
 * sequence/sort/log/retry boundary without dragging the dispatch into
 * this PR.
 *
 * Push-order priority is read from [SyncDispatchTables.pushOrderPriorityOf]
 * (extracted in 7e) — projects first (parent FK), then tags, then
 * everything else, with task_completions last because they reference
 * task cloud IDs.
 */
@Singleton
class SyncPushOrchestrator
@Inject
constructor(
    private val syncMetadataDao: SyncMetadataDao,
    private val logger: PrismSyncLogger
) {
    /**
     * Push every pending SyncMetadata row through [pushOne]. Returns the
     * count of processed entries (success + failure) so callers can
     * populate the "pushed=N" sync-completion telemetry.
     */
    suspend fun pushAllPending(
        pushOne: suspend (SyncMetadataEntity) -> Unit
    ): Int {
        val pending = syncMetadataDao.getPendingActions()
        val ordered = pending.sortedBy { SyncDispatchTables.pushOrderPriorityOf(it.entityType) }

        var successCount = 0
        var failureCount = 0
        for (meta in ordered) {
            val start = System.currentTimeMillis()
            try {
                pushOne(meta)
                syncMetadataDao.clearPendingAction(meta.localId, meta.entityType)
                successCount++
                logger.debug(
                    operation = "push.${meta.pendingAction}",
                    entity = meta.entityType,
                    id = meta.localId.toString(),
                    status = "success",
                    durationMs = System.currentTimeMillis() - start
                )
            } catch (e: Exception) {
                failureCount++
                logger.error(
                    operation = "push.${meta.pendingAction ?: "unknown"}",
                    entity = meta.entityType,
                    id = meta.localId.toString(),
                    durationMs = System.currentTimeMillis() - start,
                    detail = "retry=${meta.retryCount}",
                    throwable = e
                )
                try {
                    com.google.firebase.crashlytics.FirebaseCrashlytics
                        .getInstance()
                        .recordException(e)
                } catch (_: Exception) {
                    // crashlytics unavailable in tests — swallow
                }
                syncMetadataDao.incrementRetry(meta.localId, meta.entityType)
            }
        }
        if (ordered.isNotEmpty()) {
            logger.info(
                operation = "push.summary",
                status = if (failureCount == 0) "success" else "partial",
                detail = "success=$successCount failed=$failureCount"
            )
        }
        return successCount + failureCount
    }
}
