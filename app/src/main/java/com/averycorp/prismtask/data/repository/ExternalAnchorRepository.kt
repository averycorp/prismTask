package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.ExternalAnchorDao
import com.averycorp.prismtask.data.local.entity.ExternalAnchorEntity
import com.averycorp.prismtask.data.remote.SyncTracker
import com.averycorp.prismtask.data.remote.adapter.ExternalAnchorJsonAdapter
import com.averycorp.prismtask.domain.model.ExternalAnchor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for the polymorphic external-anchor surface.
 *
 * Reads return a typed `(entity, decodedAnchor)` pair so callers don't
 * have to re-decode the JSON every render. Decoded anchors with
 * unrecognized payloads (forward-compat) come back as `null` and are
 * surfaced in the UI as "unsupported anchor type" rather than dropped.
 */
@Singleton
class ExternalAnchorRepository
@Inject
constructor(private val externalAnchorDao: ExternalAnchorDao, private val syncTracker: SyncTracker) {
    data class Decoded(val entity: ExternalAnchorEntity, val anchor: ExternalAnchor?)

    fun observeAnchors(projectId: Long): Flow<List<Decoded>> =
        externalAnchorDao.observeAnchors(projectId).map { rows ->
            rows.map { Decoded(it, ExternalAnchorJsonAdapter.decode(it.anchorJson)) }
        }

    suspend fun getAnchorsOnce(projectId: Long): List<Decoded> =
        externalAnchorDao.getAnchorsOnce(projectId).map {
            Decoded(it, ExternalAnchorJsonAdapter.decode(it.anchorJson))
        }

    suspend fun addAnchor(
        projectId: Long,
        label: String,
        anchor: ExternalAnchor,
        phaseId: Long? = null
    ): Long {
        val now = System.currentTimeMillis()
        val row = ExternalAnchorEntity(
            projectId = projectId,
            phaseId = phaseId,
            label = label,
            anchorJson = ExternalAnchorJsonAdapter.encode(anchor),
            createdAt = now,
            updatedAt = now
        )
        val id = externalAnchorDao.insert(row)
        syncTracker.trackCreate(id, "external_anchor")
        return id
    }

    suspend fun updateAnchor(
        existing: ExternalAnchorEntity,
        label: String = existing.label,
        anchor: ExternalAnchor? = null,
        phaseId: Long? = existing.phaseId
    ) {
        val updated = existing.copy(
            label = label,
            anchorJson = anchor?.let { ExternalAnchorJsonAdapter.encode(it) } ?: existing.anchorJson,
            phaseId = phaseId,
            updatedAt = System.currentTimeMillis()
        )
        externalAnchorDao.update(updated)
        syncTracker.trackUpdate(updated.id, "external_anchor")
    }

    suspend fun deleteAnchor(anchor: ExternalAnchorEntity) {
        syncTracker.trackDelete(anchor.id, "external_anchor")
        externalAnchorDao.delete(anchor)
    }
}
