package com.averycorp.prismtask.data.remote.sync

import com.averycorp.prismtask.data.local.dao.LeisureActivityDao
import com.averycorp.prismtask.data.local.dao.LeisureSessionDao
import com.averycorp.prismtask.data.local.dao.SyncMetadataDao
import com.averycorp.prismtask.data.local.entity.LeisureSessionEntity
import com.averycorp.prismtask.data.local.entity.SyncMetadataEntity
import com.averycorp.prismtask.data.remote.api.LeisureSessionCreateRequest
import com.averycorp.prismtask.data.remote.api.LeisureSessionRemoteResponse
import com.averycorp.prismtask.data.remote.api.LeisureSessionUpdateRequest
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bidirectional sync for the user's leisure-session history. Mirrors
 * [LeisureSyncService]'s activity-pool sync: dedicated REST endpoints
 * (`/api/v1/leisure/sessions`), local cloud_id minted on first push,
 * orphan reconciliation against the pulled window.
 *
 * Two wrinkles unique to sessions vs activities:
 *
 *  • [LeisureSessionEntity.activityId] is a *local* `Long` FK while the
 *    backend stores `activity_id` as the activity's *cloud_id* (String).
 *    Push translates Long → String via [LeisureActivityDao.getById];
 *    pull translates String → Long via [LeisureActivityDao.getByCloudIdOnce].
 *    Both sides null out the FK gracefully if the parent activity isn't
 *    around (rare race; the FK on the Room entity is `SET_NULL`).
 *    Because activities must round-trip first so the cloud_id exists,
 *    [BackendSyncService.fullSync] runs [LeisureSyncService.sync] before
 *    this service.
 *
 *  • Sessions don't carry an `updated_at` column (mostly immutable post
 *    insert — only [com.averycorp.prismtask.data.repository.LeisureBudgetRepository.updateSessionTime]
 *    mutates `logged_at`). Pull therefore overwrites local fields from
 *    remote unconditionally when cloud_ids match, rather than running a
 *    last-write-wins comparison — remote is server-authoritative.
 *
 * Orphan reconciliation only considers local rows whose `loggedAt` is
 * within the window the pull covered (≥ the oldest remote row's
 * `loggedAt`). The list endpoint paginates (default 200, max 1000); for
 * very heavy users older sessions stay locally even if deleted on
 * another device — an acceptable trade-off to avoid mass-deleting
 * history when the pull window doesn't cover it.
 */
