package com.averycorp.prismtask.data.remote.sync

import com.averycorp.prismtask.data.local.dao.LeisureActivityDao
import com.averycorp.prismtask.data.local.dao.SyncMetadataDao
import com.averycorp.prismtask.data.local.entity.LeisureActivityEntity
import com.averycorp.prismtask.data.local.entity.SyncMetadataEntity
import com.averycorp.prismtask.data.remote.api.LeisureActivityCreateRequest
import com.averycorp.prismtask.data.remote.api.LeisureActivityRemoteResponse
import com.averycorp.prismtask.data.remote.api.LeisureActivityUpdateRequest
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bidirectional sync for the user's leisure-activity pool. The backend
 * exposes dedicated `/api/v1/leisure/activities` CRUD endpoints
 * (`backend/app/routers/leisure.py`) rather than going through the
 * generic `/sync/push` ENTITY_MAP, so activities can't ride the
 * [BackendSyncService] pipeline. This service is invoked from
 * [BackendSyncService.fullSync] after the generic pull completes so
 * the user's activity pool follows them across devices.
 *
 * Identity strategy mirrors the medication tier-state pattern:
 * Android's local autoincrement `id: Long` is the local primary key,
 * and a UUID-shaped `cloud_id: String` (the backend's primary key) is
 * the cross-system identifier. We mint the cloud_id on the first push
 * for a row, then echo it back into both [LeisureActivityEntity.cloudId]
 * and the row's [SyncMetadataEntity] so subsequent updates can address
 * it.
 *
 * Pending actions are produced by [com.averycorp.prismtask.data.remote.SyncTracker]
 * inside [com.averycorp.prismtask.data.repository.LeisureBudgetRepository]
 * — every `upsertActivity` / `deleteActivity` / `setActivityEnabled`
 * call already queues the appropriate metadata row.
 */
@Singleton
class LeisureSyncService
@Inject
constructor(
    private val api: PrismTaskApi,
    private val activityDao: LeisureActivityDao,
    private val syncMetadataDao: SyncMetadataDao,
    private val logger: PrismSyncLogger
) {
    companion object {
        const val ENTITY_TYPE = "leisure_activity"
    }

    /**
     * Run a full activity sync: push pending local changes, then pull
     * the canonical list from the backend and reconcile. Returns the
     * total number of rows pushed + applied.
     */
    suspend fun sync(): Int {
        val pushed = pushPending()
        val pulled = pullAndReconcile()
        return pushed + pulled
    }

    /**
     * Walk every pending sync-metadata row for `leisure_activity` and
     * apply it to the backend. Mints a cloud_id on first push so the
     * server-side primary key is stable across re-runs.
     */
    private suspend fun pushPending(): Int {
        val pending = syncMetadataDao.getPendingActions()
            .filter { it.entityType == ENTITY_TYPE }
        if (pending.isEmpty()) return 0
        var ops = 0
        for (meta in pending) {
            try {
                when (meta.pendingAction) {
                    "create" -> ops += pushCreate(meta)
                    "update" -> ops += pushUpdate(meta)
                    "delete" -> ops += pushDelete(meta)
                }
            } catch (e: Exception) {
                logger.error(
                    operation = "push.leisure_activity",
                    entity = ENTITY_TYPE,
                    id = meta.localId.toString(),
                    detail = "action=${meta.pendingAction}",
                    throwable = e
                )
                syncMetadataDao.incrementRetry(meta.localId, ENTITY_TYPE)
            }
        }
        return ops
    }

    private suspend fun pushCreate(meta: SyncMetadataEntity): Int {
        val activity = activityDao.getById(meta.localId) ?: run {
            // Row was deleted locally before we got around to creating
            // it remotely — drop the pending metadata.
            syncMetadataDao.delete(meta.localId, ENTITY_TYPE)
            return 0
        }
        val cloudId = activity.cloudId?.takeIf { it.isNotBlank() }
            ?: meta.cloudId.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        api.createLeisureActivity(
            LeisureActivityCreateRequest(
                id = cloudId,
                name = activity.name,
                category = activity.category,
                defaultDurationMinutes = activity.defaultDurationMinutes,
                enabled = activity.enabled
            )
        )
        if (activity.cloudId != cloudId) {
            activityDao.setCloudId(activity.id, cloudId)
        }
        syncMetadataDao.upsert(
            meta.copy(
                cloudId = cloudId,
                pendingAction = null,
                lastSyncedAt = System.currentTimeMillis(),
                retryCount = 0
            )
        )
        logger.info(
            operation = "push.leisure_activity",
            entity = ENTITY_TYPE,
            id = meta.localId.toString(),
            status = "created",
            detail = "cloud_id=$cloudId"
        )
        return 1
    }

    private suspend fun pushUpdate(meta: SyncMetadataEntity): Int {
        val activity = activityDao.getById(meta.localId) ?: run {
            syncMetadataDao.delete(meta.localId, ENTITY_TYPE)
            return 0
        }
        val cloudId = activity.cloudId?.takeIf { it.isNotBlank() }
            ?: meta.cloudId.takeIf { it.isNotBlank() }
        if (cloudId == null) {
            // Update on a row that never made it to the cloud — promote
            // to create so we don't 404 the backend.
            return pushCreate(meta.copy(pendingAction = "create"))
        }
        api.updateLeisureActivity(
            activityId = cloudId,
            body = LeisureActivityUpdateRequest(
                name = activity.name,
                category = activity.category,
                defaultDurationMinutes = activity.defaultDurationMinutes,
                enabled = activity.enabled
            )
        )
        syncMetadataDao.upsert(
            meta.copy(
                cloudId = cloudId,
                pendingAction = null,
                lastSyncedAt = System.currentTimeMillis(),
                retryCount = 0
            )
        )
        logger.info(
            operation = "push.leisure_activity",
            entity = ENTITY_TYPE,
            id = meta.localId.toString(),
            status = "updated",
            detail = "cloud_id=$cloudId"
        )
        return 1
    }

    private suspend fun pushDelete(meta: SyncMetadataEntity): Int {
        val cloudId = meta.cloudId.takeIf { it.isNotBlank() }
        if (cloudId != null) {
            val response = api.deleteLeisureActivity(cloudId)
            // 204 success or 404 (already gone) both leave us in the
            // intended state. Anything else throws via Retrofit.
            if (!response.isSuccessful && response.code() != 404) {
                error("deleteLeisureActivity returned ${response.code()}")
            }
        }
        syncMetadataDao.delete(meta.localId, ENTITY_TYPE)
        logger.info(
            operation = "push.leisure_activity",
            entity = ENTITY_TYPE,
            id = meta.localId.toString(),
            status = "deleted",
            detail = "cloud_id=${cloudId ?: "(none)"}"
        )
        return 1
    }

    /**
     * Pull the canonical activity list from the backend and merge into
     * Room. Last-write-wins by `updated_at`. Local rows whose cloud_id
     * isn't in the remote list are treated as remote-side deletes —
     * unless they have a pending local action that hasn't pushed yet.
     */
    private suspend fun pullAndReconcile(): Int {
        val remote = api.listLeisureActivities(enabledOnly = false)
        var applied = 0
        val remoteCloudIds = HashSet<String>(remote.size)
        for (row in remote) {
            remoteCloudIds += row.id
            applied += applyRemote(row)
        }
        applied += reconcileLocalOrphans(remoteCloudIds)
        logger.debug(
            operation = "pull.leisure_activity",
            entity = ENTITY_TYPE,
            status = "success",
            detail = "remote=${remote.size} applied=$applied"
        )
        return applied
    }

    private suspend fun applyRemote(row: LeisureActivityRemoteResponse): Int {
        val remoteUpdated = isoToMillisOrNull(row.updatedAt) ?: return 0
        val remoteCreated = isoToMillisOrNull(row.createdAt) ?: remoteUpdated
        val remoteLastCompleted = row.lastCompletedAt?.let(::isoToMillisOrNull)
        val existing = activityDao.getByCloudIdOnce(row.id)
        if (existing == null) {
            val localId = activityDao.insert(
                LeisureActivityEntity(
                    id = 0,
                    cloudId = row.id,
                    name = row.name,
                    category = row.category,
                    defaultDurationMinutes = row.defaultDurationMinutes,
                    enabled = row.enabled,
                    createdAt = remoteCreated,
                    updatedAt = remoteUpdated,
                    lastCompletedAt = remoteLastCompleted
                )
            )
            syncMetadataDao.upsert(
                SyncMetadataEntity(
                    localId = localId,
                    entityType = ENTITY_TYPE,
                    cloudId = row.id,
                    lastSyncedAt = System.currentTimeMillis(),
                    pendingAction = null
                )
            )
            return 1
        }
        // Last-write-wins: skip if the local row is at least as new.
        if (existing.updatedAt >= remoteUpdated) return 0
        activityDao.update(
            existing.copy(
                name = row.name,
                category = row.category,
                defaultDurationMinutes = row.defaultDurationMinutes,
                enabled = row.enabled,
                updatedAt = remoteUpdated,
                lastCompletedAt = remoteLastCompleted ?: existing.lastCompletedAt
            )
        )
        // Refresh metadata so the next push doesn't re-emit a stale update.
        syncMetadataDao.get(existing.id, ENTITY_TYPE)?.let { meta ->
            syncMetadataDao.upsert(
                meta.copy(
                    cloudId = row.id,
                    lastSyncedAt = System.currentTimeMillis(),
                    pendingAction = null
                )
            )
        }
        return 1
    }

    /**
     * Delete any local row that was previously synced (has a cloud_id)
     * but no longer appears in the remote list — another device deleted
     * it. Rows with a pending local action are preserved so we don't
     * clobber an in-flight create/update.
     */
    private suspend fun reconcileLocalOrphans(remoteCloudIds: Set<String>): Int {
        val locals = activityDao.getAllOnce()
        var deleted = 0
        for (activity in locals) {
            val cloudId = activity.cloudId?.takeIf { it.isNotBlank() } ?: continue
            if (cloudId in remoteCloudIds) continue
            val meta = syncMetadataDao.get(activity.id, ENTITY_TYPE)
            if (meta?.pendingAction != null) continue
            activityDao.deleteById(activity.id)
            syncMetadataDao.delete(activity.id, ENTITY_TYPE)
            deleted++
            logger.info(
                operation = "pull.leisure_activity",
                entity = ENTITY_TYPE,
                id = activity.id.toString(),
                status = "deleted_remote_orphan",
                detail = "cloud_id=$cloudId"
            )
        }
        return deleted
    }
}
