package com.averycorp.prismtask.ui.screens.medication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.entity.MedicationDoseEntity
import com.averycorp.prismtask.data.local.entity.MedicationEntity
import com.averycorp.prismtask.data.local.entity.MedicationSlotEntity
import com.averycorp.prismtask.data.local.entity.MedicationTierStateEntity
import com.averycorp.prismtask.data.repository.MedicationRepository
import com.averycorp.prismtask.data.repository.MedicationSlotRepository
import com.averycorp.prismtask.domain.model.medication.AchievedTier
import com.averycorp.prismtask.domain.model.medication.TierSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Medication Log screen — day-by-day history of every
 * dose the user has recorded, plus tier-state-only entries surfaced from
 * past tier-button taps that pre-date PR #857 (or skips that intentionally
 * record a slot-level "skipped" marker without per-med doses).
 *
 * Post v1.4 medication-top-level refactor (spec:
 * `docs/SPEC_MEDICATIONS_TOP_LEVEL.md` §6.1), reads exclusively from
 * [MedicationRepository]. Each [MedicationLogDay] holds per-med [doses]
 * and slot-level [SlotTierEntry] rows so historical activity stays
 * legible even when the underlying dose log was never written.
 */
@HiltViewModel
class MedicationLogViewModel
@Inject
constructor(
    private val repository: MedicationRepository,
    private val slotRepository: MedicationSlotRepository
) : ViewModel() {
    /**
     * Logs a one-time custom medication dose against today. Delegates to
     * [MedicationRepository.logCustomDose] which enforces the non-blank
     * name invariant and stores the dose with `slotKey="anytime"` and
     * `medicationId=null`. Surfaced to the Log screen's "+" affordance.
     */
    fun logCustomDose(name: String, takenAtMillis: Long, note: String) {
        viewModelScope.launch {
            try {
                repository.logCustomDose(name = name, takenAt = takenAtMillis, note = note)
            } catch (_: IllegalArgumentException) {
                // Blank-name guard. The UI's enabled-when-non-blank already
                // prevents this, but keep the catch so a future caller
                // can't crash the ViewModel by passing an empty string.
            }
        }
    }

    val days: StateFlow<List<MedicationLogDay>> = combine(
        repository.observeAll(),
        repository.observeAllDoses(),
        slotRepository.observeAllSlots(),
        slotRepository.observeAllTierStates()
    ) { meds, doses, slots, tierStates ->
        val medsById = meds.associateBy { it.id }
        val slotsById = slots.associateBy { it.id }

        // Synthetic-skip rows are scheduling anchors only — never user history.
        val visibleDoses = doses.filterNot { it.isSyntheticSkip }

        // Computed tier-state rows are derivative of doses (refreshTierState
        // writes them on every dose toggle), so surfacing them as separate
        // entries would double-count today's per-med checkbox activity. Keep
        // only user_set rows: pre-PR #857 tier-button taps and post-PR #857
        // skips both write user_set, so this captures the "log was empty
        // because tier-button taps didn't write doses" history.
        val userTierStates = tierStates.filter { it.isUserSetSource() }

        val datesFromDoses = visibleDoses.map { it.takenDateLocal }
        val datesFromTierStates = userTierStates.map { it.logDate }
        val allDates = (datesFromDoses + datesFromTierStates).distinct()

        allDates.map { date ->
            val dosesForDate = visibleDoses.filter { it.takenDateLocal == date }
            val tierStatesForDate = userTierStates.filter { it.logDate == date }

            // For a (slot, date), only surface a tier-state-only entry when
            // no real dose covers that slot — otherwise the doses already
            // tell the story and a duplicate slot chip would clutter the
            // day card. Slot ids on doses live in `slotKey` as numeric
            // strings (post bulk-mark fix); pre-fix doses with slot.name
            // keys won't match — those rows simply lack tier-state coverage.
            val slotIdsWithDoses = dosesForDate
                .mapNotNull { it.slotKey.toLongOrNull() }
                .toSet()
            val slotEntries = tierStatesForDate
                .groupBy { it.slotId }
                .filterKeys { slotId -> slotId !in slotIdsWithDoses }
                .mapNotNull { (slotId, rows) ->
                    val slot = slotsById[slotId] ?: return@mapNotNull null
                    val tier = AchievedTier.fromStorage(rows.first().tier)
                    val intended = rows.firstOrNull { it.intendedTime != null }?.intendedTime
                    val logged = rows.minOfOrNull { it.loggedAt }
                    SlotTierEntry(
                        slot = slot,
                        tier = tier,
                        intendedTime = intended,
                        loggedAt = logged
                    )
                }
                .sortedBy { it.intendedTime ?: it.loggedAt ?: 0L }

            MedicationLogDay(
                date = date,
                doses = dosesForDate.sortedBy { it.takenAt },
                slotEntries = slotEntries,
                medicationsById = medsById,
                slotsById = slotsById
            )
        }.sortedByDescending { it.date }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun MedicationTierStateEntity.isUserSetSource(): Boolean =
        TierSource.fromStorage(this.tierSource) == TierSource.USER_SET
}

/**
 * One day's worth of dose history. Embeds the `medicationsById` lookup
 * so UI code can resolve names without needing its own StateFlow.
 *
 * [slotEntries] are slot-level summaries surfaced from
 * `medication_tier_states` rows that lack corresponding doses — typically
 * pre-PR #857 tier-button taps that wrote a tier-state row without
 * logging per-med doses, or post-PR #857 skips that intentionally record
 * a slot-level "skipped" marker. Rendered separately from per-med
 * [doses] so the difference between "we know exactly what was taken" and
 * "the slot was logged at tier X" stays legible.
 */
data class MedicationLogDay(
    /** ISO yyyy-MM-dd in the device's local timezone. */
    val date: String,
    val doses: List<MedicationDoseEntity>,
    val slotEntries: List<SlotTierEntry> = emptyList(),
    val medicationsById: Map<Long, MedicationEntity>,
    /**
     * Lookup of every active medication slot at view-build time, keyed by
     * slot id. The medication screen writes `dose.slotKey = slot.id.toString()`
     * (per the bulk-mark fix in PR #857), so the log resolves slot-id
     * doses to their slot's display name + ideal time via this map.
     * Defaults to empty for legacy callers; unresolved slot keys (legacy
     * "morning" / "anytime" / "HH:MM" strings, or numeric ids whose slot
     * has been deleted) fall back to the legacy bucketing logic in the
     * screen.
     */
    val slotsById: Map<Long, MedicationSlotEntity> = emptyMap()
) {
    val loggedCount: Int get() = doses.size + slotEntries.size

    /**
     * Groups [doses] by slot-key. Preserves the input ordering within
     * each group so the earliest dose shows first. Slot keys are
     * opaque strings — callers decide how to label the sections.
     */
    val dosesBySlot: Map<String, List<MedicationDoseEntity>>
        get() = doses.groupBy { it.slotKey }

    /**
     * Doses whose `slotKey` parses to a numeric slot id present in
     * [slotsById], grouped by the resolved slot. The screen renders
     * one section per entry, ordered by `slot.idealTime`. Unresolved
     * doses are excluded and surface via [legacyDosesBySlot].
     */
    val dosesByResolvedSlot: Map<MedicationSlotEntity, List<MedicationDoseEntity>>
        get() = doses
            .mapNotNull { dose ->
                val slot = dose.slotKey.toLongOrNull()?.let { slotsById[it] } ?: return@mapNotNull null
                slot to dose
            }
            .groupBy({ it.first }, { it.second })

    /**
     * Doses whose slot key didn't resolve through [slotsById] — pre-migration
     * legacy strings ("morning", "afternoon", "evening", "night", "anytime",
     * "HH:MM") and orphaned numeric ids whose slot has since been deleted.
     * Grouped by raw slot key so the screen can keep dispatching them
     * through the legacy bucketing logic.
     */
    val legacyDosesBySlot: Map<String, List<MedicationDoseEntity>>
        get() = doses
            .filterNot { dose ->
                dose.slotKey.toLongOrNull()?.let { slotsById.containsKey(it) } == true
            }
            .groupBy { it.slotKey }

    fun medicationName(dose: MedicationDoseEntity): String {
        if (dose.medicationId == null) {
            return dose.customMedicationName?.takeIf { it.isNotBlank() } ?: "Custom"
        }
        val med = medicationsById[dose.medicationId]
        return med?.displayLabel ?: med?.name ?: "Unknown"
    }
}

/**
 * Slot-level log entry surfaced from `medication_tier_states` rows when
 * no corresponding dose exists. Carries the user-claimed [intendedTime]
 * (set via long-press → Edit Time) and the database-write [loggedAt] —
 * UI prefers `intendedTime` and falls back to `loggedAt`.
 */
data class SlotTierEntry(val slot: MedicationSlotEntity, val tier: AchievedTier, val intendedTime: Long?, val loggedAt: Long?) {
    /** The best-effort wall-clock for display, or null if both are missing. */
    val displayTime: Long? get() = intendedTime ?: loggedAt
}
