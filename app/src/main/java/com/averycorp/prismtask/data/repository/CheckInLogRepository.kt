package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.CheckInLogDao
import com.averycorp.prismtask.data.local.entity.CheckInLogEntity
import com.averycorp.prismtask.data.remote.SyncTracker
import com.averycorp.prismtask.domain.usecase.CheckInStep
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for morning check-in history (v1.4.0 V4).
 *
 * Keeps the runtime API in [CheckInStep] terms and hides the CSV
 * encoding of the `steps_completed_csv` column.
 */
@Singleton
class CheckInLogRepository
@Inject
constructor(private val dao: CheckInLogDao, private val syncTracker: SyncTracker) {
    suspend fun record(
        date: Long,
        stepsCompleted: List<CheckInStep>,
        medicationsConfirmed: Int = 0,
        tasksReviewed: Int = 0,
        habitsCompleted: Int = 0
    ): Long {
        val existing = dao.getByDate(date)
        val row = CheckInLogEntity(
            id = existing?.id ?: 0,
            date = date,
            stepsCompletedCsv = stepsCompleted.joinToString(",") { it.name },
            medicationsConfirmed = medicationsConfirmed,
            tasksReviewed = tasksReviewed,
            habitsCompleted = habitsCompleted,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            cloudId = existing?.cloudId,
            updatedAt = System.currentTimeMillis()
        )
        val id = dao.upsert(row)
        if (existing == null) {
            syncTracker.trackCreate(id, "check_in_log")
        } else {
            syncTracker.trackUpdate(id, "check_in_log")
        }
        return id
    }

    suspend fun getMostRecentDate(): Long? = dao.getMostRecent()?.date

    suspend fun getByDate(date: Long): CheckInLogEntity? = dao.getByDate(date)

    fun observeAll(): Flow<List<CheckInLogEntity>> = dao.observeAll()

    /**
     * Count consecutive days ending at [today] that have at least one
     * check-in row. This is the "7-day check-in streak 🔥" number.
     */
    suspend fun currentStreak(today: Long): Int {
        val logs = dao.getSince(today - 60L * 24 * 60 * 60 * 1000)
        if (logs.isEmpty()) return 0
        val dayMillis = 24L * 60 * 60 * 1000
        val dateSet = logs.map { it.date }.toHashSet()
        var streak = 0
        var cursor = today
        while (dateSet.contains(cursor)) {
            streak++
            cursor -= dayMillis
        }
        return streak
    }
}
