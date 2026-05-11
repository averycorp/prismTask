package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.TaskTimingDao
import com.averycorp.prismtask.data.local.entity.TaskTimingEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository wrapper around `TaskTimingDao`. Sync tracking is intentionally
 * NOT wired here — cross-device sync of `task_timings` is a follow-up
 * (P2-D, see `docs/audits/ANALYTICS_C4_C5_TIME_TRACKING_DESIGN.md`).
 */
@Singleton
class TaskTimingRepository @Inject constructor(private val taskTimingDao: TaskTimingDao) {
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
        return taskTimingDao.insert(entity)
    }

    suspend fun update(timing: TaskTimingEntity) = taskTimingDao.update(timing)

    suspend fun deleteById(id: Long) = taskTimingDao.deleteById(id)

    suspend fun deleteByTaskId(taskId: Long) = taskTimingDao.deleteByTaskId(taskId)

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
}