@Singleton
class LeisureSessionSyncService
@Inject
constructor(
    private val api: PrismTaskApi,
    private val sessionDao: LeisureSessionDao,
    private val activityDao: LeisureActivityDao,
    private val syncMetadataDao: SyncMetadataDao,
    private val logger: PrismSyncLogger
) {
    companion object {
        const val ENTITY_TYPE = "leisure_session"

        // Backend caps `limit` at 1000; pull the largest window we can to
        // make orphan reconciliation reliable for typical users.
        private const val PULL_LIMIT = 1000
    }

    suspend fun sync(): Int {
        val justPushed = mutableSetOf<Long>()
        val pushed = pushPending(justPushed)
        val pulled = pullAndReconcile(justPushed)
        return pushed + pulled
    }

    private suspend fun pushPending(justPushed: MutableSet<Long>): Int {
        val pending = syncMetadataDao.getPendingActions()
            .filter { it.entityType == ENTITY_TYPE }
        if (pending.isEmpty()) return 0
        var ops = 0
        for (meta in pending) {
            try {
                val applied = when (meta.pendingAction) {
                    "create" -> pushCreate(meta)
                    "update" -> pushUpdate(meta)
                    "delete" -> pushDelete(meta)
                    else -> 0
                }
                if (applied > 0) {
                    ops += applied
                    if (meta.pendingAction != "delete") {
                        justPushed += meta.localId
                    }
                }
            } catch (e: Exception) {
                logger.error(
                    operation = "push.leisure_session",
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
        val session = sessionDao.getById(meta.localId) ?: run {
            syncMetadataDao.delete(meta.localId, ENTITY_TYPE)
            return 0
        }
        val cloudId = session.cloudId?.takeIf { it.isNotBlank() }
            ?: meta.cloudId.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        api.createLeisureSession(
            LeisureSessionCreateRequest(
                id = cloudId,
                activityId = resolveActivityCloudId(session.activityId),
                category = session.category,
                durationMinutes = session.durationMinutes,
                loggedAt = millisToIso(session.loggedAt),
                source = session.source
            )
        )
        if (session.cloudId != cloudId) {
            sessionDao.setCloudId(session.id, cloudId)
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
            operation = "push.leisure_session",
            entity = ENTITY_TYPE,
            id = meta.localId.toString(),
            status = "created",
            detail = "cloud_id=$cloudId"
        )
        return 1
    }

    private suspend fun pushUpdate(meta: SyncMetadataEntity): Int {
        val session = sessionDao.getById(meta.localId) ?: run {
            syncMetadataDao.delete(meta.localId, ENTITY_TYPE)
            return 0
        }
        val cloudId = session.cloudId?.takeIf { it.isNotBlank() }
            ?: meta.cloudId.takeIf { it.isNotBlank() }
        if (cloudId == null) {
            return pushCreate(meta.copy(pendingAction = "create"))
        }
        api.updateLeisureSession(
            sessionId = cloudId,
            body = LeisureSessionUpdateRequest(
                loggedAt = millisToIso(session.loggedAt)
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
            operation = "push.leisure_session",
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
            val response = api.deleteLeisureSession(cloudId)
            if (!response.isSuccessful && response.code() != 404) {
                error("deleteLeisureSession returned ${response.code()}")
            }
        }
        syncMetadataDao.delete(meta.localId, ENTITY_TYPE)
        logger.info(
            operation = "push.leisure_session",
            entity = ENTITY_TYPE,
            id = meta.localId.toString(),
            status = "deleted",
            detail = "cloud_id=${cloudId ?: "(none)"}"
        )
        return 1
    }

    private suspend fun pullAndReconcile(justPushed: Set<Long>): Int {
        val remote = api.listLeisureSessions(limit = PULL_LIMIT)
        var applied = 0
        val remoteCloudIds = HashSet<String>(remote.size)
        var oldestRemoteLoggedAt: Long = Long.MAX_VALUE
        for (row in remote) {
            remoteCloudIds += row.id
            val loggedAtMillis = isoToMillisOrNull(row.loggedAt)
            if (loggedAtMillis != null && loggedAtMillis < oldestRemoteLoggedAt) {
                oldestRemoteLoggedAt = loggedAtMillis
            }
            applied += applyRemote(row)
        }
        // If the remote list filled the page (`remote.size == PULL_LIMIT`)
        // assume older sessions exist beyond the window; only reconcile
        // local orphans newer than the oldest remote row we actually saw.
        // Otherwise the full history fits in one page and we can
        // reconcile against everything (window = 0..now).
        val reconcileFromMillis = if (remote.size >= PULL_LIMIT) {
            oldestRemoteLoggedAt
        } else {
            0L
        }
        applied += reconcileLocalOrphans(remoteCloudIds, justPushed, reconcileFromMillis)
        logger.debug(
            operation = "pull.leisure_session",
            entity = ENTITY_TYPE,
            status = "success",
            detail = "remote=${remote.size} applied=$applied"
        )
        return applied
    }

    private suspend fun applyRemote(row: LeisureSessionRemoteResponse): Int {
        val loggedAtMillis = isoToMillisOrNull(row.loggedAt) ?: return 0
        val createdAtMillis = isoToMillisOrNull(row.createdAt) ?: loggedAtMillis
        val localActivityId = resolveLocalActivityId(row.activityId)
        val existing = sessionDao.getByCloudIdOnce(row.id)
        if (existing == null) {
            val localId = sessionDao.insert(
                LeisureSessionEntity(
                    id = 0,
                    cloudId = row.id,
                    activityId = localActivityId,
                    category = row.category,
                    durationMinutes = row.durationMinutes,
                    loggedAt = loggedAtMillis,
                    source = row.source,
                    createdAt = createdAtMillis
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
        // Sessions are mostly immutable, but updateSessionTime can move
        // logged_at after the fact. Skip the write when nothing changed
        // so we don't churn Room or bump cross-device cache invalidation.
        val unchanged = existing.activityId == localActivityId &&
            existing.category == row.category &&
            existing.durationMinutes == row.durationMinutes &&
            existing.loggedAt == loggedAtMillis &&
            existing.source == row.source
        if (unchanged) return 0
        sessionDao.update(
            existing.copy(
                activityId = localActivityId,
                category = row.category,
                durationMinutes = row.durationMinutes,
                loggedAt = loggedAtMillis,
                source = row.source
            )
        )
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

    private suspend fun reconcileLocalOrphans(
        remoteCloudIds: Set<String>,
        justPushed: Set<Long>,
        fromMillis: Long
    ): Int {
        val locals = sessionDao.getAllOnce()
        var deleted = 0
        for (session in locals) {
            val cloudId = session.cloudId?.takeIf { it.isNotBlank() } ?: continue
            if (cloudId in remoteCloudIds) continue
            if (session.id in justPushed) continue
            // Don't false-delete sessions outside the pulled window —
            // we never confirmed they're missing from the server.
            if (session.loggedAt < fromMillis) continue
            val meta = syncMetadataDao.get(session.id, ENTITY_TYPE)
            if (meta?.pendingAction != null) continue
            sessionDao.deleteById(session.id)
            syncMetadataDao.delete(session.id, ENTITY_TYPE)
            deleted++
            logger.info(
                operation = "pull.leisure_session",
                entity = ENTITY_TYPE,
                id = session.id.toString(),
                status = "deleted_remote_orphan",
                detail = "cloud_id=$cloudId"
            )
        }
        return deleted
    }

    private suspend fun resolveActivityCloudId(localActivityId: Long?): String? {
        if (localActivityId == null) return null
        val activity = activityDao.getById(localActivityId) ?: return null
        return activity.cloudId?.takeIf { it.isNotBlank() }
    }

    private suspend fun resolveLocalActivityId(remoteActivityId: String?): Long? {
        if (remoteActivityId.isNullOrBlank()) return null
        return activityDao.getByCloudIdOnce(remoteActivityId)?.id
    }
}
