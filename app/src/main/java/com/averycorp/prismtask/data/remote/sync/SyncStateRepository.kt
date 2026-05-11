package com.averycorp.prismtask.data.remote.sync

import com.averycorp.prismtask.data.local.dao.SyncMetadataDao
import com.averycorp.prismtask.data.local.entity.SyncMetadataEntity
import com.averycorp.prismtask.data.preferences.BackendSyncPreferences
import com.averycorp.prismtask.data.remote.AuthManager
import com.averycorp.prismtask.utils.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for observable sync state. Collects state from both
 * sync services ([com.averycorp.prismtask.data.remote.SyncService] and
 * [BackendSyncService]), plus ambient signals (network, auth, pending queue),
 * and exposes them as flows for the sync indicator, details sheet, and debug
 * panel.
 *
 * Services call [markSyncStarted] / [markSyncCompleted] / [pushError] /
 * [recordListenerSnapshot] to report activity. No service owns its own
 * observable state — that lives here so UI code has one dependency.
 */
@Singleton
class SyncStateRepository
@Inject
constructor(
    authManager: AuthManager,
    private val syncMetadataDao: SyncMetadataDao,
    backendSyncPreferences: BackendSyncPreferences,
    networkMonitor: NetworkMonitor,
    private val logger: PrismSyncLogger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _activeSources = MutableStateFlow<Set<String>>(emptySet())
    val activeSources: StateFlow<Set<String>> = _activeSources.asStateFlow()
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastSuccessAt = MutableStateFlow<Long?>(null)
    val lastSuccessAt: StateFlow<Long?> = _lastSuccessAt.asStateFlow()

    private val _recentErrors = MutableStateFlow<List<SyncErrorSample>>(emptyList())
    val recentErrors: StateFlow<List<SyncErrorSample>> = _recentErrors.asStateFlow()

    /**
     * Timestamp of the most recent [markSyncCompleted] that surfaced
     * permanent skips (`permanentlyFailed > 0`). Null until the first
     * such event lands or after [clearErrors] runs.
     *
     * P0 sync audit PR-D. Powers a user-visible "Sync completed with
     * data loss" indicator: a successful sync that nevertheless dropped
     * a doc due to SQLiteConstraintException is more important to flag
     * than a routine transient skip, but less alarming than a sync that
     * outright threw — so it gets its own surface independent of
     * [lastSuccessAt] / [recentErrors].
     */
    private val _lastDataLossAt = MutableStateFlow<Long?>(null)
    val lastDataLossAt: StateFlow<Long?> = _lastDataLossAt.asStateFlow()

    private val _listenerSnapshots = MutableStateFlow<Map<String, Long>>(emptyMap())
    val listenerSnapshots: StateFlow<Map<String, Long>> = _listenerSnapshots.asStateFlow()

    private val _listenersActive = MutableStateFlow(false)
    val listenersActive: StateFlow<Boolean> = _listenersActive.asStateFlow()

    val pendingCount: StateFlow<Int> = syncMetadataDao
        .getPendingCount()
        .stateIn(scope, SharingStarted.Eagerly, 0)

    val pendingEntries: Flow<List<SyncMetadataEntity>> = syncMetadataDao.observePending()

    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    val isSignedIn: StateFlow<Boolean> = authManager.isSignedIn

    val lastSyncAt: StateFlow<Long> = backendSyncPreferences.lastSyncAtFlow
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    fun markSyncStarted(source: String, trigger: String? = null) {
        _activeSources.update { it + source }
        _isSyncing.value = _activeSources.value.isNotEmpty()
        logger.info(
            operation = "sync.started",
            entity = "service",
            id = source,
            status = "running",
            detail = trigger?.let { "trigger=$it" }
        )
    }

    /**
     * @param permanentlyFailed Number of pull-side docs that threw during
     * apply (e.g. `SQLiteConstraintException`). Non-zero means the sync
     * "succeeded" overall but silently dropped data — bump
     * [lastDataLossAt] and emit `sync.completed | status=success_with_data_loss`
     * so dashboards and the in-app indicator can surface the partial
     * failure without flipping the whole sync to a hard error. P0 sync
     * audit PR-D.
     */
    fun markSyncCompleted(
        source: String,
        success: Boolean,
        durationMs: Long? = null,
        pushed: Int? = null,
        pulled: Int? = null,
        permanentlyFailed: Int = 0,
        errorMessage: String? = null,
        throwable: Throwable? = null
    ) {
        _activeSources.update { it - source }
        _isSyncing.value = _activeSources.value.isNotEmpty()
        if (success) {
            _lastSuccessAt.value = System.currentTimeMillis()
            val detail = listOfNotNull(
                pushed?.let { "pushed=$it" },
                pulled?.let { "pulled=$it" },
                permanentlyFailed.takeIf { it > 0 }?.let { "permanently_failed=$it" }
            ).ifEmpty { null }?.joinToString(" ")
            if (permanentlyFailed > 0) {
                _lastDataLossAt.value = System.currentTimeMillis()
                pushError(
                    source = source,
                    message = "$permanentlyFailed doc(s) dropped during pull (constraint violation)"
                )
                logger.warn(
                    operation = "sync.completed",
                    entity = "service",
                    id = source,
                    status = "success_with_data_loss",
                    durationMs = durationMs,
                    detail = detail
                )
            } else {
                logger.info(
                    operation = "sync.completed",
                    entity = "service",
                    id = source,
                    status = "success",
                    durationMs = durationMs,
                    detail = detail
                )
            }
        } else {
            val message = errorMessage ?: throwable?.message ?: "unknown"
            pushError(source = source, message = message)
            logger.error(
                operation = "sync.completed",
                entity = "service",
                id = source,
                status = "failed",
                durationMs = durationMs,
                detail = message,
                throwable = throwable
            )
        }
    }

    fun pushError(source: String, message: String) {
        val sample = SyncErrorSample(
            timestampMs = System.currentTimeMillis(),
            source = source,
            message = message
        )
        _recentErrors.update { (listOf(sample) + it).take(MAX_ERRORS) }
    }

    fun clearErrors() {
        _recentErrors.value = emptyList()
        _lastDataLossAt.value = null
    }

    fun markListenersActive(active: Boolean) {
        if (_listenersActive.value != active) {
            _listenersActive.value = active
            logger.debug(
                operation = "listeners",
                status = if (active) "active" else "stopped"
            )
        }
        if (!active) _listenerSnapshots.value = emptyMap()
    }

    fun recordListenerSnapshot(collection: String, changeCount: Int) {
        _listenerSnapshots.update { it + (collection to System.currentTimeMillis()) }
        logger.debug(
            operation = "listener.snapshot",
            entity = "collection",
            id = collection,
            status = "received",
            detail = "changes=$changeCount"
        )
    }

    suspend fun deadLettered(): List<SyncMetadataEntity> =
        syncMetadataDao.getDeadLettered()

    suspend fun clearOfflineQueue() {
        val pending = syncMetadataDao.getPendingActions()
        for (entry in pending) {
            syncMetadataDao.clearPendingAction(entry.localId, entry.entityType)
        }
        logger.warn(
            operation = "queue.clear",
            status = "cleared",
            detail = "entries=${pending.size}"
        )
    }

    suspend fun resetSyncState() {
        syncMetadataDao.deleteAll()
        _listenerSnapshots.value = emptyMap()
        _lastSuccessAt.value = null
        _recentErrors.value = emptyList()
        logger.warn(
            operation = "state.reset",
            status = "cleared",
            detail = "sync_metadata wiped"
        )
    }

    companion object {
        private const val MAX_ERRORS = 5
    }
}

/**
 * One recent sync error surfaced in the details sheet. Kept small — at most
 * [SyncStateRepository.MAX_ERRORS] in memory at a time.
 */
data class SyncErrorSample(val timestampMs: Long, val source: String, val message: String)
