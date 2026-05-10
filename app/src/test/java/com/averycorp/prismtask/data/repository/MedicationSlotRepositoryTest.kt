package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.MedicationSlotDao
import com.averycorp.prismtask.data.local.dao.MedicationSlotOverrideDao
import com.averycorp.prismtask.data.local.dao.MedicationTierStateDao
import com.averycorp.prismtask.data.local.entity.MedicationSlotCrossRef
import com.averycorp.prismtask.data.local.entity.MedicationSlotEntity
import com.averycorp.prismtask.data.local.entity.MedicationSlotOverrideEntity
import com.averycorp.prismtask.data.local.entity.MedicationTierStateEntity
import com.averycorp.prismtask.data.remote.SyncTracker
import com.averycorp.prismtask.domain.model.medication.AchievedTier
import com.averycorp.prismtask.domain.model.medication.TierSource
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [MedicationSlotRepository], focused on the medication
 * tier-marking surface flagged YELLOW ("no smoke") by the D2/F audit
 * (`docs/audits/D2_AND_PHASE_F_PREP_MEGA_AUDIT.md` item 4).
 *
 * Scope-shift note: the audit recommended a Compose-level smoke test
 * (`MedicationSmokeTest`) covering open-screen → tap-tier → persist. The
 * MedicationScreen route is not in bottom nav (reached only via Today /
 * Habits entry points after seeding a self-care routine), which means a
 * Compose smoke would need significant test-infrastructure scaffolding
 * to navigate there reliably. Pivoted to a unit test on
 * [MedicationSlotRepository] that exercises the same behaviour the
 * smoke would validate at a higher level — the tier-state write path
 * with the USER_SET-vs-COMPUTED precedence rule, plus slot CRUD and
 * the medication↔slot junction. The Compose smoke remains a follow-up
 * for G.0+ when we extract a deep-link / test-route helper.
 *
 * Coverage focus:
 * - Slot CRUD (insert / update / softDelete) with sync tracking + timestamp stamping
 * - Junction add / remove / replace (no SyncTracker contract per repo doc)
 * - [MedicationSlotRepository.upsertTierState] insert path
 * - upsertTierState's USER_SET-wins-over-COMPUTED invariant — the
 *   most-load-bearing rule in the medication system
 * - upsertTierState's USER_SET overwrite path
 * - deleteTierState + sync tracking
 */
class MedicationSlotRepositoryTest {
    private lateinit var slotDao: FakeMedicationSlotDao
    private lateinit var overrideDao: FakeMedicationSlotOverrideDao
    private lateinit var tierStateDao: FakeMedicationTierStateDao
    private lateinit var syncTracker: SyncTracker
    private lateinit var repo: MedicationSlotRepository

    @Before
    fun setUp() {
        slotDao = FakeMedicationSlotDao()
        overrideDao = FakeMedicationSlotOverrideDao()
        tierStateDao = FakeMedicationTierStateDao()
        syncTracker = mockk(relaxed = true)
        repo = MedicationSlotRepository(slotDao, overrideDao, tierStateDao, syncTracker)
    }

    // ── Slot CRUD ────────────────────────────────────────────────────────

    @Test
    fun insertSlot_stampsTimestampsAndTracksCreate() = runBlocking {
        val pre = System.currentTimeMillis()
        val id = repo.insertSlot(
            MedicationSlotEntity(
                name = "Morning",
                idealTime = "09:00",
                createdAt = 0,
                updatedAt = 0
            )
        )
        val post = System.currentTimeMillis()

        val stored = slotDao.rows.single { it.id == id }
        assertEquals("Morning", stored.name)
        assertTrue(
            "createdAt ${stored.createdAt} not in [$pre, $post]",
            stored.createdAt in pre..post
        )
        assertTrue(
            "updatedAt ${stored.updatedAt} not in [$pre, $post]",
            stored.updatedAt in pre..post
        )
        coVerify { syncTracker.trackCreate(id, "medication_slot") }
    }

