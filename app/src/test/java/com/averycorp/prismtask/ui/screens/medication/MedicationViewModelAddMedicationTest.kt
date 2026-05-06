package com.averycorp.prismtask.ui.screens.medication

import app.cash.turbine.test
import com.averycorp.prismtask.core.time.LocalDateFlow
import com.averycorp.prismtask.data.local.entity.MedicationEntity
import com.averycorp.prismtask.data.local.entity.MedicationSlotEntity
import com.averycorp.prismtask.data.preferences.StartOfDay
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.repository.BatchOperationsRepository
import com.averycorp.prismtask.data.repository.MedicationRepository
import com.averycorp.prismtask.data.repository.MedicationSlotRepository
import com.averycorp.prismtask.domain.model.medication.MedicationTier
import com.averycorp.prismtask.notifications.MedicationClockRescheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Regression coverage for the D_MEDICATION_ADD_CRASH audit
 * (`docs/audits/D_MEDICATION_ADD_CRASH_AUDIT.md`). The original code path
 * fired its insert through an uncaught `viewModelScope.launch`, so any
 * exception inside the coroutine — most plausibly a duplicate-name
 * `SQLiteConstraintException` from `OnConflictStrategy.ABORT` on the
 * `medications.name` unique index — crashed the app instead of surfacing
 * a recoverable error. These tests pin the post-fix invariants:
 *
 *  - active duplicate-name → emit a friendly error, no insert
 *  - archived duplicate-name → unarchive + update, no insert
 *  - repository throws → emit a friendly error, do not propagate
 *  - empty `slotSelections` → insert succeeds with empty link set
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MedicationViewModelAddMedicationTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var medicationRepository: MedicationRepository
    private lateinit var slotRepository: MedicationSlotRepository
    private lateinit var taskBehaviorPreferences: TaskBehaviorPreferences
    private lateinit var batchOperationsRepository: BatchOperationsRepository
    private lateinit var localDateFlow: LocalDateFlow
    private lateinit var clockRescheduler: MedicationClockRescheduler

    private val today = "2026-05-06"

    private val morningSlot = MedicationSlotEntity(
        id = 10L,
        name = "Morning",
        idealTime = "09:00",
        driftMinutes = 60
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        medicationRepository = mockk(relaxed = true)
        slotRepository = mockk(relaxed = true)
        taskBehaviorPreferences = mockk(relaxed = true)
        batchOperationsRepository = mockk(relaxed = true)
        localDateFlow = mockk(relaxed = true)
        clockRescheduler = mockk(relaxed = true)

        every { taskBehaviorPreferences.getStartOfDay() } returns
            MutableStateFlow(StartOfDay(hour = 0, minute = 0, hasBeenSet = true))
        every { localDateFlow.observeIsoString(any()) } returns flowOf(today)
        every { medicationRepository.observeActive() } returns flowOf(emptyList())
        every { slotRepository.observeActiveSlots() } returns flowOf(listOf(morningSlot))
        every { medicationRepository.observeDosesForDate(any()) } returns flowOf(emptyList())
        every { slotRepository.observeTierStatesForDate(any()) } returns flowOf(emptyList())

        // Default: no name collision and no rescheduler exception.
        coEvery { medicationRepository.getByNameOnce(any()) } returns null
        coEvery { medicationRepository.insert(any()) } returns 42L
        coEvery { medicationRepository.getByIdOnce(42L) } returns
            MedicationEntity(id = 42L, name = "Lamotrigine 200mg", tier = "essential")
        coEvery { clockRescheduler.rescheduleAll() } returns Unit
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() = MedicationViewModel(
        medicationRepository = medicationRepository,
        slotRepository = slotRepository,
        taskBehaviorPreferences = taskBehaviorPreferences,
        batchOperationsRepository = batchOperationsRepository,
        localDateFlow = localDateFlow,
        clockRescheduler = clockRescheduler
    )

    @Test
    fun addMedication_withDuplicateActiveName_emitsErrorAndSkipsInsert() = runTest(dispatcher) {
        val existing = MedicationEntity(id = 7L, name = "Lamotrigine 200mg", tier = "essential")
        coEvery { medicationRepository.getByNameOnce("Lamotrigine 200mg") } returns existing
        val vm = newViewModel()

        vm.errorMessages.test {
            vm.addMedication(
                name = "Lamotrigine 200mg",
                tier = MedicationTier.ESSENTIAL,
                notes = "",
                slotSelections = emptyList()
            )
            advanceUntilIdle()
            val msg = awaitItem()
            assertEquals(
                "A medication named \"Lamotrigine 200mg\" already exists. " +
                    "Edit it instead, or pick a different name.",
                msg
            )
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 0) { medicationRepository.insert(any()) }
    }

    @Test
    fun addMedication_withArchivedDuplicateName_unarchivesInsteadOfInserting() = runTest(dispatcher) {
        val archived = MedicationEntity(
            id = 99L,
            name = "Lamotrigine 200mg",
            tier = "essential",
            isArchived = true,
            notes = "old note"
        )
        coEvery { medicationRepository.getByNameOnce("Lamotrigine 200mg") } returns archived
        coEvery { medicationRepository.getByIdOnce(99L) } returns archived.copy(isArchived = false)
        val vm = newViewModel()

        vm.addMedication(
            name = "Lamotrigine 200mg",
            tier = MedicationTier.PRESCRIPTION,
            notes = "fresh note",
            slotSelections = emptyList()
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { medicationRepository.insert(any()) }
        coVerify {
            medicationRepository.update(
                match {
                    it.id == 99L &&
                        !it.isArchived &&
                        it.notes == "fresh note" &&
                        it.tier == "prescription"
                }
            )
        }
        // Junction is rebuilt against the unarchived row's id, not a fresh
        // insert id — confirms we routed through the unarchive branch.
        coVerify { slotRepository.replaceLinksForMedication(99L, emptyList()) }
    }

    @Test
    fun addMedication_repositoryInsertThrows_emitsErrorWithoutCrashing() = runTest(dispatcher) {
        coEvery { medicationRepository.getByNameOnce(any()) } returns null
        // Mirror the SQLiteConstraintException message Room would surface
        // for a unique-name collision. Tests at the unit-test layer use a
        // plain RuntimeException to avoid pulling android.database.sqlite
        // onto the JVM classpath.
        coEvery { medicationRepository.insert(any()) } throws
            RuntimeException("UNIQUE constraint failed: medications.name")
        val vm = newViewModel()

        vm.errorMessages.test {
            vm.addMedication(
                name = "Lamotrigine 200mg",
                tier = MedicationTier.ESSENTIAL,
                notes = "",
                slotSelections = emptyList()
            )
            advanceUntilIdle()
            val msg = awaitItem()
            assertEquals("Couldn't save medication. Please try again.", msg)
            cancelAndIgnoreRemainingEvents()
        }
        // Belt-and-braces: the rescheduler is not invoked when insert fails.
        coVerify(exactly = 0) { clockRescheduler.rescheduleAll() }
    }

    @Test
    fun addMedication_emptySelections_persistsWithEmptyLinks() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.addMedication(
            name = "Lamotrigine 200mg",
            tier = MedicationTier.ESSENTIAL,
            notes = "",
            slotSelections = emptyList()
        )
        advanceUntilIdle()

        coVerify { medicationRepository.insert(any()) }
        coVerify { slotRepository.replaceLinksForMedication(42L, emptyList()) }
        coVerify { clockRescheduler.rescheduleAll() }
    }

    @Test
    fun addMedication_blankName_isNoOpAndDoesNotEmit() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.addMedication(
            name = "   ",
            tier = MedicationTier.ESSENTIAL,
            notes = "",
            slotSelections = emptyList()
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { medicationRepository.getByNameOnce(any()) }
        coVerify(exactly = 0) { medicationRepository.insert(any()) }
    }

    @Test
    fun updateMedication_renameToCollidingActiveName_emitsErrorAndSkipsUpdate() = runTest(dispatcher) {
        val current = MedicationEntity(id = 5L, name = "Old Name", tier = "essential")
        val collision = MedicationEntity(id = 6L, name = "Lamotrigine 200mg", tier = "essential")
        coEvery { medicationRepository.getByNameOnce("Lamotrigine 200mg") } returns collision
        val vm = newViewModel()

        vm.errorMessages.test {
            vm.updateMedication(
                medication = current,
                name = "Lamotrigine 200mg",
                tier = MedicationTier.ESSENTIAL,
                notes = "",
                slotSelections = emptyList()
            )
            advanceUntilIdle()
            val msg = awaitItem()
            assertEquals(
                "A medication named \"Lamotrigine 200mg\" already exists. " +
                    "Edit it instead, or pick a different name.",
                msg
            )
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 0) { medicationRepository.update(any()) }
    }

    @Test
    fun recordUnslottedDose_insertsAnytimeDoseRow() = runTest(dispatcher) {
        val med = MedicationEntity(id = 7L, name = "Ibuprofen", tier = "essential")
        val vm = newViewModel()

        vm.recordUnslottedDose(med)
        advanceUntilIdle()

        coVerify {
            medicationRepository.logDose(
                medicationId = 7L,
                slotKey = "anytime",
                takenAt = any(),
                doseAmount = null
            )
        }
    }

    @Test
    fun recordUnslottedDose_threadsDoseAmountWhenProvided() = runTest(dispatcher) {
        val med = MedicationEntity(
            id = 8L,
            name = "Ibuprofen",
            tier = "essential",
            promptDoseAtLog = true
        )
        val vm = newViewModel()

        vm.recordUnslottedDose(med, doseAmount = "400 mg")
        advanceUntilIdle()

        coVerify {
            medicationRepository.logDose(
                medicationId = 8L,
                slotKey = "anytime",
                takenAt = any(),
                doseAmount = "400 mg"
            )
        }
    }

    @Test
    fun recordUnslottedDose_repositoryThrows_emitsErrorWithoutCrashing() = runTest(dispatcher) {
        val med = MedicationEntity(id = 9L, name = "Ibuprofen", tier = "essential")
        coEvery {
            medicationRepository.logDose(any(), any(), any(), any(), any())
        } throws RuntimeException("disk full")
        val vm = newViewModel()

        vm.errorMessages.test {
            vm.recordUnslottedDose(med)
            advanceUntilIdle()
            assertEquals("Couldn't record dose. Please try again.", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun addMedication_persistsPromptDoseAtLogToggle() = runTest(dispatcher) {
        val vm = newViewModel()

        vm.addMedication(
            name = "Lamotrigine 200mg",
            tier = MedicationTier.ESSENTIAL,
            notes = "",
            slotSelections = emptyList(),
            promptDoseAtLog = true
        )
        advanceUntilIdle()

        coVerify {
            medicationRepository.insert(match { it.promptDoseAtLog })
        }
    }

    @Test
    fun updateMedication_renameToOwnName_skipsCollisionCheck() = runTest(dispatcher) {
        // When the user opens the edit dialog and saves without changing
        // the name, we must NOT treat the row as colliding with itself.
        val current = MedicationEntity(id = 5L, name = "Lamotrigine 200mg", tier = "essential")
        coEvery { medicationRepository.getByNameOnce(any()) } returns current
        val vm = newViewModel()

        vm.updateMedication(
            medication = current,
            name = "Lamotrigine 200mg",
            tier = MedicationTier.ESSENTIAL,
            notes = "fresh note",
            slotSelections = emptyList()
        )
        advanceUntilIdle()

        coVerify {
            medicationRepository.update(
                match {
                    it.id == 5L && it.notes == "fresh note"
                }
            )
        }
        // Self-update path skips the name lookup entirely.
        coVerify(exactly = 0) { medicationRepository.getByNameOnce(any()) }
    }
}
