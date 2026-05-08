package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.MedicationDao
import com.averycorp.prismtask.data.local.dao.MedicationDoseDao
import com.averycorp.prismtask.data.local.entity.MedicationDoseEntity
import com.averycorp.prismtask.data.local.entity.MedicationEntity
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.remote.SyncTracker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [MedicationRepository]. Uses in-memory fakes for the
 * two DAOs and a relaxed mock for [SyncTracker] so we can verify
 * every write path calls `trackCreate` / `trackUpdate` / `trackDelete`
 * with the correct entity type — the contract that keeps medications
 * syncing through [SyncService].
 */
class MedicationRepositoryTest {
    private lateinit var medicationDao: FakeMedicationDaoForRepo
    private lateinit var medicationDoseDao: FakeMedicationDoseDaoForRepo
    private lateinit var syncTracker: SyncTracker
    private lateinit var taskBehaviorPreferences: TaskBehaviorPreferences
    private lateinit var repo: MedicationRepository

    @Before
    fun setUp() {
        medicationDao = FakeMedicationDaoForRepo()
        medicationDoseDao = FakeMedicationDoseDaoForRepo()
        syncTracker = mockk(relaxed = true)
        taskBehaviorPreferences = mockk(relaxed = true)
        // Day starts at midnight so dose date normalization is predictable.
        coEvery { taskBehaviorPreferences.getDayStartHour() } returns flowOf(0)
        repo = MedicationRepository(
            medicationDao = medicationDao,
            medicationDoseDao = medicationDoseDao,
            syncTracker = syncTracker,
            taskBehaviorPreferences = taskBehaviorPreferences,
            widgetUpdateManager = mockk(relaxed = true),
            automationEventBus = com.averycorp.prismtask.domain.automation.AutomationEventBus()
        )
    }

    @Test
    fun insert_storesMedicationAndTracksCreate() = runBlocking {
        val id = repo.insert(MedicationEntity(name = "Lipitor"))

        val stored = medicationDao.rows.single { it.id == id }
        assertEquals("Lipitor", stored.name)
        coVerify { syncTracker.trackCreate(id, "medication") }
    }

    @Test
    fun insert_recoversFromUniqueNameCollisionByAdoptingExistingRow() = runBlocking {
        // A pre-existing row with the same name simulates a concurrent
        // insert (or sync pull) that won the race against this caller's
        // pre-flight `getByNameOnce` check. The DAO insert throws
        // SQLiteConstraintException; the repo must catch it and return
        // the existing row's id rather than crashing.
        medicationDao.rows += MedicationEntity(id = 42, name = "Lipitor")
        medicationDao.failNextInsertOnNameCollision = true

        val id = repo.insert(MedicationEntity(name = "Lipitor"))

        assertEquals(42L, id)
        // Adopt path must NOT track a create — no row was actually inserted.
        coVerify(exactly = 0) { syncTracker.trackCreate(any(), "medication") }
    }

    @Test
    fun insert_stampsCreatedAndUpdatedAtAtInsertTime() = runBlocking {
        // Pass a medication with zero timestamps; the repo should stamp
        // them — sync needs updatedAt to resolve last-write-wins.
        val pre = System.currentTimeMillis()
        val id = repo.insert(MedicationEntity(name = "Adderall", createdAt = 0, updatedAt = 0))
        val post = System.currentTimeMillis()

        val stored = medicationDao.rows.single { it.id == id }
        assertNotNull(stored.createdAt)
        assert(stored.createdAt in pre..post) {
            "createdAt ${stored.createdAt} not within [$pre, $post]"
        }
        assert(stored.updatedAt in pre..post) {
            "updatedAt ${stored.updatedAt} not within [$pre, $post]"
        }
    }