    @Test
    fun updateSlot_bumpsUpdatedAtAndTracksUpdate() = runBlocking {
        slotDao.rows += MedicationSlotEntity(
            id = 1,
            name = "Morning",
            idealTime = "09:00",
            updatedAt = 1000L
        )

        val pre = System.currentTimeMillis()
        repo.updateSlot(
            MedicationSlotEntity(
                id = 1,
                name = "Morning",
                idealTime = "08:30",
                updatedAt = 1000L
            )
        )

        val stored = slotDao.rows.single { it.id == 1L }
        assertEquals("08:30", stored.idealTime)
        assertTrue(
            "updatedAt was not bumped — still ${stored.updatedAt}",
            stored.updatedAt >= pre
        )
        coVerify { syncTracker.trackUpdate(1L, "medication_slot") }
    }

    @Test
    fun softDeleteSlot_flipsIsActiveAndTracksUpdate() = runBlocking {
        slotDao.rows += MedicationSlotEntity(
            id = 1,
            name = "Evening",
            idealTime = "21:00",
            isActive = true
        )

        repo.softDeleteSlot(1L)

        val stored = slotDao.rows.single { it.id == 1L }
        assertFalse("Soft delete must set is_active=false", stored.isActive)
        coVerify { syncTracker.trackUpdate(1L, "medication_slot") }
    }

    @Test
    fun restoreSlot_flipsIsActiveBackOn() = runBlocking {
        slotDao.rows += MedicationSlotEntity(
            id = 1,
            name = "Evening",
            idealTime = "21:00",
            isActive = false
        )

        repo.restoreSlot(1L)

        val stored = slotDao.rows.single { it.id == 1L }
        assertTrue("Restore must set is_active=true", stored.isActive)
        coVerify { syncTracker.trackUpdate(1L, "medication_slot") }
    }

    // ── Junction (medication ↔ slot) ────────────────────────────────────

    @Test
    fun addLink_persistsCrossRef() = runBlocking {
        slotDao.rows += MedicationSlotEntity(id = 10, name = "Morning", idealTime = "09:00")

        repo.addLink(medicationId = 1L, slotId = 10L)

        val ids = repo.getSlotIdsForMedicationOnce(1L)
        assertEquals(listOf(10L), ids)
        // Junction writes deliberately bypass SyncTracker (per repo KDoc) —
        // parent medication's push embeds the slot cloud-id list.
        coVerify(exactly = 0) { syncTracker.trackCreate(any(), "medication_slot_cross_ref") }
    }

    @Test
    fun removeLink_unlinksWithoutTouchingSlot() = runBlocking {
        slotDao.rows += MedicationSlotEntity(id = 10, name = "Morning", idealTime = "09:00")
        slotDao.crossRefs += MedicationSlotCrossRef(medicationId = 1L, slotId = 10L)

        repo.removeLink(medicationId = 1L, slotId = 10L)

        assertTrue(repo.getSlotIdsForMedicationOnce(1L).isEmpty())
        // Slot row itself untouched.
        assertNotNull(slotDao.rows.firstOrNull { it.id == 10L })
    }

    @Test
    fun replaceLinksForMedication_wipesAndReinsertsDistinct() = runBlocking {
        slotDao.rows += MedicationSlotEntity(id = 10, name = "Morning", idealTime = "09:00")
        slotDao.rows += MedicationSlotEntity(id = 20, name = "Evening", idealTime = "21:00")
        slotDao.crossRefs += MedicationSlotCrossRef(medicationId = 1L, slotId = 10L)

        // Replace with a list that contains a duplicate — repo must dedup.
        repo.replaceLinksForMedication(medicationId = 1L, slotIds = listOf(20L, 20L))

        val ids = repo.getSlotIdsForMedicationOnce(1L)
        assertEquals(listOf(20L), ids)
    }

    // ── Tier states (the load-bearing user-vs-computed precedence) ──────

    @Test
    fun upsertTierState_insertPath_tracksCreate() = runBlocking {
        val id = repo.upsertTierState(
            medicationId = 1L,
            slotId = 10L,
            date = "2026-04-26",
            tier = AchievedTier.COMPLETE,
            source = TierSource.USER_SET
        )

        val stored = tierStateDao.rows.single { it.id == id }
        assertEquals(1L, stored.medicationId)
        assertEquals(10L, stored.slotId)
        assertEquals("2026-04-26", stored.logDate)
        assertEquals("complete", stored.tier)
        assertEquals("user_set", stored.tierSource)
        coVerify { syncTracker.trackCreate(id, "medication_tier_state") }
    }

