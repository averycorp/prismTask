package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.MoodEnergyLogDao
import com.averycorp.prismtask.data.local.entity.MoodEnergyLogEntity
import com.averycorp.prismtask.data.remote.SyncTracker
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [MoodEnergyRepository] — the v1.4.0 V7 mood/energy
 * tracking surface that was flagged RED ("zero coverage") by the D2/F
 * audit (`docs/audits/D2_AND_PHASE_F_PREP_MEGA_AUDIT.md` item 4).
 *
 * Coverage focus: the [MoodEnergyRepository.upsertForDate] dedup logic
 * (replace-vs-insert per `(date, timeOfDay)`) and the [SyncTracker]
 * contract that keeps mood logs syncing.
 */
class MoodEnergyRepositoryTest {
    private lateinit var dao: FakeMoodEnergyLogDao
    private lateinit var syncTracker: SyncTracker
    private lateinit var repo: MoodEnergyRepository

    @Before
    fun setUp() {
        dao = FakeMoodEnergyLogDao()
        syncTracker = mockk(relaxed = true)
        repo = MoodEnergyRepository(dao, syncTracker)
    }

    @Test
    fun upsertForDate_insertsWhenNoExistingEntry() = runBlocking {
        val id = repo.upsertForDate(date = DAY_1, mood = 4, energy = 3, notes = "Slept ok")

        val stored = dao.rows.single { it.id == id }
        assertEquals(DAY_1, stored.date)
        assertEquals(4, stored.mood)
        assertEquals(3, stored.energy)
        assertEquals("Slept ok", stored.notes)
        assertEquals("morning", stored.timeOfDay)
        coVerify { syncTracker.trackCreate(id, "mood_energy_log") }
    }

    @Test
    fun upsertForDate_updatesExistingEntryForSameDateAndTimeOfDay() = runBlocking {
        val id = repo.upsertForDate(date = DAY_1, mood = 2, energy = 2)
        val updatedId = repo.upsertForDate(date = DAY_1, mood = 5, energy = 4, notes = "Better now")

        assertEquals("Same (date, time_of_day) reuses the row id", id, updatedId)
        val rows = dao.rows.filter { it.date == DAY_1 }
        assertEquals("Upsert must not duplicate the row", 1, rows.size)
        val row = rows.single()
        assertEquals(5, row.mood)
        assertEquals(4, row.energy)
        assertEquals("Better now", row.notes)
        coVerify { syncTracker.trackUpdate(id, "mood_energy_log") }
    }

    @Test
    fun upsertForDate_keepsSeparateRowsForMorningAndEvening() = runBlocking {
        val morningId = repo.upsertForDate(date = DAY_1, mood = 4, energy = 3, timeOfDay = "morning")
        val eveningId = repo.upsertForDate(date = DAY_1, mood = 2, energy = 1, timeOfDay = "evening")

        assertTrue("Different time_of_day must yield different rows", morningId != eveningId)
        val onDay1 = dao.rows.filter { it.date == DAY_1 }
        assertEquals(2, onDay1.size)
        assertEquals(setOf("morning", "evening"), onDay1.map { it.timeOfDay }.toSet())
    }

    @Test
    fun upsertForDate_doesNotCollideAcrossDifferentDates() = runBlocking {
        val day1Id = repo.upsertForDate(date = DAY_1, mood = 3, energy = 3)
        val day2Id = repo.upsertForDate(date = DAY_2, mood = 5, energy = 5)

        assertTrue(day1Id != day2Id)
        assertEquals(2, dao.rows.size)
    }

    @Test
    fun getByDate_returnsRowsForExactDateOnly() = runBlocking {
        repo.upsertForDate(date = DAY_1, mood = 3, energy = 3)
        repo.upsertForDate(date = DAY_1, mood = 4, energy = 4, timeOfDay = "evening")
        repo.upsertForDate(date = DAY_2, mood = 5, energy = 5)

        val day1 = repo.getByDate(DAY_1)
        assertEquals(2, day1.size)
        assertTrue(day1.all { it.date == DAY_1 })
    }