    @Test
    fun update_bumpsUpdatedAtAndTracksUpdate() = runBlocking {
        medicationDao.rows += MedicationEntity(id = 1, name = "Lipitor", updatedAt = 1000L)

        val pre = System.currentTimeMillis()
        repo.update(MedicationEntity(id = 1, name = "Lipitor", displayLabel = "20mg", updatedAt = 1000L))

        val stored = medicationDao.rows.single { it.id == 1L }
        assertEquals("20mg", stored.displayLabel)
        assert(stored.updatedAt >= pre) {
            "updatedAt was not bumped — still ${stored.updatedAt}"
        }
        coVerify { syncTracker.trackUpdate(1L, "medication") }
    }

    @Test
    fun archive_flagsRowAndTracksUpdate() = runBlocking {
        medicationDao.rows += MedicationEntity(id = 1, name = "Lipitor")

        repo.archive(1)

        val stored = medicationDao.rows.single { it.id == 1L }
        assert(stored.isArchived) { "archive() must flip isArchived=true" }
        coVerify { syncTracker.trackUpdate(1L, "medication") }
    }

    @Test
    fun delete_removesRowAndTracksDelete() = runBlocking {
        val med = MedicationEntity(id = 1, name = "Lipitor")
        medicationDao.rows += med

        repo.delete(med)

        assert(medicationDao.rows.none { it.id == 1L }) {
            "delete() must remove the row (cascades to doses via FK in prod)"
        }
        coVerify { syncTracker.trackDelete(1L, "medication") }
    }

    @Test
    fun logDose_insertsDoseAndTracksCreate() = runBlocking {
        medicationDao.rows += MedicationEntity(id = 1, name = "Lipitor")

        val doseId = repo.logDose(
            medicationId = 1,
            slotKey = "morning",
            takenAt = 1_000_000L,
            note = "with breakfast"
        )

        val dose = medicationDoseDao.rows.single { it.id == doseId }
        assertEquals(1L, dose.medicationId)
        assertEquals("morning", dose.slotKey)
        assertEquals(1_000_000L, dose.takenAt)
        assertEquals("with breakfast", dose.note)
        coVerify { syncTracker.trackCreate(doseId, "medication_dose") }
    }

    @Test
    fun logDose_computesTakenDateLocalFromTakenAt() = runBlocking {
        medicationDao.rows += MedicationEntity(id = 1, name = "Lipitor")

        // 2026-04-22 12:00 UTC — exact epoch known.
        val takenAt = 1_777_204_800_000L
        val doseId = repo.logDose(medicationId = 1, slotKey = "morning", takenAt = takenAt)

        val dose = medicationDoseDao.rows.single { it.id == doseId }
        // takenDateLocal must be the ISO string form of the day takenAt fell
        // on in the device's local TZ (which varies in tests). We just assert
        // it's non-blank and looks like an ISO date, since timezone-dependent
        // string comparison would be flaky.
        assertEquals(10, dose.takenDateLocal.length)
        assert(dose.takenDateLocal.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) {
            "takenDateLocal '${dose.takenDateLocal}' should be ISO yyyy-MM-dd"
        }
    }

    @Test
    fun logCustomDose_storesCustomNameWithNullMedicationIdAndAnytimeSlot() = runBlocking {
        val doseId = repo.logCustomDose(
            name = "Tylenol 500mg",
            takenAt = 1_000_000L,
            note = "headache"
        )

        val dose = medicationDoseDao.rows.single { it.id == doseId }
        // The whole point of this feature: dose carries the custom name
        // verbatim and has no FK to a tracked medication, so the user
        // can record "I took something" without first creating a med.
        assertEquals(null, dose.medicationId)
        assertEquals("Tylenol 500mg", dose.customMedicationName)
        assertEquals("anytime", dose.slotKey)
        assertEquals("headache", dose.note)
        coVerify { syncTracker.trackCreate(doseId, "medication_dose") }
    }

