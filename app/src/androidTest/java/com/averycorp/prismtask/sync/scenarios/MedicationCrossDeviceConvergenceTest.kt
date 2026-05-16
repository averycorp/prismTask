package com.averycorp.prismtask.sync.scenarios

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.averycorp.prismtask.data.local.entity.MedicationEntity
import com.averycorp.prismtask.data.repository.MedicationRepository
import com.averycorp.prismtask.data.repository.MedicationSlotRepository
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Cross-device sync scenarios for the medication subsystem (C3 of the
 * medication migration safety net). Both devices run the same schema
 * version — `SyncTestHarness` cannot pair Room DBs at different
 * versions; cross-version cases live in the manual runbook
 * (`docs/archive/MEDICATION_MIGRATION_INSTRUMENTATION.md`).
 *
 * Each scenario exercises a real production sync path: the
 * `SyncService.pullRemoteChanges` pipeline at
 * `SyncService.kt:1952–2027` for slots/medications, plus the FK
 * resolution that ties `medication_doses` to its parent `medications`
 * row across the device boundary.
 *
 * Per memory `feedback_firestore_doc_iteration_order.md`, assertions
 * focus on convergence shape (row counts, FK integrity, junction
 * presence) — never on which `cloud_id` "wins" a natural-key dedup,
 * because the SDK's doc iteration order flips between CI runs.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MedicationCrossDeviceConvergenceTest : SyncScenarioTestBase() {

    @Inject
    lateinit var medicationRepository: MedicationRepository

    @Inject
    lateinit var medicationSlotRepository: MedicationSlotRepository

    /**
     * Same-cloud_id concurrent edit: B's later write supersedes A's
     * local row on pull. Pin the last-write-wins contract for the
     * `medications` collection — beta installs will routinely
     * cross-edit the same med (refill date, pharmacy info) and the
     * loser must surface predictably.
     */
    @Test
    fun medicationLastWriteWins_remoteUpdateOverwritesLocal() = runBlocking {
        withTimeout(TEST_TIMEOUT) {
            requireSignedIn()

            val medId = medicationRepository.insert(
                MedicationEntity(
                    name = "Lexapro",
                    notes = "A's original",
                    createdAt = 0L,
                    updatedAt = 0L
                )
            )
            syncService.pushLocalChanges()

            val cloudId = database.syncMetadataDao().getCloudId(medId, "medication")
            assertNotNull("medication cloud_id populated after push", cloudId)
            assertEquals(
                "exactly one Firestore medications doc after push",
                1,
                harness.firestoreCount("medications")
            )

            val futureUpdatedAt = System.currentTimeMillis() + 60_000L
            harness.writeAsDeviceB(
                subcollection = "medications",
                docId = cloudId!!,
                fields = mapOf(
                    "localId" to 9999L,
                    "name" to "Lexapro",
                    "notes" to "B's later write",
                    "tier" to "essential",
                    "isArchived" to false,
                    "sortOrder" to 0,
                    "scheduleMode" to "TIMES_OF_DAY",
                    "dosesPerDay" to 1,
                    "pillsPerDose" to 1,
                    "reminderDaysBefore" to 3,
                    "slotCloudIds" to emptyList<String>(),
                    "createdAt" to 0L,
                    "updatedAt" to futureUpdatedAt
                )
            )

            syncService.pullRemoteChanges()

            harness.waitFor(message = "B's note arrives on A") {
                database.medicationDao().getByIdOnce(medId)?.notes == "B's later write"
            }
            val finalLocal = database.medicationDao().getByIdOnce(medId)
            assertNotNull("local medication still present after pull", finalLocal)
            assertEquals("Lexapro", finalLocal!!.name)
            assertEquals("B's later write", finalLocal.notes)
            assertEquals(
                "Firestore unchanged at one doc (B overwrote in place)",
                1,
                harness.firestoreCount("medications")
            )
        }
    }

    /**
     * Cross-device parent FK resolution: A inserts the medication,
     * B writes a dose referencing it. After A pulls, the dose binds
     * to A's local medication via the cloud-id lookup at
     * `SyncService.kt:2032–2034`. Pin this so a future change to the
     * dose mapper that breaks `medicationCloudId` resolution surfaces
     * as a failing test, not a silent CASCADE-orphan in production.
     */
    @Test
    fun medicationDoseFkResolvesAcrossDevices() = runBlocking {
        withTimeout(TEST_TIMEOUT) {
            requireSignedIn()

            val medId = medicationRepository.insert(
                MedicationEntity(
                    name = "Adderall",
                    createdAt = 0L,
                    updatedAt = 0L
                )
            )
            syncService.pushLocalChanges()
            val medCloudId = database.syncMetadataDao().getCloudId(medId, "medication")
            assertNotNull("parent medication cloud_id populated", medCloudId)

            val nowMs = System.currentTimeMillis()
            harness.writeAsDeviceB(
                subcollection = "medication_doses",
                docId = "dose-from-b",
                fields = mapOf(
                    "localId" to 8888L,
                    "medicationCloudId" to medCloudId!!,
                    "slotKey" to "morning",
                    "takenAt" to nowMs,
                    "takenDateLocal" to "2026-04-25",
                    "note" to "",
                    "isSyntheticSkip" to false,
                    "createdAt" to nowMs,
                    "updatedAt" to nowMs
                )
            )

            syncService.pullRemoteChanges()

            harness.waitFor(message = "B's dose lands locally with correct FK") {
                database.medicationDoseDao()
                    .getAllForMedOnce(medId)
                    .isNotEmpty()
            }
            val doses = database.medicationDoseDao()
                .getAllForMedOnce(medId)
            assertEquals(
                "exactly one dose tied to A's local medication after pull",
                1,
                doses.size
            )
            assertEquals(
                "dose's medicationId resolved to A's local row, not orphaned",
                medId,
                doses[0].medicationId
            )
        }
    }

    /**
     * Junction rebuild: B publishes a slot, then re-publishes the
     * medication with `slotCloudIds = [slot]`. A pulls and the
     * `medication_medication_slots` row reappears, even though A
     * never created the slot locally. Exercises the
     * pull-medications-after-slots ordering at
     * `SyncService.kt:1950–1980` and the junction rebuild at
     * `:2009–2027`.
     */
    @Test
    fun medicationSlotJunctionRebuildAfterRemoteSlotAdd() = runBlocking {
        withTimeout(TEST_TIMEOUT) {
            requireSignedIn()

            val medId = medicationRepository.insert(
                MedicationEntity(
                    name = "Vitamin D",
                    createdAt = 0L,
                    updatedAt = 0L
                )
            )
            syncService.pushLocalChanges()
            val medCloudId = database.syncMetadataDao().getCloudId(medId, "medication")
            assertNotNull(medCloudId)

            val slotCloudId = "slot-from-b"
            val nowMs = System.currentTimeMillis()
            harness.writeAsDeviceB(
                subcollection = "medication_slots",
                docId = slotCloudId,
                fields = mapOf(
                    "localId" to 7777L,
                    "name" to "Lunch",
                    "idealTime" to "12:30",
                    "driftMinutes" to 90,
                    "sortOrder" to 1,
                    "isActive" to true,
                    "createdAt" to nowMs,
                    "updatedAt" to nowMs
                )
            )
            harness.writeAsDeviceB(
                subcollection = "medications",
                docId = medCloudId!!,
                fields = mapOf(
                    "localId" to 9999L,
                    "name" to "Vitamin D",
                    "notes" to "",
                    "tier" to "essential",
                    "isArchived" to false,
                    "sortOrder" to 0,
                    "scheduleMode" to "TIMES_OF_DAY",
                    "dosesPerDay" to 1,
                    "pillsPerDose" to 1,
                    "reminderDaysBefore" to 3,
                    "slotCloudIds" to listOf(slotCloudId),
                    "createdAt" to 0L,
                    "updatedAt" to nowMs + 60_000L
                )
            )

            syncService.pullRemoteChanges()

            harness.waitFor(message = "junction row appears on A") {
                database.medicationSlotDao()
                    .getSlotsForMedicationOnce(medId)
                    .isNotEmpty()
            }
            val linkedSlots = database.medicationSlotDao()
                .getSlotsForMedicationOnce(medId)
            assertEquals(
                "exactly one junction link after pull",
                1,
                linkedSlots.size
            )
            assertEquals("Lunch", linkedSlots[0].name)
            assertTrue(
                "slot has its updated_at populated from B's write",
                linkedSlots[0].updatedAt > 0L
            )
        }
    }

    /**
     * Natural-key collision: A and B independently created a medication
     * with the same `name` while offline (e.g. both ran the v53→v54
     * backfill before signing in). When A pulls B's doc, the new cloud_id
     * is unknown to sync_metadata so the receive path takes the
     * `localId == null` branch — and `medications.name` is UNIQUE, so a
     * naive INSERT throws SQLiteConstraintException and the medication
     * is silently dropped (P0 surfaced in Test 3 of Session 1 manual
     * testing, 2026-04-27).
     *
     * Pin the dedup contract: after A pulls, A has exactly one row named
     * "Vitamin D" and sync_metadata resolves B's cloud_id to A's local
     * row. Convergence shape only — never assert which cloud_id "wins"
     * (memory `feedback_firestore_doc_iteration_order` — Firestore doc
     * iteration order flips between runs).
     */
    @Test
    fun medicationByName_dedupAcrossDevices() = runBlocking {
        withTimeout(TEST_TIMEOUT) {
            requireSignedIn()

            val localMedId = medicationRepository.insert(
                MedicationEntity(
                    name = "Vitamin D",
                    notes = "A's local copy from migration backfill",
                    createdAt = 0L,
                    updatedAt = 1_000L
                )
            )

            val nowMs = System.currentTimeMillis()
            val bCloudId = "med-from-b-independent-cloudid"
            harness.writeAsDeviceB(
                subcollection = "medications",
                docId = bCloudId,
                fields = mapOf(
                    "localId" to 9999L,
                    "name" to "Vitamin D",
                    "notes" to "B's local copy from migration backfill",
                    "tier" to "essential",
                    "isArchived" to false,
                    "sortOrder" to 0,
                    "scheduleMode" to "TIMES_OF_DAY",
                    "dosesPerDay" to 1,
                    "pillsPerDose" to 1,
                    "reminderDaysBefore" to 3,
                    "slotCloudIds" to emptyList<String>(),
                    "createdAt" to 0L,
                    "updatedAt" to nowMs
                )
            )

            // Must not throw. Pre-fix: SQLiteConstraintException: UNIQUE
            // constraint failed: medications.name surfaced via
            // pull.apply | medications=… | status=failed and the doc was
            // silently dropped.
            syncService.pullRemoteChanges()

            harness.waitFor(message = "B's cloud_id maps to A's local row") {
                database.syncMetadataDao().getLocalId(bCloudId, "medication") == localMedId
            }

            val byName = database.medicationDao().getByNameOnce("Vitamin D")
            assertNotNull("local medication still present after pull", byName)
            assertEquals(
                "exactly one medication row named Vitamin D after dedup",
                1,
                database.medicationDao().getAllOnce()
                    .count { it.name == "Vitamin D" }
            )
            assertEquals(
                "B's cloud_id resolves to A's local row id",
                localMedId,
                database.syncMetadataDao().getLocalId(bCloudId, "medication")
            )
            // B's updatedAt > A's local updatedAt, so last-write-wins
            // applied B's notes onto A's row (convergence shape — we do
            // NOT assert which cloud_id "wins" the metadata, only that
            // exactly one row exists and the metadata resolves to it).
            assertEquals(
                "last-write-wins applied B's payload onto adopted local row",
                "B's local copy from migration backfill",
                byName!!.notes
            )
        }
    }

    /**
     * Slot natural-key collision: A and B independently created a slot
     * named "Morning" while offline (e.g. both ran the built-in slot seed
     * before signing in). When A pulls B's doc, the new cloud_id is
     * unknown to sync_metadata and the receive path takes the
     * `localId == null` branch. medication_slots.name is NOT UNIQUE so
     * a naive INSERT does not throw — but it leaves two visible "Morning"
     * slots on A, doubling the ideal-time options in the UI.
     *
     * Pin the dedup contract: after A pulls, A has exactly one slot named
     * "Morning" and sync_metadata resolves B's cloud_id to A's local row.
     * Convergence shape only — never assert which cloud_id "wins" the
     * dedup (memory `feedback_firestore_doc_iteration_order`).
     */
    @Test
    fun medicationSlot_dedupByNameAcrossDevices() = runBlocking {
        withTimeout(TEST_TIMEOUT) {
            requireSignedIn()

            val localSlotId = database.medicationSlotDao().insert(
                com.averycorp.prismtask.data.local.entity.MedicationSlotEntity(
                    name = "Morning",
                    idealTime = "08:00",
                    createdAt = 0L,
                    updatedAt = 1_000L
                )
            )

            val nowMs = System.currentTimeMillis()
            val bCloudId = "slot-from-b-independent-cloudid"
            harness.writeAsDeviceB(
                subcollection = "medication_slots",
                docId = bCloudId,
                fields = mapOf(
                    "localId" to 7777L,
                    "name" to "Morning",
                    "idealTime" to "09:30",
                    "driftMinutes" to 90,
                    "sortOrder" to 0,
                    "isActive" to true,
                    "createdAt" to 0L,
                    "updatedAt" to nowMs
                )
            )

            // Must not produce a duplicate "Morning" slot. Pre-fix: the
            // pull insert succeeded (no UNIQUE constraint on name) and the
            // user saw two "Morning" slots in the time-of-day picker.
            syncService.pullRemoteChanges()

            harness.waitFor(message = "B's cloud_id maps to A's local row") {
                database.syncMetadataDao().getLocalId(bCloudId, "medication_slot") == localSlotId
            }

            assertEquals(
                "exactly one slot named Morning after dedup",
                1,
                database.medicationSlotDao().getAllOnce()
                    .count { it.name == "Morning" }
            )
            assertEquals(
                "B's cloud_id resolves to A's local row id",
                localSlotId,
                database.syncMetadataDao().getLocalId(bCloudId, "medication_slot")
            )
            // B's updatedAt > A's local updatedAt, so last-write-wins
            // applied B's idealTime onto A's row.
            assertEquals(
                "last-write-wins applied B's payload onto adopted local row",
                "09:30",
                database.medicationSlotDao().getByIdOnce(localSlotId)!!.idealTime
            )
        }
    }

    /**
     * Slot-override natural-key collision: A and B independently created
     * an override for the same `(medication, slot)` pair. When A pulls B's
     * doc, the cloud_id is unknown to sync_metadata so the receive path
     * takes the `localId == null` branch — and `medication_slot_overrides`
     * has UNIQUE(medication_id, slot_id), so a naive INSERT throws
     * SQLiteConstraintException and the override is silently dropped.
     *
     * Pin the dedup contract: after A pulls, A has exactly one override
     * for the pair and sync_metadata resolves B's cloud_id to A's local
     * row. Convergence shape only.
     */
    @Test
    fun medicationSlotOverride_dedupAcrossDevices() = runBlocking {
        withTimeout(TEST_TIMEOUT) {
            requireSignedIn()

            // Seed shared parent rows on both sides via push: A creates
            // them locally, pushes, then we use those cloud_ids when B
            // writes its override doc.
            val medId = medicationRepository.insert(
                MedicationEntity(
                    name = "Methylphenidate",
                    createdAt = 0L,
                    updatedAt = 0L
                )
            )
            val slotId = medicationSlotRepository.insertSlot(
                com.averycorp.prismtask.data.local.entity.MedicationSlotEntity(
                    name = "Lunch",
                    idealTime = "12:00",
                    createdAt = 0L,
                    updatedAt = 0L
                )
            )
            syncService.pushLocalChanges()
            val medCloudId = database.syncMetadataDao().getCloudId(medId, "medication")
            val slotCloudId = database.syncMetadataDao().getCloudId(slotId, "medication_slot")
            assertNotNull("med cloud_id populated", medCloudId)
            assertNotNull("slot cloud_id populated", slotCloudId)

            val localOverrideId = database.medicationSlotOverrideDao().insert(
                com.averycorp.prismtask.data.local.entity.MedicationSlotOverrideEntity(
                    medicationId = medId,
                    slotId = slotId,
                    overrideIdealTime = "12:30",
                    overrideDriftMinutes = 30,
                    createdAt = 0L,
                    updatedAt = 1_000L
                )
            )

            val nowMs = System.currentTimeMillis()
            val bOverrideCloudId = "override-from-b-independent-cloudid"
            harness.writeAsDeviceB(
                subcollection = "medication_slot_overrides",
                docId = bOverrideCloudId,
                fields = mapOf(
                    "localId" to 6666L,
                    "medicationCloudId" to medCloudId!!,
                    "slotCloudId" to slotCloudId!!,
                    "overrideIdealTime" to "13:00",
                    "overrideDriftMinutes" to 60,
                    "createdAt" to 0L,
                    "updatedAt" to nowMs
                )
            )

            // Must not throw. Pre-fix: SQLiteConstraintException: UNIQUE
            // constraint failed: medication_slot_overrides.medication_id,
            // medication_slot_overrides.slot_id surfaced via
            // pull.apply | medication_slot_overrides=… | status=failed
            // and the doc was silently dropped.
            syncService.pullRemoteChanges()

            harness.waitFor(message = "B's cloud_id maps to A's local row") {
                database.syncMetadataDao()
                    .getLocalId(bOverrideCloudId, "medication_slot_override") == localOverrideId
            }

            assertEquals(
                "exactly one override row for the pair after dedup",
                1,
                database.medicationSlotOverrideDao().getForMedicationOnce(medId)
                    .count { it.slotId == slotId }
            )
            assertEquals(
                "B's cloud_id resolves to A's local row id",
                localOverrideId,
                database.syncMetadataDao()
                    .getLocalId(bOverrideCloudId, "medication_slot_override")
            )
            assertEquals(
                "last-write-wins applied B's payload onto adopted local row",
                "13:00",
                database.medicationSlotOverrideDao().getByIdOnce(localOverrideId)!!.overrideIdealTime
            )
        }
    }

    /**
     * Tier-state natural-key collision: A and B independently logged a
     * tier for the same `(medication, slot, day)` triple. When A pulls
     * B's doc, the cloud_id is unknown so the receive path takes the
     * `localId == null` branch — and `medication_tier_states` has
     * UNIQUE(medication_id, log_date, slot_id), so a naive INSERT throws
     * SQLiteConstraintException and the tier-state is silently dropped.
     *
     * Pin the dedup contract: after A pulls, A has exactly one tier-state
     * for the triple and sync_metadata resolves B's cloud_id to A's local
     * row. Convergence shape only.
     */
    @Test
    fun medicationTierState_dedupAcrossDevices() = runBlocking {
        withTimeout(TEST_TIMEOUT) {
            requireSignedIn()

            val medId = medicationRepository.insert(
                MedicationEntity(
                    name = "Sertraline",
                    createdAt = 0L,
                    updatedAt = 0L
                )
            )
            val slotId = medicationSlotRepository.insertSlot(
                com.averycorp.prismtask.data.local.entity.MedicationSlotEntity(
                    name = "Bedtime",
                    idealTime = "22:00",
                    createdAt = 0L,
                    updatedAt = 0L
                )
            )
            syncService.pushLocalChanges()
            val medCloudId = database.syncMetadataDao().getCloudId(medId, "medication")
            val slotCloudId = database.syncMetadataDao().getCloudId(slotId, "medication_slot")
            assertNotNull("med cloud_id populated", medCloudId)
            assertNotNull("slot cloud_id populated", slotCloudId)

            val logDate = "2026-04-25"
            val localTierStateId = database.medicationTierStateDao().insert(
                com.averycorp.prismtask.data.local.entity.MedicationTierStateEntity(
                    medicationId = medId,
                    slotId = slotId,
                    logDate = logDate,
                    tier = "essential",
                    tierSource = "computed",
                    loggedAt = 1_000L,
                    createdAt = 0L,
                    updatedAt = 1_000L
                )
            )

            val nowMs = System.currentTimeMillis()
            val bTierCloudId = "tier-from-b-independent-cloudid"
            harness.writeAsDeviceB(
                subcollection = "medication_tier_states",
                docId = bTierCloudId,
                fields = mapOf(
                    "localId" to 5555L,
                    "medicationCloudId" to medCloudId!!,
                    "slotCloudId" to slotCloudId!!,
                    "logDate" to logDate,
                    "tier" to "complete",
                    "tierSource" to "user_set",
                    "intendedTime" to nowMs,
                    "loggedAt" to nowMs,
                    "createdAt" to 0L,
                    "updatedAt" to nowMs
                )
            )

            // Must not throw. Pre-fix: SQLiteConstraintException: UNIQUE
            // constraint failed: medication_tier_states.medication_id,
            // medication_tier_states.log_date, medication_tier_states.slot_id
            // and the doc was silently dropped.
            syncService.pullRemoteChanges()

            harness.waitFor(message = "B's cloud_id maps to A's local row") {
                database.syncMetadataDao()
                    .getLocalId(bTierCloudId, "medication_tier_state") == localTierStateId
            }

            assertEquals(
                "exactly one tier-state row for the triple after dedup",
                1,
                database.medicationTierStateDao().getForDateOnce(logDate)
                    .count { it.medicationId == medId && it.slotId == slotId }
            )
            assertEquals(
                "B's cloud_id resolves to A's local row id",
                localTierStateId,
                database.syncMetadataDao()
                    .getLocalId(bTierCloudId, "medication_tier_state")
            )
            assertEquals(
                "last-write-wins applied B's payload onto adopted local row",
                "complete",
                database.medicationTierStateDao().getByIdOnce(localTierStateId)!!.tier
            )
        }
    }

    companion object {
        private val TEST_TIMEOUT = 90.seconds
    }
}