    @Test
    fun upsertTierState_userSetWinsOverComputed() = runBlocking {
        val id = repo.upsertTierState(
            medicationId = 1L,
            slotId = 10L,
            date = "2026-04-26",
            tier = AchievedTier.COMPLETE,
            source = TierSource.USER_SET
        )

        // A subsequent COMPUTED write must NOT overwrite the user's choice.
        val resultId = repo.upsertTierState(
            medicationId = 1L,
            slotId = 10L,
            date = "2026-04-26",
            tier = AchievedTier.SKIPPED,
            source = TierSource.COMPUTED
        )

        assertEquals("Same row id returned (no new insert)", id, resultId)
        val stored = tierStateDao.rows.single { it.id == id }
        assertEquals("Tier must remain COMPLETE (user override wins)", "complete", stored.tier)
        assertEquals("Source must remain user_set", "user_set", stored.tierSource)
        // Crucially, no UPDATE should have been tracked for the no-op.
        coVerify(exactly = 0) { syncTracker.trackUpdate(id, "medication_tier_state") }
    }

    @Test
    fun upsertTierState_userSetOverwritesPriorUserSet() = runBlocking {
        val id = repo.upsertTierState(
            medicationId = 1L,
            slotId = 10L,
            date = "2026-04-26",
            tier = AchievedTier.ESSENTIAL,
            source = TierSource.USER_SET
        )

        repo.upsertTierState(
            medicationId = 1L,
            slotId = 10L,
            date = "2026-04-26",
            tier = AchievedTier.SKIPPED,
            source = TierSource.USER_SET
        )

        val stored = tierStateDao.rows.single { it.id == id }
        assertEquals("User-set replaces user-set", "skipped", stored.tier)
        coVerify { syncTracker.trackUpdate(id, "medication_tier_state") }
    }

    @Test
    fun upsertTierState_computedOverwritesComputed() = runBlocking {
        val id = repo.upsertTierState(
            medicationId = 1L,
            slotId = 10L,
            date = "2026-04-26",
            tier = AchievedTier.ESSENTIAL,
            source = TierSource.COMPUTED
        )

        repo.upsertTierState(
            medicationId = 1L,
            slotId = 10L,
            date = "2026-04-26",
            tier = AchievedTier.PRESCRIPTION,
            source = TierSource.COMPUTED
        )

        val stored = tierStateDao.rows.single { it.id == id }
        assertEquals("Computed replaces computed", "prescription", stored.tier)
        coVerify { syncTracker.trackUpdate(id, "medication_tier_state") }
    }

    @Test
    fun upsertTierState_separateRowsForDifferentDates() = runBlocking {
        val id1 = repo.upsertTierState(1L, 10L, "2026-04-26", AchievedTier.COMPLETE, TierSource.USER_SET)
        val id2 = repo.upsertTierState(1L, 10L, "2026-04-27", AchievedTier.SKIPPED, TierSource.USER_SET)

        assertTrue("Different dates must yield different rows", id1 != id2)
        assertEquals(2, tierStateDao.rows.size)
    }

    @Test
    fun deleteTierState_removesRowAndTracksDelete() = runBlocking {
        val id = repo.upsertTierState(1L, 10L, "2026-04-26", AchievedTier.COMPLETE, TierSource.USER_SET)
        val state = tierStateDao.rows.single { it.id == id }

        repo.deleteTierState(state)

        assertNull("Row removed", tierStateDao.rows.firstOrNull { it.id == id })
        coVerify { syncTracker.trackDelete(id, "medication_tier_state") }
    }
}

private class FakeMedicationSlotDao : MedicationSlotDao {
    val rows = mutableListOf<MedicationSlotEntity>()
    val crossRefs = mutableListOf<MedicationSlotCrossRef>()
    private var nextId = 1L

    override fun observeActive(): Flow<List<MedicationSlotEntity>> = flow {
        emit(rows.filter { it.isActive }.sortedBy { it.id })
    }

    override fun observeAll(): Flow<List<MedicationSlotEntity>> = flow {
        emit(rows.sortedBy { it.id })
    }

    override fun observeById(id: Long): Flow<MedicationSlotEntity?> = flow {
        emit(rows.firstOrNull { it.id == id })
    }

    override suspend fun getByIdOnce(id: Long): MedicationSlotEntity? =
        rows.firstOrNull { it.id == id }

    override suspend fun getActiveOnce(): List<MedicationSlotEntity> =
        rows.filter { it.isActive }.sortedBy { it.id }