    @Test
    fun logCustomDose_trimsLeadingAndTrailingWhitespace() = runBlocking {
        val doseId = repo.logCustomDose(name = "  Tylenol  ", takenAt = 1L)

        val dose = medicationDoseDao.rows.single { it.id == doseId }
        // We trim because users dictate names by voice or paste them,
        // and trailing/leading whitespace would render as ugly gaps in
        // the log row label.
        assertEquals("Tylenol", dose.customMedicationName)
    }

    @Test
    fun logCustomDose_blankNameThrows() = runBlocking {
        try {
            repo.logCustomDose(name = "   ", takenAt = 1L)
            error("expected IllegalArgumentException for blank name")
        } catch (_: IllegalArgumentException) {
            // expected — a custom dose with no name has no way to
            // render in the log and would defeat the feature's purpose.
        }
        assert(medicationDoseDao.rows.isEmpty()) {
            "blank-name guard must short-circuit before insert"
        }
    }

    @Test
    fun unlogDose_removesDoseAndTracksDelete() = runBlocking {
        val dose = MedicationDoseEntity(
            id = 10,
            medicationId = 1,
            slotKey = "morning",
            takenAt = 1L,
            takenDateLocal = "2026-04-22"
        )
        medicationDoseDao.rows += dose

        repo.unlogDose(dose)

        assert(medicationDoseDao.rows.none { it.id == 10L })
        coVerify { syncTracker.trackDelete(10L, "medication_dose") }
    }

    @Test
    fun updateDose_bumpsUpdatedAtAndTracksUpdate() = runBlocking {
        val dose = MedicationDoseEntity(
            id = 10,
            medicationId = 1,
            slotKey = "morning",
            takenAt = 1L,
            takenDateLocal = "2026-04-22",
            updatedAt = 1000L
        )
        medicationDoseDao.rows += dose

        val pre = System.currentTimeMillis()
        repo.updateDose(dose.copy(note = "edited"))

        val stored = medicationDoseDao.rows.single { it.id == 10L }
        assertEquals("edited", stored.note)
        assert(stored.updatedAt >= pre)
        coVerify { syncTracker.trackUpdate(10L, "medication_dose") }
    }

    @Test
    fun getByNameOnce_matchesExactName() = runBlocking {
        medicationDao.rows += MedicationEntity(id = 1, name = "Lipitor")
        medicationDao.rows += MedicationEntity(id = 2, name = "Adderall")

        val found = repo.getByNameOnce("Lipitor")
        assertEquals(1L, found?.id)

        val missing = repo.getByNameOnce("Metformin")
        assertEquals(null, missing)
    }

    @Test
    fun logSyntheticSkipDose_insertsDoseFlaggedSyntheticAndTracksCreate() = runBlocking {
        medicationDao.rows += MedicationEntity(id = 1, name = "Lipitor")

        val doseId = repo.logSyntheticSkipDose(
            medicationId = 1,
            slotKey = "morning",
            intendedAt = 1_000_000L
        )

        val dose = medicationDoseDao.rows.single { it.id == doseId }
        assertEquals(1L, dose.medicationId)
        assertEquals("morning", dose.slotKey)
        assertEquals(1_000_000L, dose.takenAt)
        assert(dose.isSyntheticSkip) { "logSyntheticSkipDose must set isSyntheticSkip=true" }
        assertEquals("synthetic skips never carry user notes", "", dose.note)
        coVerify { syncTracker.trackCreate(doseId, "medication_dose") }
    }

    @Test
    fun logSyntheticSkipDose_appearsInGetMostRecentDoseAnyOnceForAnchor() = runBlocking {
        medicationDao.rows += MedicationEntity(id = 1, name = "Lipitor")
        // Real dose at t=1000.
        repo.logDose(medicationId = 1, slotKey = "morning", takenAt = 1000L)
        // Synthetic skip at t=2000 — must win as the anchor.
        val syntheticId = repo.logSyntheticSkipDose(
            medicationId = 1,
            slotKey = "morning",
            intendedAt = 2000L
        )

        val anchor = medicationDoseDao.getMostRecentDoseAnyOnce()
        assertEquals(syntheticId, anchor?.id)
    }

