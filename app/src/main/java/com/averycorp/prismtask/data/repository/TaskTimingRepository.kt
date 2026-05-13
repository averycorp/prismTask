package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.TaskTimingDao
import com.averycorp.prismtask.data.local.entity.TaskTimingEntity
import com.averycorp.prismtask.data.remote.SyncTracker
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskTimingRepository @Inject constructor(
    private val taskTimingDao: TaskTimingDao,
    private val syncTracker: SyncTracker
) {
    suspend fun logTime(
        taskId: Long,
        durationMinutes: Int,
        source: String = TaskTimingEntity.SOURCE_MANUAL,
        startedAt: Long? = null,
        endedAt: Long? = null,
        notes: String? = null
    ): Long {
        require(durationMinutes > 0) { "durationMinutes must be > 0 (got $durationMinutes)" }
        val entity = TaskTimingEntity(
            taskId = taskId,
            startedAt = startedAt,
            endedAt = endedAt,
            durationMinutes = durationMinutes,
            source = source,
            notes = notes
        )
        val id = taskTimingDao.insert(entity)
        syncTracker.trackCreate(id, ENTITY_TYPE)
        return id
    }

    suspend fun update(timing: TaskTimingEntity) {
        taskTimingDao.update(timing)
        syncTracker.trackUpdate(timing.id, ENTITY_TYPE)
    }

    suspend fun deleteById(id: Long) {
        taskTimingDao.deleteById(id)
        syncTracker.trackDelete(id, ENTITY_TYPE)
    }

    suspend fun deleteByTaskId(taskId: Long) {
        val ids = taskTimingDao.getTimingsForTaskOnce(taskId).map { it.id }
        taskTimingDao.deleteByTaskId(taskId)
        ids.forEach { syncTracker.trackDelete(it, ENTITY_TYPE) }
    }

    fun getTimingsForTask(taskId: Long): Flow<List<TaskTimingEntity>> =
        taskTimingDao.getTimingsForTask(taskId)

    suspend fun getTimingsForTaskOnce(taskId: Long): List<TaskTimingEntity> =
        taskTimingDao.getTimingsForTaskOnce(taskId)

    fun getTimingsInRange(startMillis: Long, endMillis: Long): Flow<List<TaskTimingEntity>> =
        taskTimingDao.getTimingsInRange(startMillis, endMillis)

    suspend fun sumMinutesForTask(taskId: Long): Int =
        taskTimingDao.sumMinutesForTask(taskId)

    fun observeSumMinutesForTask(taskId: Long): Flow<Int> =
        taskTimingDao.observeSumMinutesForTask(taskId)

    companion object {
        private const val ENTITY_TYPE = "task_timing"
    }
}
