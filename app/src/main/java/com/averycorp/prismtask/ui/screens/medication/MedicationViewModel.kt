package com.averycorp.prismtask.ui.screens.medication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.core.time.LocalDateFlow
import com.averycorp.prismtask.data.local.entity.MedicationDoseEntity
import com.averycorp.prismtask.data.local.entity.MedicationEntity
import com.averycorp.prismtask.data.local.entity.MedicationSlotEntity
import com.averycorp.prismtask.data.local.entity.MedicationTierStateEntity
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.remote.api.ProposedMutationResponse
import com.averycorp.prismtask.data.repository.BatchOperationsRepository
import com.averycorp.prismtask.data.repository.MedicationRepository
import com.averycorp.prismtask.data.repository.MedicationSlotRepository
import com.averycorp.prismtask.domain.model.BatchEntityType
import com.averycorp.prismtask.domain.model.BatchMutationType
import com.averycorp.prismtask.domain.model.medication.AchievedTier
import com.averycorp.prismtask.domain.model.medication.BulkMarkScope
import com.averycorp.prismtask.domain.model.medication.MedicationTier
import com.averycorp.prismtask.domain.model.medication.TierSource
import com.averycorp.prismtask.domain.usecase.MedicationTierComputer
import com.averycorp.prismtask.notifications.MedicationClockRescheduler
import com.averycorp.prismtask.ui.screens.medication.components.MedicationSlotSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * Surface the Main Medication screen needs to render one row per slot per
 * day with auto-computed achieved tier + per-medication toggle affordance.
 */
data class MedicationSlotTodayState(
    val slot: MedicationSlotEntity,
    val medications: List<MedicationEntity>,
    val takenMedicationIds: Set<Long>,
    val achievedTier: AchievedTier,
    val isUserSet: Boolean,
    /**
     * User-claimed wall-clock for when the slot was actually taken, if
     * the user has explicitly set it via long-press. Sourced from the
     * first per-slot tier-state row (all rows for the slot carry the
     * same intended_time). NULL means no user override — UI should
     * treat the row's logged_at as the de-facto taken time.
     */
    val intendedTime: Long? = null,
    /** Earliest tier-state logged_at across this slot's rows, or null. */
    val loggedAt: Long? = null,
    /**
     * Per-medication `taken_at` for the latest non-synthetic dose at this
     * slot today. Drives the inline time label next to each checked
     * medication row — e.g. "8:32 AM" rendered to the right of the med
     * name when the user toggled that med's checkbox individually. The
     * slot-level `loggedAt` covers the aggregate "Taken at HH:mm"
     * summary; this map gives finer-grained per-row times for users who
     * stagger their meds within a single slot.
     */
    val takenAtByMedicationId: Map<Long, Long> = emptyMap(),
    /**
     * Latest non-synthetic dose row per medication. Carried alongside
     * [takenAtByMedicationId] so the long-press time-edit sheet can
     * retime / remove the exact row that's surfacing in the UI without
     * a second repository round-trip.
     */
    val latestDoseByMedicationId: Map<Long, MedicationDoseEntity> = emptyMap()
) {
    /**
     * True when the user backdated this slot — i.e. intended_time was
     * set explicitly to a moment that meaningfully differs from the
     * database write. The 60s tolerance avoids a clock-icon flicker for
     * the trivial gap between "tap to mark" and "row landed".
     */
    val isBacklogged: Boolean
        get() {
            val intended = intendedTime ?: return false
            val logged = loggedAt ?: return false
            return kotlin.math.abs(logged - intended) > 60_000L
        }
}

/**
 * Surface row for medications without any linked slot. Drives the
 * "Unscheduled Medications" section of the Medication screen — a slot-less
 * med would otherwise be invisible (the per-slot card list filters by
 * junction membership). [takenAt] is the most recent non-synthetic dose's
 * `taken_at` for today, used to render a "Last taken at HH:mm" label.
 */