    @Test
    fun observeRange_emitsRowsInDateRange() = runBlocking {
        repo.upsertForDate(date = DAY_1, mood = 3, energy = 3)
        repo.upsertForDate(date = DAY_2, mood = 4, energy = 4)
        repo.upsertForDate(date = DAY_3, mood = 5, energy = 5)

        val inRange = repo.observeRange(DAY_1, DAY_2).first()
        assertEquals(2, inRange.size)
        assertEquals(setOf(DAY_1, DAY_2), inRange.map { it.date }.toSet())
    }

    @Test
    fun delete_removesRowAndTracksDelete() = runBlocking {
        val id = repo.upsertForDate(date = DAY_1, mood = 3, energy = 3)

        repo.delete(id)

        assertNull("Row removed", dao.rows.firstOrNull { it.id == id })
        coVerify { syncTracker.trackDelete(id, "mood_energy_log") }
    }

    @Test
    fun getAll_returnsEveryRow() = runBlocking {
        repo.upsertForDate(date = DAY_1, mood = 3, energy = 3)
        repo.upsertForDate(date = DAY_1, mood = 4, energy = 4, timeOfDay = "evening")
        repo.upsertForDate(date = DAY_2, mood = 5, energy = 5)

        val all = repo.getAll()
        assertEquals(3, all.size)
    }

    private companion object {
        // Three midnight-normalized millis on consecutive UTC days.
        const val DAY_1 = 1_745_625_600_000L // 2026-04-26
        const val DAY_2 = 1_745_712_000_000L // 2026-04-27
        const val DAY_3 = 1_745_798_400_000L // 2026-04-28
    }
}

private class FakeMoodEnergyLogDao : MoodEnergyLogDao {
    val rows = mutableListOf<MoodEnergyLogEntity>()
    private var nextId = 1L

    override suspend fun insert(log: MoodEnergyLogEntity): Long {
        val id = if (log.id == 0L) nextId++ else log.id
        rows += log.copy(id = id)
        return id
    }

    override suspend fun update(log: MoodEnergyLogEntity) {
        val idx = rows.indexOfFirst { it.id == log.id }
        if (idx >= 0) rows[idx] = log
    }

    override suspend fun getByDate(date: Long): List<MoodEnergyLogEntity> =
        rows.filter { it.date == date }.sortedBy { it.timeOfDay }

    override suspend fun getByDateAndTimeOfDayOnce(
        date: Long,
        timeOfDay: String
    ): MoodEnergyLogEntity? =
        rows.firstOrNull { it.date == date && it.timeOfDay == timeOfDay }

    override fun observeRange(start: Long, end: Long): Flow<List<MoodEnergyLogEntity>> = flow {
        emit(rows.filter { it.date in start..end }.sortedWith(compareBy({ it.date }, { it.timeOfDay })))
    }

    override suspend fun getRange(start: Long, end: Long): List<MoodEnergyLogEntity> =
        rows.filter { it.date in start..end }.sortedWith(compareBy({ it.date }, { it.timeOfDay }))

    override suspend fun getAll(): List<MoodEnergyLogEntity> = rows.sortedByDescending { it.date }

    override suspend fun delete(id: Long) {
        rows.removeAll { it.id == id }
    }

    override suspend fun deleteAll() {
        rows.clear()
    }

    override suspend fun getAllOnce(): List<MoodEnergyLogEntity> = rows.sortedByDescending { it.date }

    override suspend fun getByIdOnce(id: Long): MoodEnergyLogEntity? = rows.firstOrNull { it.id == id }

    override suspend fun getByCloudIdOnce(cloudId: String): MoodEnergyLogEntity? =
        rows.firstOrNull { it.cloudId == cloudId }

    override suspend fun setCloudId(id: Long, cloudId: String?, now: Long) {
        val idx = rows.indexOfFirst { it.id == id }
        if (idx >= 0) rows[idx] = rows[idx].copy(cloudId = cloudId, updatedAt = now)
    }

    override suspend fun deleteById(id: Long) {
        rows.removeAll { it.id == id }
    }

    override suspend fun countLowMoodSinceOnce(
        moodCeiling: Int,
        sinceCreatedAtMillis: Long
    ): Int = rows.count { it.mood <= moodCeiling && it.createdAt >= sinceCreatedAtMillis }
}