    override suspend fun getIntervalModeSlotsOnce(): List<MedicationSlotEntity> =
        rows.filter { it.isActive && it.reminderMode == "INTERVAL" }.sortedBy { it.id }

    override suspend fun getAllOnce(): List<MedicationSlotEntity> = rows.sortedBy { it.id }

    override suspend fun getByCloudIdOnce(cloudId: String): MedicationSlotEntity? =
        rows.firstOrNull { it.cloudId == cloudId }

    override suspend fun getByNameOnce(name: String): MedicationSlotEntity? =
        rows.firstOrNull { it.name == name }

    override suspend fun insert(slot: MedicationSlotEntity): Long {
        val id = if (slot.id == 0L) nextId++ else slot.id
        rows += slot.copy(id = id)
        return id
    }

    override suspend fun update(slot: MedicationSlotEntity) {
        val idx = rows.indexOfFirst { it.id == slot.id }
        if (idx >= 0) rows[idx] = slot
    }

    override suspend fun softDelete(id: Long, now: Long) {
        val idx = rows.indexOfFirst { it.id == id }
        if (idx >= 0) rows[idx] = rows[idx].copy(isActive = false, updatedAt = now)
    }

    override suspend fun restore(id: Long, now: Long) {
        val idx = rows.indexOfFirst { it.id == id }
        if (idx >= 0) rows[idx] = rows[idx].copy(isActive = true, updatedAt = now)
    }

    override suspend fun delete(slot: MedicationSlotEntity) {
        rows.removeAll { it.id == slot.id }
    }

    override suspend fun deleteById(id: Long) {
        rows.removeAll { it.id == id }
    }

    override suspend fun setCloudId(id: Long, cloudId: String?, now: Long) {
        val idx = rows.indexOfFirst { it.id == id }
        if (idx >= 0) rows[idx] = rows[idx].copy(cloudId = cloudId, updatedAt = now)
    }

    override suspend fun getSlotIdsForMedicationOnce(medicationId: Long): List<Long> =
        crossRefs.filter { it.medicationId == medicationId }.map { it.slotId }

    override suspend fun getSlotsForMedicationOnce(medicationId: Long): List<MedicationSlotEntity> {
        val ids = crossRefs.filter { it.medicationId == medicationId }.map { it.slotId }.toSet()
        return rows.filter { it.id in ids }.sortedBy { it.id }
    }

    override fun observeSlotsForMedication(medicationId: Long): Flow<List<MedicationSlotEntity>> = flow {
        val ids = crossRefs.filter { it.medicationId == medicationId }.map { it.slotId }.toSet()
        emit(rows.filter { it.id in ids }.sortedBy { it.id })
    }

    override suspend fun getMedicationIdsForSlotOnce(slotId: Long): List<Long> =
        crossRefs.filter { it.slotId == slotId }.map { it.medicationId }

    override suspend fun insertLink(crossRef: MedicationSlotCrossRef) {
        if (crossRefs.none { it.medicationId == crossRef.medicationId && it.slotId == crossRef.slotId }) {
            crossRefs += crossRef
        }
    }

    override suspend fun insertLinks(crossRefs: List<MedicationSlotCrossRef>) {
        crossRefs.forEach { insertLink(it) }
    }

    override suspend fun deleteLink(medicationId: Long, slotId: Long) {
        crossRefs.removeAll { it.medicationId == medicationId && it.slotId == slotId }
    }

    override suspend fun deleteLinksForMedication(medicationId: Long) {
        crossRefs.removeAll { it.medicationId == medicationId }
    }
}

private class FakeMedicationSlotOverrideDao : MedicationSlotOverrideDao {
    val rows = mutableListOf<MedicationSlotOverrideEntity>()
    private var nextId = 1L

    override fun observeForMedication(medicationId: Long): Flow<List<MedicationSlotOverrideEntity>> = flow {
        emit(rows.filter { it.medicationId == medicationId })
    }

    override fun observeAll(): Flow<List<MedicationSlotOverrideEntity>> = flow {
        emit(rows.toList())
    }

    override suspend fun getForMedicationOnce(medicationId: Long): List<MedicationSlotOverrideEntity> =
        rows.filter { it.medicationId == medicationId }

    override suspend fun getForPairOnce(medicationId: Long, slotId: Long): MedicationSlotOverrideEntity? =
        rows.firstOrNull { it.medicationId == medicationId && it.slotId == slotId }