    @Test
    fun logSyntheticSkipDose_excludedFromGetMostRecentRealDoseOnce() = runBlocking {
        medicationDao.rows += MedicationEntity(id = 1, name = "Lipitor")
        val realId = repo.logDose(medicationId = 1, slotKey = "morning", takenAt = 1000L)
        repo.logSyntheticSkipDose(medicationId = 1, slotKey = "morning", intendedAt = 2000L)

        val realAnchor = medicationDoseDao.getMostRecentRealDoseOnce()
        assertEquals("synthetic skips must NOT appear in the real-dose anchor", realId, realAnchor?.id)
    }

    @Test
    fun countDosesForMedOnDate_returnsMatchingCount() = runBlocking {
        medicationDoseDao.rows += MedicationDoseEntity(
            id = 1, medicationId = 1, slotKey = "morning",
            takenAt = 1L, takenDateLocal = "2026-04-22"
        )
        medicationDoseDao.rows += MedicationDoseEntity(
            id = 2, medicationId = 1, slotKey = "evening",
            takenAt = 2L, takenDateLocal = "2026-04-22"
        )
        medicationDoseDao.rows += MedicationDoseEntity(
            id = 3, medicationId = 1, slotKey = "morning",
            takenAt = 3L, takenDateLocal = "2026-04-21"
        )
        medicationDoseDao.rows += MedicationDoseEntity(
            id = 4, medicationId = 2, slotKey = "morning",
            takenAt = 4L, takenDateLocal = "2026-04-22"
        )

        assertEquals(2, repo.countDosesForMedOnDateOnce(medicationId = 1, date = "2026-04-22"))
        assertEquals(1, repo.countDosesForMedOnDateOnce(medicationId = 1, date = "2026-04-21"))
        assertEquals(0, repo.countDosesForMedOnDateOnce(medicationId = 3, date = "2026-04-22"))
    }
}

// --- in-memory fake DAOs ------------------------------------------------

private class FakeMedicationDaoForRepo : MedicationDao {
    val rows = mutableListOf<MedicationEntity>()
    private var nextId = 1L

    /**
     * When true, the next `insert` call whose `name` already exists in
     * [rows] throws `SQLiteConstraintException` (matching the real Room
     * DAO behavior under `OnConflictStrategy.ABORT`). One-shot — flag
     * resets after the throw so subsequent inserts behave normally.
     */
    var failNextInsertOnNameCollision: Boolean = false

    override suspend fun insert(medication: MedicationEntity): Long {
        if (failNextInsertOnNameCollision && rows.any { it.name == medication.name }) {
            failNextInsertOnNameCollision = false
            throw android.database.sqlite.SQLiteConstraintException(
                "UNIQUE constraint failed: medications.name"
            )
        }
        val id = if (medication.id == 0L) nextId++ else medication.id
        rows += medication.copy(id = id)
        return id
    }

    override suspend fun update(medication: MedicationEntity) {
        val idx = rows.indexOfFirst { it.id == medication.id }
        if (idx >= 0) rows[idx] = medication
    }

    override suspend fun archive(id: Long, now: Long) {
        val idx = rows.indexOfFirst { it.id == id }
        if (idx >= 0) rows[idx] = rows[idx].copy(isArchived = true, updatedAt = now)
    }

    override suspend fun unarchive(id: Long, now: Long) {
        val idx = rows.indexOfFirst { it.id == id }
        if (idx >= 0) rows[idx] = rows[idx].copy(isArchived = false, updatedAt = now)
    }

    override suspend fun delete(medication: MedicationEntity) {
        rows.removeAll { it.id == medication.id }
    }

    override suspend fun deleteById(id: Long) {
        rows.removeAll { it.id == id }
    }

    override suspend fun getByIdOnce(id: Long): MedicationEntity? =
        rows.firstOrNull { it.id == id }

