package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.MoodEnergyLogDao
import com.averycorp.prismtask.data.local.entity.MoodEnergyLogEntity
import com.averycorp.prismtask.data.remote.SyncTracker
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository wrapping [MoodEnergyLogDao] for the v1.4.0 V7 mood + energy
 * tracking feature. Mostly straight-through — the only bit of logic is
 * [upsertForDate] which replaces an existing entry for the same
 * `(date, timeOfDay)` pair instead of inserting a duplicate.
 */
@Singleton
class MoodEnergyRepository
@Inject
constructor(private val dao: MoodEnergyLogDao, private val syncTracker: SyncTracker) {
    suspend fun upsertForDate(
        date: Long,
        mood: Int,
        energy: Int,
        notes: String? = null,
        timeOfDay: String = "morning"
    ): Long {
        val now = System.currentTimeMillis()
        val existing = dao.getByDate(date).firstOrNull { it.timeOfDay == timeOfDay }
        return if (existing != null) {
            val updated = existing.copy(
                mood = mood,
                energy = energy,
                notes = notes,
                updatedAt = now
            )
            dao.update(updated)
            syncTracker.trackUpdate(existing.id, "mood_energy_log")
            existing.id
        } else {
            val id = dao.insert(
                MoodEnergyLogEntity(
                    date = date,
                    mood = mood,
                    energy = energy,
                    notes = notes,
                    timeOfDay = timeOfDay,
                    updatedAt = now
                )
            )
            syncTracker.trackCreate(id, "mood_energy_log")
            id
        }
    }

    suspend fun getByDate(date: Long): List<MoodEnergyLogEntity> = dao.getByDate(date)

    fun observeRange(start: Long, end: Long): Flow<List<MoodEnergyLogEntity>> =
        dao.observeRange(start, end)

    suspend fun getRange(start: Long, end: Long): List<MoodEnergyLogEntity> =
        dao.getRange(start, end)

    suspend fun getAll(): List<MoodEnergyLogEntity> = dao.getAll()

    suspend fun delete(id: Long) {
        dao.delete(id)
        syncTracker.trackDelete(id, "mood_energy_log")
    }
}
