package com.averycorp.prismtask.ui.screens.medication

import com.averycorp.prismtask.core.time.LocalDateFlow
import com.averycorp.prismtask.data.local.entity.MedicationDoseEntity
import com.averycorp.prismtask.data.local.entity.MedicationEntity
import com.averycorp.prismtask.data.local.entity.MedicationSlotEntity
import com.averycorp.prismtask.data.preferences.StartOfDay
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.repository.BatchOperationsRepository
import com.averycorp.prismtask.data.repository.MedicationRepository
import com.averycorp.prismtask.data.repository.MedicationSlotRepository
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
import org.junit.Before
import org.junit.Test

/**
 * Covers the long-press dose time-edit ViewModel functions:
 *  - retimeDose: updates an existing dose's takenAt + takenDateLocal,
 *    refreshes tier state for slot-anchored doses, skips refresh for
 *    unscheduled (`slotKey = "anytime"`) doses.
 *  - removeDose: deletes a dose row, refreshes tier state for slot
 *    rows only.
 *  - logDoseAtTime: writes a fresh dose at a user-picked past time,
 *    using the provided slot id stringified or `anytime` for unscheduled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MedicationViewModelDoseTimeEditTest {
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

    private val med = MedicationEntity(id = 1L, name = "Vitamin D", tier = "essential")

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
        every { taskBehaviorPreferences.getDayStartHour() } returns flowOf(0)
        every { localDateFlow.observeIsoString(any()) } returns flowOf(today)

        every { medicationRepository.observeActive() } returns flowOf(listOf(med))
        every { slotRepository.observeActiveSlots() } returns flowOf(listOf(morningSlot))
        every { medicationRepository.observeDosesForDate(any()) } returns flowOf(emptyList())
        every { slotRepository.observeTierStatesForDate(any()) } returns flowOf(emptyList())

        coEvery { slotRepository.getMedicationIdsForSlotOnce(morningSlot.id) } returns
            listOf(med.id)
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

    /**
     * Public StateFlows use `WhileSubscribed(5_000L)` so `.value` stays
     * empty until something collects. `refreshTierState` reads
     * `medications.value`; keep a live subscription open via
     * `slotTodayStates` (combine over `medications` + `activeSlots` +
     * others) so the upstream is warm.
     */
    private fun CoroutineScope.warmStateFlows(vm: MedicationViewModel) {
        launch { vm.slotTodayStates.collect {} }
        launch { vm.todayDate.collect {} }
    }

    private fun slotDose(takenAt: Long, slotKey: String = morningSlot.id.toString()) =
        MedicationDoseEntity(
            id = 99L,
            medicationId = med.id,
            slotKey = slotKey,
            takenAt = takenAt,
            takenDateLocal = "2026-05-17"
        )

    @Test
    fun retimeDose_slotAnchored_updatesTakenAtAndRefreshesTier() = runTest(dispatcher) {
        val vm = newViewModel()
        backgroundScope.warmStateFlows(vm)
        advanceUntilIdle()
        val existing = slotDose(takenAt = 1_700_000_000_000L)
        val newTakenAt = 1_700_010_000_000L

        val captured = slot<MedicationDoseEntity>()
        coEvery { medicationRepository.updateDose(capture(captured)) } returns Unit

        vm.retimeDose(existing, newTakenAt)
        advanceUntilIdle()

        assertEquals(newTakenAt, captured.captured.takenAt)
        // takenDateLocal is recomputed from DayBoundary against the new
        // takenAt — verify it no longer matches the entity's seed value.
        assert(captured.captured.takenDateLocal != existing.takenDateLocal) {
            "takenDateLocal should be recomputed; seed=${existing.takenDateLocal} " +
                "captured=${captured.captured.takenDateLocal}"
        }
        // Slot-anchored → tier state refreshes (upsertTierState fires).
        coVerify(atLeast = 1) {
            slotRepository.upsertTierState(
                medicationId = med.id,
                slotId = morningSlot.id,
                date = any(),
                tier = any(),
                source = any()
            )
        }
    }

    @Test
    fun retimeDose_anytimeSlot_skipsTierRefresh() = runTest(dispatcher) {
        val vm = newViewModel()
        val existing = slotDose(takenAt = 1_700_000_000_000L, slotKey = "anytime")
        val newTakenAt = 1_700_010_000_000L

        vm.retimeDose(existing, newTakenAt)
        advanceUntilIdle()

        coVerify { medicationRepository.updateDose(any()) }
        coVerify(exactly = 0) {
            slotRepository.upsertTierState(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun removeDose_slotAnchored_callsUnlogAndRefreshes() = runTest(dispatcher) {
        val vm = newViewModel()
        backgroundScope.warmStateFlows(vm)
        advanceUntilIdle()
        val existing = slotDose(takenAt = 1_700_000_000_000L)

        vm.removeDose(existing)
        advanceUntilIdle()

        coVerify { medicationRepository.unlogDose(existing) }
        coVerify(atLeast = 1) {
            slotRepository.upsertTierState(
                medicationId = med.id,
                slotId = morningSlot.id,
                date = any(),
                tier = any(),
                source = any()
            )
        }
    }

    @Test
    fun removeDose_anytimeSlot_skipsTierRefresh() = runTest(dispatcher) {
        val vm = newViewModel()
        val existing = slotDose(takenAt = 1_700_000_000_000L, slotKey = "anytime")

        vm.removeDose(existing)
        advanceUntilIdle()

        coVerify { medicationRepository.unlogDose(existing) }
        coVerify(exactly = 0) {
            slotRepository.upsertTierState(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun logDoseAtTime_withSlot_writesDoseAndRefreshes() = runTest(dispatcher) {
        val vm = newViewModel()
        backgroundScope.warmStateFlows(vm)
        advanceUntilIdle()
        val takenAt = 1_700_005_000_000L

        coEvery {
            medicationRepository.logDose(any(), any(), any(), any(), any())
        } returns 42L

        vm.logDoseAtTime(med, morningSlot, takenAt)
        advanceUntilIdle()

        coVerify {
            medicationRepository.logDose(
                medicationId = med.id,
                slotKey = morningSlot.id.toString(),
                takenAt = takenAt,
                note = any(),
                doseAmount = null
            )
        }
        coVerify(atLeast = 1) {
            slotRepository.upsertTierState(
                medicationId = med.id,
                slotId = morningSlot.id,
                date = any(),
                tier = any(),
                source = any()
            )
        }
    }

    @Test
    fun logDoseAtTime_withoutSlot_usesAnytimeKeyAndSkipsRefresh() = runTest(dispatcher) {
        val vm = newViewModel()
        val takenAt = 1_700_005_000_000L

        coEvery {
            medicationRepository.logDose(any(), any(), any(), any(), any())
        } returns 42L

        vm.logDoseAtTime(med, slot = null, takenAt = takenAt)
        advanceUntilIdle()

        coVerify {
            medicationRepository.logDose(
                medicationId = med.id,
                slotKey = "anytime",
                takenAt = takenAt,
                note = any(),
                doseAmount = null
            )
        }
        coVerify(exactly = 0) {
            slotRepository.upsertTierState(any(), any(), any(), any(), any())
        }
    }
}
