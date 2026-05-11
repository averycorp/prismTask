package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.TaskDependencyDao
import com.averycorp.prismtask.data.local.entity.TaskDependencyEntity
import com.averycorp.prismtask.data.remote.SyncTracker
import com.averycorp.prismtask.domain.usecase.DependencyCycleGuard
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for the `task_dependencies` graph (PrismTask-timeline-class
 * scope, PR-2).
 *
 * The write path enforces the no-cycles invariant via
 * [DependencyCycleGuard]; the storage layer's unique
 * `(blocker, blocked)` index handles the no-duplicate-edges invariant.
 */
@Singleton
class TaskDependencyRepository
@Inject
constructor(private val taskDependencyDao: TaskDependencyDao, private val syncTracker: SyncTracker) {

    fun observeBlockersOf(taskId: Long): Flow<List<TaskDependencyEntity>> =
        taskDependencyDao.observeBlockersOf(taskId)

    suspend fun getBlockersOf(taskId: Long): List<TaskDependencyEntity> =
        taskDependencyDao.getBlockersOfOnce(taskId)

    suspend fun getBlockedBy(taskId: Long): List<TaskDependencyEntity> =
        taskDependencyDao.getBlockedByOnce(taskId)

    /** Snapshot of every edge in the table — caller is expected to filter. */
    suspend fun getAllOnce(): List<TaskDependencyEntity> =
        taskDependencyDao.getAllOnce()

    /**
     * Adds an edge from [blockerTaskId] to [blockedTaskId].
     *
     * Returns the new edge's local id, or:
     *  * [Result.failure] with [DependencyError.CycleRejected] if the
     *    edge would close a cycle.
     *  * [Result.success] of the existing row id if the edge already
     *    exists (idempotent — the unique index would have rejected the
     *    insert anyway).
     */
    suspend fun addDependency(
        blockerTaskId: Long,
        blockedTaskId: Long
    ): Result<Long> {
        val existing = taskDependencyDao.findEdgeIdOnce(blockerTaskId, blockedTaskId)
        if (existing != null) return Result.success(existing)

        val edges = taskDependencyDao.getAllOnce()
        if (DependencyCycleGuard.wouldCreateCycle(edges, blockerTaskId, blockedTaskId)) {
            return Result.failure(DependencyError.CycleRejected(blockerTaskId, blockedTaskId))
        }

        val now = System.currentTimeMillis()
        val edge = TaskDependencyEntity(
            blockerTaskId = blockerTaskId,
            blockedTaskId = blockedTaskId,
            createdAt = now
        )
        val id = taskDependencyDao.insert(edge)
        if (id <= 0) {
            // OnConflictStrategy.IGNORE swallowed the insert. Re-read
            // the existing row to give the caller the canonical id.
            return Result.success(
                taskDependencyDao.findEdgeIdOnce(blockerTaskId, blockedTaskId)
                    ?: return Result.failure(DependencyError.WriteFailed)
            )
        }
        syncTracker.trackCreate(id, "task_dependency")
        return Result.success(id)
    }

    suspend fun removeDependency(blockerTaskId: Long, blockedTaskId: Long) {
        val id = taskDependencyDao.findEdgeIdOnce(blockerTaskId, blockedTaskId) ?: return
        syncTracker.trackDelete(id, "task_dependency")
        taskDependencyDao.deleteEdge(blockerTaskId, blockedTaskId)
    }

    suspend fun removeById(id: Long) {
        syncTracker.trackDelete(id, "task_dependency")
        taskDependencyDao.deleteById(id)
    }

    /** Domain-level errors surfaced from [addDependency]. */
    sealed class DependencyError(message: String) : Throwable(message) {
        class CycleRejected(val blockerTaskId: Long, val blockedTaskId: Long) :
            DependencyError("edge ($blockerTaskId, $blockedTaskId) would close a cycle")

        object WriteFailed : DependencyError("dependency insert returned no rowid")
    }
}
