package com.averycorp.prismtask.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.entity.MedicationSlotEntity
import com.averycorp.prismtask.data.repository.MedicationSlotRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs [MedicationSlotsScreen] (the Settings → Medication Slots editor).
 * Exposes the full slot list (active + soft-deleted) so the UI can offer
 * "Show deleted" without re-fetching, and proxies CRUD to
 * [MedicationSlotRepository].
 */
@HiltViewModel
class MedicationSlotsViewModel
@Inject
constructor(private val repository: MedicationSlotRepository) : ViewModel() {

    val allSlots: StateFlow<List<MedicationSlotEntity>> =
        repository.observeAllSlots().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList()
        )

    fun create(
        name: String,
        idealTime: String,
        driftMinutes: Int,
        reminderMode: String? = null,
        reminderIntervalMinutes: Int? = null
    ) {
        if (name.isBlank() || idealTime.isBlank()) return
        viewModelScope.launch {
            val current = repository.getAllSlotsOnce()
            val nextOrder = (current.maxOfOrNull { it.sortOrder } ?: -1) + 1
            repository.insertSlot(
                MedicationSlotEntity(
                    name = name.trim(),
                    idealTime = idealTime,
                    driftMinutes = driftMinutes,
                    sortOrder = nextOrder,
                    isActive = true,
                    reminderMode = reminderMode,
                    reminderIntervalMinutes = reminderIntervalMinutes
                )
            )
        }
    }

    fun update(
        slot: MedicationSlotEntity,
        name: String,
        idealTime: String,
        driftMinutes: Int,
        reminderMode: String? = null,
        reminderIntervalMinutes: Int? = null
    ) {
        if (name.isBlank() || idealTime.isBlank()) return
        viewModelScope.launch {
            repository.updateSlot(
                slot.copy(
                    name = name.trim(),
                    idealTime = idealTime,
                    driftMinutes = driftMinutes,
                    reminderMode = reminderMode,
                    reminderIntervalMinutes = reminderIntervalMinutes
                )
            )
        }
    }

    fun softDelete(id: Long) {
        viewModelScope.launch { repository.softDeleteSlot(id) }
    }

    fun restore(id: Long) {
        viewModelScope.launch { repository.restoreSlot(id) }
    }

    /**
     * Swap two slots' `sort_order` values. The UI passes the source row
     * and the row immediately above/below it; this keeps the rest of the
     * list unaffected and avoids a full re-numbering pass.
     */
    fun swapOrder(a: MedicationSlotEntity, b: MedicationSlotEntity) {
        if (a.id == b.id) return
        viewModelScope.launch {
            repository.updateSlot(a.copy(sortOrder = b.sortOrder))
            repository.updateSlot(b.copy(sortOrder = a.sortOrder))
        }
    }
}