    override suspend fun getByIdOnce(id: Long): MedicationSlotOverrideEntity? =
        rows.firstOrNull { it.id == id }

    override suspend fun getByCloudIdOnce(cloudId: String): MedicationSlotOverrideEntity? =
        rows.firstOrNull { it.cloudId == cloudId }

    override suspend fun getAllOnce(): List<MedicationSlotOverrideEntity> = rows.toList()

    override suspend fun insert(override: MedicationSlotOverrideEntity): Long {
        val id = if (override.id == 0L) nextId++ else override.id
        rows += override.copy(id = id)
        return id
    }

    override suspend fun update(override: MedicationSlotOverrideEntity) {
        val idx = rows.indexOfFirst { it.id == override.id }
        if (idx >= 0) rows[idx] = override
    }

    override suspend fun delete(override: MedicationSlotOverrideEntity) {
        rows.removeAll { it.id == override.id }
    }

    override suspend fun deleteById(id: Long) {
        rows.removeAll { it.id == id }
    }

    override suspend fun deleteForPair(medicationId: Long, slotId: Long) {
        rows.removeAll { it.medicationId == medicationId && it.slotId == slotId }
    }

    override suspend fun setCloudId(id: Long, cloudId: String?, now: Long) {
        val idx = rows.indexOfFirst { it.id == id }
        if (idx >= 0) rows[idx] = rows[idx].copy(cloudId = cloudId, updatedAt = now)
    }
}

private class FakeMedicationTierStateDao : MedicationTierStateDao {
    val rows = mutableListOf<MedicationTierStateEntity>()
    private var nextId = 1L

    override fun observeForDate(date: String): Flow<List<MedicationTierStateEntity>> = flow {
        emit(rows.filter { it.logDate == date })
    }

    override fun observeAll(): Flow<List<MedicationTierStateEntity>> = flow { emit(rows.toList()) }

    override suspend fun getForDateOnce(date: String): List<MedicationTierStateEntity> =
        rows.filter { it.logDate == date }

    override suspend fun getForTripleOnce(
        medicationId: Long,
        date: String,
        slotId: Long
    ): MedicationTierStateEntity? =
        rows.firstOrNull {
            it.medicationId == medicationId && it.logDate == date && it.slotId == slotId
        }

    override suspend fun getForQuadrupleOnce(
        medicationId: Long,
        date: String,
        slotId: Long,
        timeOfDay: String
    ): MedicationTierStateEntity? =
        rows.firstOrNull {
            it.medicationId == medicationId &&
                it.logDate == date &&
                it.slotId == slotId &&
                it.timeOfDay == timeOfDay
        }

    override suspend fun getDistinctTimeOfDayForDateOnce(date: String): List<String> =
        rows.filter { it.logDate == date && it.timeOfDay != null }
            .mapNotNull { it.timeOfDay }
            .distinct()

    override suspend fun getForSlotDateOnce(slotId: Long, date: String): List<MedicationTierStateEntity> =
        rows.filter { it.slotId == slotId && it.logDate == date }

    override suspend fun getByIdOnce(id: Long): MedicationTierStateEntity? =
        rows.firstOrNull { it.id == id }

    override suspend fun getByCloudIdOnce(cloudId: String): MedicationTierStateEntity? =
        rows.firstOrNull { it.cloudId == cloudId }

    override suspend fun getAllOnce(): List<MedicationTierStateEntity> = rows.toList()

    override suspend fun insert(state: MedicationTierStateEntity): Long {
        val id = if (state.id == 0L) nextId++ else state.id
        rows += state.copy(id = id)
        return id
    }

    override suspend fun update(state: MedicationTierStateEntity) {
        val idx = rows.indexOfFirst { it.id == state.id }
        if (idx >= 0) rows[idx] = state
    }

    override suspend fun delete(state: MedicationTierStateEntity) {
        rows.removeAll { it.id == state.id }
    }

    override suspend fun deleteById(id: Long) {
        rows.removeAll { it.id == id }
    }

    override suspend fun setCloudId(id: Long, cloudId: String?, now: Long) {
        val idx = rows.indexOfFirst { it.id == id }
        if (idx >= 0) rows[idx] = rows[idx].copy(cloudId = cloudId, updatedAt = now)
    }
}