    override suspend fun getByNameOnce(name: String): MedicationEntity? =
        rows.firstOrNull { it.name == name }

    override suspend fun getActiveOnce(): List<MedicationEntity> =
        rows.filter { !it.isArchived }

    override suspend fun getAllOnce(): List<MedicationEntity> = rows.toList()

    override suspend fun getByCloudIdOnce(cloudId: String): MedicationEntity? =
        rows.firstOrNull { it.cloudId == cloudId }

    override suspend fun setCloudId(id: Long, cloudId: String?, now: Long) {
        val idx = rows.indexOfFirst { it.id == id }
        if (idx >= 0) rows[idx] = rows[idx].copy(cloudId = cloudId, updatedAt = now)
    }

    override fun getActive() = error("flow not exercised")
    override fun getAll() = error("flow not exercised")
    override fun observeById(id: Long) = error("flow not exercised")

    override suspend fun getIntervalModeMedicationsOnce(): List<MedicationEntity> =
        rows.filter { !it.isArchived && it.reminderMode == "INTERVAL" }
}

private class FakeMedicationDoseDaoForRepo : MedicationDoseDao {
    val rows = mutableListOf<MedicationDoseEntity>()
    private var nextId = 1L

    override suspend fun insert(dose: MedicationDoseEntity): Long {
        val id = if (dose.id == 0L) nextId++ else dose.id
        rows += dose.copy(id = id)
        return id
    }

    override suspend fun update(dose: MedicationDoseEntity) {
        val idx = rows.indexOfFirst { it.id == dose.id }
        if (idx >= 0) rows[idx] = dose
    }

    override suspend fun delete(dose: MedicationDoseEntity) {
        rows.removeAll { it.id == dose.id }
    }

    override suspend fun deleteById(id: Long) {
        rows.removeAll { it.id == id }
    }

    override suspend fun countForMedOnce(medicationId: Long): Int =
        rows.count { it.medicationId == medicationId }

    override suspend fun countForMedOnDateOnce(medicationId: Long, date: String): Int =
        rows.count { it.medicationId == medicationId && it.takenDateLocal == date }

    override suspend fun getAllOnce(): List<MedicationDoseEntity> = rows.toList()

    override suspend fun getAllForMedOnce(medicationId: Long): List<MedicationDoseEntity> =
        rows.filter { it.medicationId == medicationId }

    override suspend fun getLatestForMedOnce(medicationId: Long): MedicationDoseEntity? =
        rows.filter { it.medicationId == medicationId }.maxByOrNull { it.takenAt }

    override suspend fun getByCloudIdOnce(cloudId: String): MedicationDoseEntity? =
        rows.firstOrNull { it.cloudId == cloudId }

    override suspend fun setCloudId(id: Long, cloudId: String?, now: Long) {
        val idx = rows.indexOfFirst { it.id == id }
        if (idx >= 0) rows[idx] = rows[idx].copy(cloudId = cloudId, updatedAt = now)
    }

    override suspend fun reassignMedicationId(oldId: Long, newId: Long) {
        val updated = rows.map { if (it.medicationId == oldId) it.copy(medicationId = newId) else it }
        rows.clear()
        rows += updated
    }

    override fun observeAll() = error("flow not exercised")
    override fun getForDate(date: String) = error("flow not exercised")
    override fun getForMedOnDate(medicationId: Long, date: String) =
        error("flow not exercised")

    override suspend fun getForDateOnce(date: String): List<MedicationDoseEntity> =
        rows.filter { it.takenDateLocal == date }

    override suspend fun getMostRecentDoseAnyOnce(): MedicationDoseEntity? =
        rows.maxByOrNull { it.takenAt }

    override fun observeMostRecentDoseAny() = error("flow not exercised")

    override suspend fun getMostRecentRealDoseOnce(): MedicationDoseEntity? =
        rows.filterNot { it.isSyntheticSkip }.maxByOrNull { it.takenAt }
}
