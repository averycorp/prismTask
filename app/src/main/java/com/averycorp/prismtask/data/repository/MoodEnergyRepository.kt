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
constructor(
    private val dao: MoodEnergyLogDao,
    private val syncTracker: SyncTracker
) {
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

    /**
     * Partial update used by surfaces that only capture energy (the
     * Today-screen Energy Check-In and the post-Pomodoro prompt). If a
     * row already exists for `(date, timeOfDay)` — typically written by
     * the Morning Check-In — only its `energy` is touched; `mood`,
     * `notes`, and `createdAt` are preserved so a quick energy tap can
     * never clobber a detailed morning entry. If no row exists, inserts
     * one with [DEFAULT_MOOD] (neutral 3) so the entity's 1..5
     * invariant holds.
     */
    suspend fun setEnergyForDate(
        date: Long,
        energy: Int,
        timeOfDay: String = "morning"
    ): Long {
        val now = System.currentTimeMillis()
        val existing = dao.getByDate(date).firstOrNull { it.timeOfDay == timeOfDay }
        return if (existing != null) {
            dao.update(existing.copy(energy = energy, updatedAt = now))
            syncTracker.trackUpdate(existing.id, "mood_energy_log")
            existing.id
        } else {
            val id = dao.insert(
                MoodEnergyLogEntity(
                    date = date,
                    mood = DEFAULT_MOOD,
                    energy = energy,
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

    /**
     * Returns true iff at least one mood entry with `mood <= moodCeiling`
     * was created at or after [sinceCreatedAtMillis]. Filters on
     * `created_at` so the window is wall-clock-exact rather than tied to
     * the midnight-normalized `date` column.
     */
    suspend fun hasLowMoodSince(moodCeiling: Int, sinceCreatedAtMillis: Long): Boolean =
        dao.countLowMoodSinceOnce(moodCeiling, sinceCreatedAtMillis) > 0

    private companion object {
        /** Neutral mood used by [setEnergyForDate] when no entry exists yet. */
        const val DEFAULT_MOOD = 3
    }
}