data class UnslottedMedicationState(
    val medication: MedicationEntity,
    val takenToday: Boolean,
    val takenAt: Long? = null,
    /**
     * Every non-synthetic dose row for this medication today. Multiple
     * rows happen because the Unscheduled "Record Taken" button inserts
     * a fresh row per tap (no toggle). The long-press time-edit flow
     * disambiguates via [DosePickerSheet] when the list has >1 entry.
     */
    val dosesToday: List<MedicationDoseEntity> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MedicationViewModel
@Inject
constructor(
    private val medicationRepository: MedicationRepository,
    private val slotRepository: MedicationSlotRepository,
    private val taskBehaviorPreferences: TaskBehaviorPreferences,
    private val batchOperationsRepository: BatchOperationsRepository,
    private val localDateFlow: LocalDateFlow,
    private val clockRescheduler: MedicationClockRescheduler
) : ViewModel() {
    private val _editMode = MutableStateFlow(false)
    val editMode: StateFlow<Boolean> = _editMode

    private val _errorMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorMessages: SharedFlow<String> = _errorMessages.asSharedFlow()

    val medications: StateFlow<List<MedicationEntity>> = medicationRepository
        .observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val activeSlots: StateFlow<List<MedicationSlotEntity>> = slotRepository
        .observeActiveSlots()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * ISO local date scoped by the user's day-start hour preference.
     *
     * Backed by [LocalDateFlow], which combines the SoD source with a
     * wall-clock ticker that re-emits at every logical-day boundary. The
     * initial value uses calendar `LocalDate.now()` as a one-frame
     * fallback — the inner flow emits the SoD-correct value synchronously
     * on subscription, so the initial value is effectively never observed
     * by the UI.
     *
     * See `docs/audits/MEDICATION_SOD_BOUNDARY_AUDIT.md` for the bug this
     * structure replaces.
     */
    val todayDate: StateFlow<String> = localDateFlow
        .observeIsoString(taskBehaviorPreferences.getStartOfDay())
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000L),
            LocalDate.now().toString()
        )

    private val todaysDoses: StateFlow<List<MedicationDoseEntity>> = todayDate
        .flatMapLatest { date -> medicationRepository.observeDosesForDate(date) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private val todaysTierStates: StateFlow<List<MedicationTierStateEntity>> = todayDate
        .flatMapLatest { date -> slotRepository.observeTierStatesForDate(date) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * Reactive list of `(slot, meds, taken, achieved)` projections — the
     * screen renders one card per element. Junction lookups happen inside
     * `combine`'s flatMapLatest loop rather than at render time so the
     * `StateFlow` stays the source of truth.
     */
    val slotTodayStates: StateFlow<List<MedicationSlotTodayState>> = combine(
        activeSlots,
        medications,
        todaysDoses,
        todaysTierStates
    ) { slots, meds, doses, tierStates ->
        // For each slot, resolve its linked meds via the junction, then
        // compute the auto-tier based on today's doses. A user-set tier
        // in the DB sticks regardless of what auto-compute says.
        slots.map { slot ->
            val linkedMedIds = slotRepository.getMedicationIdsForSlotOnce(slot.id).toSet()
            val linkedMeds = meds.filter { it.id in linkedMedIds }
            // Synthetic-skip rows exist only as scheduling anchors for the
            // interval-mode reminder rescheduler — they should not make a
            // med look "taken" in the per-med checkbox UI or in the
            // achieved-tier auto-compute pass.
            val realDoses = doses.asSequence()
                .filter {
                    val medId = it.medicationId
                    medId != null &&
                        medId in linkedMedIds &&
                        it.slotKey == slot.id.toString() &&
                        !it.isSyntheticSkip
                }
                .toList()
            val takenIds = realDoses.mapNotNull { it.medicationId }.toSet()
            // Latest taken_at per medication. Multiple rows can land for
            // the same (med, slot, date) triple after a toggle-untoggle
            // cycle, so collapse to the max so the inline label shows
            // the most recent tap. Custom doses (medicationId=null) are
            // already excluded by the filter above; the !! is sound here.
            val takenAtByMed: Map<Long, Long> = realDoses
                .groupingBy { it.medicationId!! }
                .fold(0L) { acc, dose -> if (dose.takenAt > acc) dose.takenAt else acc }
            // Carry the latest dose entity itself (not just its timestamp)
            // so the long-press edit sheet can retime / remove the exact
            // row that's surfacing in the UI.
            val latestDoseByMed: Map<Long, MedicationDoseEntity> = realDoses
                .groupBy { it.medicationId!! }
                .mapValues { (_, doses) -> doses.maxBy { it.takenAt } }
            val computed = MedicationTierComputer.computeAchievedTier(
                medsForSlot = linkedMeds.associate { it.id to MedicationTier.fromStorage(it.tier) },
                markedTaken = takenIds
            )
            val userRow = tierStates.firstOrNull { it.slotId == slot.id && it.isUserSetSource() }
            val displayTier = userRow?.let { AchievedTier.fromStorage(it.tier) } ?: computed
            // Intended/logged times are recorded per-(med, slot, date) but
            // the user edits them at slot granularity, so all per-slot rows
            // carry the same value. Read from any row for the slot.
            val anySlotRow = tierStates.firstOrNull { it.slotId == slot.id }
            MedicationSlotTodayState(
                slot = slot,
                medications = linkedMeds,
                takenMedicationIds = takenIds,
                achievedTier = displayTier,
                isUserSet = userRow != null,
                intendedTime = anySlotRow?.intendedTime,
                loggedAt = anySlotRow?.loggedAt,
                takenAtByMedicationId = takenAtByMed,
                latestDoseByMedicationId = latestDoseByMed
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * Per-medication "unscheduled" state for medications that aren't linked
     * to any slot. Drives the "Unscheduled Medications" section on the
     * Medication screen — without this, a med added without a slot pick is
     * silently invisible (the slot-card list filters by junction
     * membership). Each row carries the most recent non-synthetic dose's
     * `taken_at` for today so the UI can render a "Taken at HH:mm" label.
     *
     * Doses logged from this section use `slotKey = "anytime"` to match
     * the existing custom-dose convention; the surface stays distinct
     * from the per-slot dose log so a med ever later linked to a slot
     * doesn't pick up a stale "anytime" row as a slot dose.
     */
    val unslottedMedicationsState: StateFlow<List<UnslottedMedicationState>> = combine(
        medications,
        todaysDoses
    ) { meds, doses ->
        meds.mapNotNull { med ->
            val linkedSlotIds = slotRepository.getSlotIdsForMedicationOnce(med.id)
            if (linkedSlotIds.isNotEmpty()) return@mapNotNull null
            val medDoses = doses
                .filter { it.medicationId == med.id && !it.isSyntheticSkip }
            val latestDose = medDoses.maxByOrNull { it.takenAt }
            UnslottedMedicationState(
                medication = med,
                takenToday = latestDose != null,
                takenAt = latestDose?.takenAt,
                dosesToday = medDoses
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private fun MedicationTierStateEntity.isUserSetSource(): Boolean =
        TierSource.fromStorage(this.tierSource) == TierSource.USER_SET

    fun toggleEditMode() {
        _editMode.value = !_editMode.value
    }

    /**
     * Toggle a single medication's dose for the current slot on today's
     * date. Also refreshes the `medication_tier_states` row via
     * auto-compute so the achieved-tier indicator updates reactively —
     * unless the row is `USER_SET`, in which case the user override wins.
     */
    fun toggleDose(
        slot: MedicationSlotEntity,
        medication: MedicationEntity,
        doseAmount: String? = null
    ) {
        viewModelScope.launch {
            val already = todaysDoses.value.firstOrNull {
                it.medicationId == medication.id && it.slotKey == slot.id.toString()
            }
            if (already != null) {
                medicationRepository.unlogDose(already)
            } else {
                medicationRepository.logDose(
                    medicationId = medication.id,
                    slotKey = slot.id.toString(),
                    doseAmount = doseAmount
                )
            }
            refreshTierState(slot.id)
        }
    }

    /**
     * Set a user-claimed intended_time on every per-(medication, slot, today)
     * tier-state row for the given slot. Materializes any missing rows via
     * an auto-compute pass first so the column is non-null after the call.
     */
    fun setIntendedTimeForSlot(slot: MedicationSlotEntity, intendedTime: Long) {
        viewModelScope.launch {
            val date = todayDate.value
            val meds = medicationsForSlotOnce(slot.id)
            // Ensure every per-med row exists — refreshTierState writes
            // COMPUTED rows for any missing (med, slot, date) triple.
            refreshTierState(slot.id)
            meds.forEach { med ->
                slotRepository.setTierStateIntendedTime(
                    medicationId = med.id,
                    slotId = slot.id,
                    date = date,
                    intendedTime = intendedTime
                )
            }
        }
    }

    /**
     * Long-press-on-tier flow: mark the slot at [tier] AND backdate every
     * existing + newly-logged real dose for the slot/today to [takenAt].
     *
     * Composes three writes so the visible "Taken at HH:mm" labels actually
     * move when the user picks a different time:
     *  1. Update `taken_at` on every existing real dose for this slot today
     *     so already-taken meds reflect the user's backdate.
     *  2. Run [bulkMarkInternal] with `takenAtOverride = takenAt` so any
     *     newly-inserted dose rows (meds at-or-below [tier] not yet taken)
     *     carry the same wall-clock instead of `now`.
     *  3. Persist `intended_time = takenAt` on every per-med tier-state row
     *     for the slot — the backlog clock icon depends on this.
     *
     * For [AchievedTier.SKIPPED] the bulk-mark path deletes existing real
     * doses and inserts synthetic skip rows; the explicit pre-pass on step 1
     * is therefore a no-op (deleted rows have nothing left to update), which
     * matches the data model — a skipped slot has no "taken time" to display.
     */
    fun applyTierAtTime(
        slot: MedicationSlotEntity,
        tier: AchievedTier,
        takenAt: Long
    ) {
        viewModelScope.launch {
            val slotKey = slot.id.toString()
            val existing = todaysDoses.value.filter {
                it.slotKey == slotKey && !it.isSyntheticSkip && it.medicationId != null
            }
            existing.forEach { dose ->
                if (dose.takenAt != takenAt) {
                    medicationRepository.updateDose(dose.copy(takenAt = takenAt))
                }
            }
            bulkMarkInternal(BulkMarkScope.SLOT, slot.id, tier, takenAtOverride = takenAt)
            val date = todayDate.value
            val meds = medicationsForSlotOnce(slot.id)
            meds.forEach { med ->
                slotRepository.setTierStateIntendedTime(
                    medicationId = med.id,
                    slotId = slot.id,
                    date = date,
                    intendedTime = takenAt
                )
            }
        }
    }

    /**
     * Retime an existing dose row to [newTakenAt]. Recomputes
     * `taken_date_local` from the SoD-anchored boundary so a retime that
     * crosses a logical-day edge re-files the row under the correct day.
     * Slot-anchored doses also trigger a tier-state refresh — retiming
     * doesn't change which meds are taken, but the call is cheap and
     * keeps the contract symmetric with toggleDose.
     */
    fun retimeDose(dose: MedicationDoseEntity, newTakenAt: Long) {
        viewModelScope.launch {
            val dayStartHour = taskBehaviorPreferences.getDayStartHour().first()
            val dateLocal = com.averycorp.prismtask.util.DayBoundary
                .currentLocalDateString(dayStartHour, newTakenAt)
            medicationRepository.updateDose(
                dose.copy(takenAt = newTakenAt, takenDateLocal = dateLocal)
            )
            dose.slotKey.toLongOrNull()?.let { refreshTierState(it) }
        }
    }

    /**
     * Delete a dose row entirely. Used by the long-press time-edit sheet
     * to recover from accidental taps without forcing the user into the
     * Medication Log screen. Tier-state refresh covers slot-anchored
     * doses; anytime/custom doses (numeric-slot-key parse fails) skip
     * the refresh.
     */
    fun removeDose(dose: MedicationDoseEntity) {
        viewModelScope.launch {
            medicationRepository.unlogDose(dose)
            dose.slotKey.toLongOrNull()?.let { refreshTierState(it) }
        }
    }

    /**
     * Log a fresh dose at a specific past wall-clock. Mirrors
     * [toggleDose] / [recordUnslottedDose] but takes a caller-supplied
     * [takenAt] so the long-press "log at past time" path can stamp a
     * backdated row without first toggling at `now` and then retiming.
     * Slot-linked logs trigger the tier-state refresh; unslotted
     * (`slot == null`) use the `anytime` slot key and skip the refresh.
     */
    fun logDoseAtTime(
        medication: MedicationEntity,
        slot: MedicationSlotEntity?,
        takenAt: Long,
        doseAmount: String? = null
    ) {
        viewModelScope.launch {
            val slotKey = slot?.id?.toString() ?: ANYTIME_SLOT_KEY
            medicationRepository.logDose(
                medicationId = medication.id,
                slotKey = slotKey,
                takenAt = takenAt,
                doseAmount = doseAmount
            )
            if (slot != null) refreshTierState(slot.id)
        }
    }

    /**
     * Recompute the tier state for a slot from the current dose list and
     * persist it as `COMPUTED`. Called after every dose toggle; no-op if
     * the existing row is `USER_SET` (the repository respects the
     * override inside upsertTierState).
     */
    private suspend fun refreshTierState(slotId: Long) {
        val date = todayDate.value
        val meds = medicationsForSlotOnce(slotId)
        val doses = todaysDoses.value
        val takenIds = doses.asSequence()
            .filter { it.slotKey == slotId.toString() }
            .mapNotNull { it.medicationId }
            .toSet()
        val computed = MedicationTierComputer.computeAchievedTier(
            medsForSlot = meds.associate { it.id to MedicationTier.fromStorage(it.tier) },
            markedTaken = takenIds
        )
        meds.forEach { med ->
            slotRepository.upsertTierState(
                medicationId = med.id,
                slotId = slotId,
                date = date,
                tier = computed,
                source = TierSource.COMPUTED
            )
        }
    }

    private suspend fun medicationsForSlotOnce(slotId: Long): List<MedicationEntity> {
        val medIds = slotRepository.getMedicationIdsForSlotOnce(slotId).toSet()
        return medications.value.filter { it.id in medIds }
    }

    // ── Medication CRUD ────────────────────────────────────────────────

    /**
     * Insert a new medication and link it to [slotSelections]. Tier is the
     * enum; the storage write is handled inside MedicationTier.toStorage().
     * Per-slot overrides in [slotSelections] are written via upsertOverride
     * so the underlying UNIQUE index is respected.
     *
     * Pre-flight name guard: `medications.name` carries a unique index
     * (see `MedicationEntity` `Index(value = ["name"], unique = true)`), so
     * a duplicate-name insert would throw `SQLiteConstraintException` and
     * crash via the otherwise-uncaught `viewModelScope.launch`. We look up
     * the existing row and route accordingly:
     *  - active duplicate → emit a friendly error, no insert
     *  - archived duplicate → unarchive + update fields (preserves dose
     *    history; mirrors `MedicationRefillViewModel.addMedication`)
     *  - no duplicate → insert as before
     *
     * Outer `try/catch` covers any other write-path exception (alarm
     * scheduling SecurityException isn't possible here — `ExactAlarmHelper`
     * already handles it — but Room/Firestore writes have their own
     * failure modes worth surfacing rather than crashing).
     */
    fun addMedication(
        name: String,
        tier: MedicationTier,
        notes: String,
        slotSelections: List<MedicationSlotSelection>,
        reminderMode: String? = null,
        reminderIntervalMinutes: Int? = null,
        promptDoseAtLog: Boolean = false
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val trimmed = name.trim()
            try {
                val existing = medicationRepository.getByNameOnce(trimmed)
                val id: Long = when {
                    existing == null -> medicationRepository.insert(
                        MedicationEntity(
                            name = trimmed,
                            tier = tier.toStorage(),
                            notes = notes.trim(),
                            reminderMode = reminderMode,
                            reminderIntervalMinutes = reminderIntervalMinutes,
                            promptDoseAtLog = promptDoseAtLog
                        )
                    )
                    existing.isArchived -> {
                        medicationRepository.update(
                            existing.copy(
                                tier = tier.toStorage(),
                                notes = notes.trim(),
                                reminderMode = reminderMode,
                                reminderIntervalMinutes = reminderIntervalMinutes,
                                promptDoseAtLog = promptDoseAtLog,
                                isArchived = false
                            )
                        )
                        existing.id
                    }
                    else -> {
                        _errorMessages.emit(
                            "A medication named \"$trimmed\" already exists. " +
                                "Edit it instead, or pick a different name."
                        )
                        return@launch
                    }
                }
                slotRepository.replaceLinksForMedication(id, slotSelections.map { it.slotId })
                slotSelections
                    .filter { it.hasOverride }
                    .forEach { sel ->
                        slotRepository.upsertOverride(
                            com.averycorp.prismtask.data.local.entity.MedicationSlotOverrideEntity(
                                medicationId = id,
                                slotId = sel.slotId,
                                overrideIdealTime = sel.overrideIdealTime,
                                overrideDriftMinutes = sel.overrideDriftMinutes
                            )
                        )
                    }
                // Bump the med so its embedded slotCloudIds list re-pushes.
                val inserted = medicationRepository.getByIdOnce(id) ?: return@launch
                medicationRepository.update(inserted)
                // Junction-table writes (medication_medication_slots) don't
                // emit a Flow that the clock rescheduler observes, so a
                // newly-linked med wouldn't fire its CLOCK alarm until the
                // next slot/med edit. Invoke the rescheduler explicitly to
                // close that gap.
                clockRescheduler.rescheduleAll()
            } catch (e: Exception) {
                android.util.Log.e("MedicationVM", "Failed to add medication", e)
                _errorMessages.emit("Couldn't save medication. Please try again.")
            }
        }
    }

    fun updateMedication(
        medication: MedicationEntity,
        name: String,
        tier: MedicationTier,
        notes: String,
        slotSelections: List<MedicationSlotSelection>,
        reminderMode: String? = null,
        reminderIntervalMinutes: Int? = null,
        promptDoseAtLog: Boolean = false
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val trimmed = name.trim()
            try {
                if (trimmed != medication.name) {
                    val collision = medicationRepository.getByNameOnce(trimmed)
                    if (collision != null && collision.id != medication.id) {
                        _errorMessages.emit(
                            "A medication named \"$trimmed\" already exists. " +
                                "Edit it instead, or pick a different name."
                        )
                        return@launch
                    }
                }
                medicationRepository.update(
                    medication.copy(
                        name = trimmed,
                        tier = tier.toStorage(),
                        notes = notes.trim(),
                        reminderMode = reminderMode,
                        reminderIntervalMinutes = reminderIntervalMinutes,
                        promptDoseAtLog = promptDoseAtLog
                    )
                )
                slotRepository.replaceLinksForMedication(medication.id, slotSelections.map { it.slotId })
                // Replace override rows: delete any existing for slots that
                // are no longer overridden, upsert the rest.
                val existingOverrides = slotRepository.getOverridesForMedicationOnce(medication.id)
                val selectedSlotIds = slotSelections.map { it.slotId }.toSet()
                existingOverrides
                    .filter { it.slotId !in selectedSlotIds }
                    .forEach { slotRepository.deleteOverride(it) }
                slotSelections
                    .filter { it.hasOverride }
                    .forEach { sel ->
                        slotRepository.upsertOverride(
                            com.averycorp.prismtask.data.local.entity.MedicationSlotOverrideEntity(
                                medicationId = medication.id,
                                slotId = sel.slotId,
                                overrideIdealTime = sel.overrideIdealTime,
                                overrideDriftMinutes = sel.overrideDriftMinutes
                            )
                        )
                    }
                slotSelections
                    .filter { !it.hasOverride }
                    .forEach { sel -> slotRepository.deleteOverrideForPair(medication.id, sel.slotId) }
                // Junction edits aren't covered by any rescheduler Flow; trigger
                // explicitly so re-linked / unlinked slots take effect immediately.
                clockRescheduler.rescheduleAll()
            } catch (e: Exception) {
                android.util.Log.e("MedicationVM", "Failed to update medication", e)
                _errorMessages.emit("Couldn't save medication. Please try again.")
            }
        }
    }

    fun archiveMedication(medication: MedicationEntity) {
        viewModelScope.launch { medicationRepository.archive(medication.id) }
    }

    /**
     * Record a fresh "anytime" dose for a medication that isn't linked to
     * any slot. Each tap inserts a new `medication_doses` row — no toggle
     * semantics — so a user taking the same PRN med twice in one day gets
     * two distinct entries in the medication log. Uses `slotKey = "anytime"`
     * to stay consistent with [MedicationRepository.logCustomDose] and to
     * avoid colliding with a numeric slot id if the med is later linked.
     *
     * The "last taken at HH:mm" label on the unscheduled-meds section
     * (driven by [unslottedMedicationsState]) gives the user visual
     * confirmation; un-recording must go through the Medication Log
     * screen rather than this button.
     */
    fun recordUnslottedDose(medication: MedicationEntity, doseAmount: String? = null) {
        viewModelScope.launch {
            try {
                medicationRepository.logDose(
                    medicationId = medication.id,
                    slotKey = ANYTIME_SLOT_KEY,
                    doseAmount = doseAmount
                )
            } catch (e: Exception) {
                android.util.Log.e("MedicationVM", "Failed to record unslotted dose", e)
                _errorMessages.emit("Couldn't record dose. Please try again.")
            }
        }
    }

    companion object {
        /**
         * Slot-key sentinel for medications without a linked slot. Matches
         * `MedicationRepository.logCustomDose`'s convention so the
         * Medication Log filters can treat both as "not anchored to a
         * scheduled slot."
         */
        private const val ANYTIME_SLOT_KEY = "anytime"
    }

    // ── Bulk tier marking ──────────────────────────────────────────────

    /**
     * Apply [tier] to every medication in [scope] by writing real
     * dose-log rows for meds at that tier or below (the ladder semantic
     * `MedicationTierComputer` already uses for auto-compute), and
     * routing the work through the batch infrastructure so Settings →
     * Batch History can reverse the whole action under one `batch_id`.
     *
     * **Mutation choice by tier:**
     *  - `SKIPPED` → [BatchMutationType.SKIP] for every linked med. The
     *    batch handler deletes any real doses for the slot today and
     *    writes a synthetic-skip row plus a `USER_SET=SKIPPED` tier-state
     *    so interval-mode reminders re-anchor.
     *  - non-`SKIPPED` → [BatchMutationType.COMPLETE] for every linked
     *    med whose [MedicationTier] sits at or below the clicked rung
     *    AND that doesn't already have a real dose for today. Already-
     *    taken meds at higher tiers stay taken — clicking a lower tier
     *    never auto-unchecks the user's manual marks.
     *
     * After a non-`SKIPPED` apply, the viewmodel deletes any
     * `USER_SET` tier-state rows for the affected slot so the
     * achieved-tier display flips back onto auto-compute (otherwise a
     * stale `USER_SET=SKIPPED` from an earlier Skip would mask the new
     * dose log).
     *
     * Empty scope / no eligible meds → no-op (returns null without
     * touching the batch infra).
     */
    fun bulkMark(scope: BulkMarkScope, slotId: Long?, tier: AchievedTier) {
        viewModelScope.launch { bulkMarkInternal(scope, slotId, tier) }
    }

    /**
     * Suspending implementation extracted so unit tests can `runTest`
     * without spinning up a full `viewModelScope` lifecycle. Returns
     * the [BatchOperationsRepository.BatchApplyResult] of the apply
     * call, or `null` if the scope produced zero targets.
     *
     * [takenAtOverride] — when non-null, every newly-inserted dose row
     * carries this wall-clock as its `taken_at` instead of `now`. Used by
     * the long-press-tier-and-pick-time flow so the displayed per-med
     * "Taken at HH:mm" labels reflect the user's backdated choice.
     */
    internal suspend fun bulkMarkInternal(
        scope: BulkMarkScope,
        slotId: Long?,
        tier: AchievedTier,
        takenAtOverride: Long? = null
    ): BatchOperationsRepository.BatchApplyResult? {
        val date = todayDate.value
        val rawTargets: List<Pair<MedicationEntity, MedicationSlotEntity>> = when (scope) {
            BulkMarkScope.SLOT -> {
                val slot = activeSlots.value.firstOrNull { it.id == slotId } ?: return null
                medicationsForSlotOnce(slot.id).map { it to slot }
            }
            BulkMarkScope.FULL_DAY -> {
                activeSlots.value.flatMap { slot ->
                    medicationsForSlotOnce(slot.id).map { it to slot }
                }
            }
        }
        if (rawTargets.isEmpty()) return null

        val mutations: List<ProposedMutationResponse>
        val storageTier = tier.toStorage()
        val now = System.currentTimeMillis()
        val doseTakenAt = takenAtOverride ?: now

        if (tier == AchievedTier.SKIPPED) {
            mutations = rawTargets.map { (med, slot) ->
                ProposedMutationResponse(
                    entityType = BatchEntityType.MEDICATION.name,
                    entityId = med.id.toString(),
                    mutationType = BatchMutationType.SKIP.name,
                    proposedNewValues = mapOf(
                        // slotKey on dose rows is the numeric slot id stringified
                        // (the per-med checkbox path uses slot.id.toString()); writing
                        // slot.name here would leave doses invisible to the slot-card UI.
                        "slot_key" to slot.id.toString(),
                        "date" to date,
                        "tier" to storageTier
                    ),
                    humanReadableDescription = "Skip ${med.name} (${slot.name})"
                )
            }
        } else {
            // Filter by tier ladder: only meds at or below the clicked
            // tier are eligible; already-taken meds are left alone so we
            // don't pile up duplicate dose rows.
            val takenByMed: Map<Long, Set<Long>> = todaysDoses.value
                .asSequence()
                .filter { !it.isSyntheticSkip && it.medicationId != null }
                .groupBy { it.medicationId!! }
                .mapValues { (_, doses) -> doses.map { it.slotKey.toLongOrNull() ?: -1L }.toSet() }
            mutations = rawTargets
                .filter { (med, _) ->
                    val medTier = MedicationTier.fromStorage(med.tier)
                    AchievedTier.from(medTier).ordinal <= tier.ordinal
                }
                .filterNot { (med, slot) -> takenByMed[med.id]?.contains(slot.id) == true }
                .map { (med, slot) ->
                    ProposedMutationResponse(
                        entityType = BatchEntityType.MEDICATION.name,
                        entityId = med.id.toString(),
                        mutationType = BatchMutationType.COMPLETE.name,
                        proposedNewValues = mapOf(
                            "slot_key" to slot.id.toString(),
                            "date" to date,
                            "tier" to storageTier,
                            "taken_at" to doseTakenAt
                        ),
                        humanReadableDescription = "Mark ${med.name} (${slot.name}) taken"
                    )
                }
        }

        if (mutations.isEmpty()) return null

        val commandText = when (scope) {
            BulkMarkScope.SLOT -> {
                val slotName = rawTargets.first().second.name
                "Bulk mark ${mutations.size} medication(s) in slot \"$slotName\" as $storageTier"
            }
            BulkMarkScope.FULL_DAY -> {
                "Bulk mark ${mutations.size} medication(s) across today as $storageTier"
            }
        }

        val result = batchOperationsRepository.applyBatch(commandText, mutations)

        // Stale USER_SET tier-state rows would mask the freshly logged
        // doses — the slot card would still show "Skipped" with every
        // checkbox now ticked. Drop them on the affected slots so
        // auto-compute drives the display.
        //
        // Then materialize COMPUTED rows for every (med, slot, date) in
        // the affected slots. The batch handler only inserts dose rows;
        // it never writes tier_states. Without this pass `state.loggedAt`
        // stays null and the slot card drops the "Taken at HH:mm" line —
        // which looked, from the user's seat, like the tier buttons had
        // stopped recording a time at all.
        if (tier != AchievedTier.SKIPPED) {
            val affectedSlotIds = rawTargets.map { it.second.id }.toSet()
            val priorStates = slotRepository.getTierStatesForDateOnce(date)
            priorStates
                .filter { it.slotId in affectedSlotIds && it.tierSource == "user_set" }
                .forEach { slotRepository.deleteTierState(it) }
            affectedSlotIds.forEach { slotId -> refreshTierState(slotId) }
        }

        return result
    }

    suspend fun selectionsForMedication(medicationId: Long): List<MedicationSlotSelection> {
        val slotIds = slotRepository.getSlotIdsForMedicationOnce(medicationId)
        val overrides = slotRepository.getOverridesForMedicationOnce(medicationId)
            .associateBy { it.slotId }
        return slotIds.map { slotId ->
            val o = overrides[slotId]
            MedicationSlotSelection(
                slotId = slotId,
                overrideIdealTime = o?.overrideIdealTime,
                overrideDriftMinutes = o?.overrideDriftMinutes
            )
        }
    }
}
