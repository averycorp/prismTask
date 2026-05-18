package com.averycorp.prismtask.ui.screens.medication

import com.averycorp.prismtask.core.time.LocalDateFlow
import com.averycorp.prismtask.data.local.entity.MedicationDoseEntity
import com.averycorp.prismtask.data.local.entity.MedicationEntity
import com.averycorp.prismtask.data.local.entity.MedicationSlotEntity
import com.averycorp.prismtask.data.preferences.StartOfDay
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.remote.api.ProposedMutationResponse
import com.averycorp.prismtask.data.repository.BatchOperationsRepository
import com.averycorp.prismtask.data.repository.MedicationRepository
import com.averycorp.prismtask.data.repository.MedicationSlotRepository
import com.averycorp.prismtask.domain.model.medication.AchievedTier
import com.averycorp.prismtask.notifications.MedicationClockRescheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for `MedicationViewModel.applyTierAtTime` — the long-press-tier
 * → pick-time flow. Save composes three writes (backdate existing doses,
 * bulk-mark with the picked time, persist intended_time) so the visible
 * "Taken at HH:mm" labels actually move when the user picks a time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MedicationViewModelApplyTierAtTimeTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var medicationRepository: MedicationRepository
    private lateinit var slotRepository: MedicationSlotRepository
    private lateinit var taskBehaviorPreferences: TaskBehaviorPreferences
    private lateinit var batchOperationsRepository: BatchOperationsRepository
    private lateinit var localDateFlow: LocalDateFlow
    private lateinit var clockRescheduler: MedicationClockRescheduler

    private val today = "2026-05-18"

    private val morningSlot = MedicationSlotEntity(
        id = 10L,
        name = "Morning",
        idealTime = "09:00",
        driftMinutes = 60
    )

    private val essMed = MedicationEntity(id = 1L, name = "Vitamin D", tier = "essential")
    private val rxMed = MedicationEntity(id = 2L, name = "Statin", tier = "prescription")

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

        every { medicationRepository.observeActive() } returns
            flowOf(listOf(essMed, rxMed))
        every { slotRepository.observeActiveSlots() } returns flowOf(listOf(morningSlot))
        every { medicationRepository.observeDosesForDate(any()) } returns flowOf(emptyList())
        every { slotRepository.observeTierStatesForDate(any()) } returns flowOf(emptyList())

        coEvery { slotRepository.getMedicationIdsForSlotOnce(morningSlot.id) } returns
            listOf(essMed.id, rxMed.id)

        coEvery {
            batchOperationsRepository.applyBatch(any(), any())
        } returns BatchOperationsRepository.BatchApplyResult(
            batchId = "test-batch",
            commandText = "test",
            appliedCount = 0,
            skipped = emptyList()
        )
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

    private fun CoroutineScope.warmStateFlows(vm: MedicationViewModel) {
        launch { vm.slotTodayStates.collect {} }
        launch { vm.todayDate.collect {} }
    }

    @Test
    fun applyTierAtTime_insertsNewDosesAtPickedWallClock() = runTest(dispatcher) {
        // No prior doses → bulkMark should insert real-dose mutations for
        // every med at or below the picked tier. The mutation's `taken_at`
        // MUST be the user-picked time, not `now`, so the per-med "Taken
        // at HH:mm" label renders the backdated wall-clock.
        val captured = slot<List<ProposedMutationResponse>>()
        coEvery {
            batchOperationsRepository.applyBatch(any(), capture(captured))
        } returns BatchOperationsRepository.BatchApplyResult("b", "c", 2, emptyList())

        val vm = newViewModel()
        backgroundScope.warmStateFlows(vm)
        advanceUntilIdle()

        val picked = 1_715_000_000_000L
        vm.applyTierAtTime(morningSlot, AchievedTier.PRESCRIPTION, picked)
        advanceUntilIdle()

        val mutations = captured.captured
        assertTrue("Should produce mutations for the eligible meds", mutations.isNotEmpty())
        assertTrue(
            "Every COMPLETE mutation must carry the user-picked taken_at, not System.currentTimeMillis",
            mutations.all { it.proposedNewValues["taken_at"] == picked }
        )
    }

    @Test
    fun applyTierAtTime_updatesExistingDoseTakenAtToPickedTime() = runTest(dispatcher) {
        // Essential med is already logged — long-pressing a tier with a
        // backdated time should rewrite that dose's `taken_at` to match,
        // so the visible "Taken at HH:mm" label moves. Without this the
        // user perceives "the time didn't change".
        val existing = MedicationDoseEntity(
            id = 99L,
            medicationId = essMed.id,
            slotKey = morningSlot.id.toString(),
            takenAt = 0L,
            takenDateLocal = today
        )
        every { medicationRepository.observeDosesForDate(any()) } returns
            flowOf(listOf(existing))

        val vm = newViewModel()
        backgroundScope.warmStateFlows(vm)
        advanceUntilIdle()

        val picked = 1_715_000_000_000L
        vm.applyTierAtTime(morningSlot, AchievedTier.PRESCRIPTION, picked)
        advanceUntilIdle()

        coVerify {
            medicationRepository.updateDose(
                match { it.id == existing.id && it.takenAt == picked }
            )
        }
    }

    @Test
    fun applyTierAtTime_leavesSyntheticSkipDosesAlone() = runTest(dispatcher) {
        // Synthetic-skip rows aren't user-visible "taken" times — they're
        // interval-reschedule anchors. Backdating must not touch them.
        val syntheticSkip = MedicationDoseEntity(
            id = 99L,
            medicationId = essMed.id,
            slotKey = morningSlot.id.toString(),
            takenAt = 0L,
            takenDateLocal = today,
            isSyntheticSkip = true
        )
        every { medicationRepository.observeDosesForDate(any()) } returns
            flowOf(listOf(syntheticSkip))

        val vm = newViewModel()
        backgroundScope.warmStateFlows(vm)
        advanceUntilIdle()

        vm.applyTierAtTime(morningSlot, AchievedTier.PRESCRIPTION, 1_715_000_000_000L)
        advanceUntilIdle()

        coVerify(exactly = 0) {
            medicationRepository.updateDose(match { it.id == syntheticSkip.id })
        }
    }

    @Test
    fun applyTierAtTime_persistsIntendedTimeOnTierStateRows() = runTest(dispatcher) {
        // The backlog clock icon depends on `intended_time` diverging from
        // `logged_at` by >60s. Long-press → pick time must persist that
        // field for every per-med tier-state row in the slot.
        val vm = newViewModel()
        backgroundScope.warmStateFlows(vm)
        advanceUntilIdle()

        val picked = 1_715_000_000_000L
        vm.applyTierAtTime(morningSlot, AchievedTier.PRESCRIPTION, picked)
        advanceUntilIdle()

        coVerify {
            slotRepository.setTierStateIntendedTime(
                medicationId = essMed.id,
                slotId = morningSlot.id,
                date = today,
                intendedTime = picked
            )
            slotRepository.setTierStateIntendedTime(
                medicationId = rxMed.id,
                slotId = morningSlot.id,
                date = today,
                intendedTime = picked
            )
        }
    }

    @Test
    fun applyTierAtTime_doesNotRewriteExistingDoseIfTakenAtAlreadyMatches() = runTest(dispatcher) {
        // No-op write-back on doses that already carry the picked time —
        // avoids spurious sync churn when the user re-saves the same time.
        val picked = 1_715_000_000_000L
        val existing = MedicationDoseEntity(
            id = 99L,
            medicationId = essMed.id,
            slotKey = morningSlot.id.toString(),
            takenAt = picked,
            takenDateLocal = today
        )
        every { medicationRepository.observeDosesForDate(any()) } returns
            flowOf(listOf(existing))

        val vm = newViewModel()
        backgroundScope.warmStateFlows(vm)
        advanceUntilIdle()

        vm.applyTierAtTime(morningSlot, AchievedTier.PRESCRIPTION, picked)
        advanceUntilIdle()

        coVerify(exactly = 0) { medicationRepository.updateDose(any()) }
    }

    @Test
    fun bulkMarkInternal_takenAtOverride_winsOverNowOnCompleteMutations() = runTest(dispatcher) {
        // Direct contract test on the public override hook used by
        // applyTierAtTime: the picked `taken_at` must land on every
        // emitted COMPLETE mutation, regardless of the wall-clock at
        // call time.
        val captured = slot<List<ProposedMutationResponse>>()
        coEvery {
            batchOperationsRepository.applyBatch(any(), capture(captured))
        } returns BatchOperationsRepository.BatchApplyResult("b", "c", 2, emptyList())

        val vm = newViewModel()
        backgroundScope.warmStateFlows(vm)
        advanceUntilIdle()

        val picked = 1_700_000_000_000L
        vm.bulkMarkInternal(
            scope = com.averycorp.prismtask.domain.model.medication.BulkMarkScope.SLOT,
            slotId = morningSlot.id,
            tier = AchievedTier.PRESCRIPTION,
            takenAtOverride = picked
        )
        advanceUntilIdle()

        assertEquals(
            "Every COMPLETE mutation should carry the override",
            picked,
            captured.captured.first().proposedNewValues["taken_at"]
        )
    }
}
