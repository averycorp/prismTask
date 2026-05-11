package com.averycorp.prismtask.ui.screens.medication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.entity.MedicationEntity
import com.averycorp.prismtask.data.repository.MedicationRepository
import com.averycorp.prismtask.domain.usecase.RefillCalculator
import com.averycorp.prismtask.domain.usecase.RefillForecast
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Medication Refill tracking screen.
 *
 * Post v1.4 medication-top-level refactor (spec:
 * `docs/SPEC_MEDICATIONS_TOP_LEVEL.md` §6.1), reads from
 * [MedicationRepository] — the old `MedicationRefillRepository` +
 * `MedicationRefillEntity` path is going away in Phase 2 cleanup. Only
 * medications with a non-null `pillCount` participate in refill tracking
 * (a user has to explicitly enter a pill count for a medication to show
 * up on this screen).
 *
 * Each write method is wrapped in `try/catch` and routes failures to
 * [errorMessages]. The pre-flight `getByNameOnce` in [addMedication]
 * already prevents the `medications.name` UNIQUE-index collision crash
 * class fixed in PR #1141 on the main medication path, but the wraps
 * provide defense-in-depth for any future repository call that grows
 * a new exception source (e.g. backend pharmacy lookup, batch ops).
 * Pattern mirrors `MedicationViewModel`'s post-#1141 shape; see
 * `docs/audits/F5_MEDICATION_HYGIENE_FOLLOWONS_AUDIT.md` § F.5b.
 */
@HiltViewModel
class MedicationRefillViewModel
@Inject
constructor(private val repository: MedicationRepository) : ViewModel() {
    private val _errorMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorMessages: SharedFlow<String> = _errorMessages.asSharedFlow()

    val medications: StateFlow<List<MedicationWithForecast>> =
        repository
            .observeActive()
            .map { list ->
                val now = System.currentTimeMillis()
                list.mapNotNull { med ->
                    val forecast = RefillCalculator.forecast(med, now) ?: return@mapNotNull null
                    MedicationWithForecast(med, forecast)
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Create a new refill-tracked medication, or enable tracking on an
     * existing unrelated one by the same name. Existing non-tracked
     * medications keep their name/tier/schedule — we just set pill-count
     * fields onto them.
     */
    fun addMedication(
        name: String,
        pillCount: Int,
        pillsPerDose: Int,
        dosesPerDay: Int,
        pharmacyName: String? = null,
        pharmacyPhone: String? = null
    ) {
        viewModelScope.launch {
            val trimmed = name.trim()
            try {
                val existing = repository.getByNameOnce(trimmed)
                val now = System.currentTimeMillis()
                if (existing != null) {
                    repository.update(
                        existing.copy(
                            pillCount = pillCount,
                            pillsPerDose = pillsPerDose,
                            dosesPerDay = dosesPerDay,
                            pharmacyName = pharmacyName?.takeIf { it.isNotBlank() }
                                ?: existing.pharmacyName,
                            pharmacyPhone = pharmacyPhone?.takeIf { it.isNotBlank() }
                                ?: existing.pharmacyPhone,
                            lastRefillDate = now,
                            updatedAt = now,
                            isArchived = false
                        )
                    )
                } else {
                    repository.insert(
                        MedicationEntity(
                            name = trimmed,
                            displayLabel = trimmed,
                            pillCount = pillCount,
                            pillsPerDose = pillsPerDose,
                            dosesPerDay = dosesPerDay,
                            pharmacyName = pharmacyName?.takeIf { it.isNotBlank() },
                            pharmacyPhone = pharmacyPhone?.takeIf { it.isNotBlank() },
                            lastRefillDate = now,
                            // Fresh refill-tracked med defaults to as-needed
                            // scheduling; user can set a schedule separately
                            // via the main Medication screen.
                            scheduleMode = "AS_NEEDED"
                        )
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("MedicationRefillVM", "Failed to save medication", e)
                _errorMessages.emit("Couldn't save medication. Please try again.")
            }
        }
    }

    fun recordDailyDose(med: MedicationEntity) {
        viewModelScope.launch {
            try {
                repository.update(RefillCalculator.applyDailyDose(med))
            } catch (e: Exception) {
                android.util.Log.e("MedicationRefillVM", "Failed to record dose", e)
                _errorMessages.emit("Couldn't record dose. Please try again.")
            }
        }
    }

    fun recordRefill(med: MedicationEntity, newSupply: Int) {
        viewModelScope.launch {
            try {
                repository.update(RefillCalculator.applyRefill(med, newSupply))
            } catch (e: Exception) {
                android.util.Log.e("MedicationRefillVM", "Failed to record refill", e)
                _errorMessages.emit("Couldn't record refill. Please try again.")
            }
        }
    }

    /**
     * Disables refill tracking for this medication by clearing pillCount
     * (which removes it from the refill-screen list). Other medication
     * fields stay intact — the medication itself isn't deleted. Use the
     * Medication-screen delete path to remove it entirely.
     */
    fun disableRefillTracking(id: Long) {
        viewModelScope.launch {
            try {
                val existing = repository.getByIdOnce(id) ?: return@launch
                repository.update(
                    existing.copy(
                        pillCount = null,
                        lastRefillDate = null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                android.util.Log.e("MedicationRefillVM", "Failed to update tracking", e)
                _errorMessages.emit("Couldn't update tracking. Please try again.")
            }
        }
    }
}

data class MedicationWithForecast(val row: MedicationEntity, val forecast: RefillForecast)
