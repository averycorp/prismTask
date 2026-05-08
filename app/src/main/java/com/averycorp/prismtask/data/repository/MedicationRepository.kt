package com.averycorp.prismtask.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.averycorp.prismtask.data.local.dao.MedicationDao
import com.averycorp.prismtask.data.local.dao.MedicationDoseDao
import com.averycorp.prismtask.data.local.entity.MedicationDoseEntity
import com.averycorp.prismtask.data.local.entity.MedicationEntity
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.data.remote.SyncTracker
import com.averycorp.prismtask.util.DayBoundary
import com.averycorp.prismtask.widget.WidgetUpdateManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single-source-of-truth repository for the top-level Medication entity.
 * Every write path calls [SyncTracker] with the correct entity type
 * (`"medication"` or `"medication_dose"`) so the sync layer picks up
 * the change on the next push cycle.
 *
 * PR 2 of the medication refactor (spec:
 * `docs/SPEC_MEDICATIONS_TOP_LEVEL.md`). The Medication UI still reads
 * from `SelfCareRepository` until PR 4 rewires the viewmodels.
 */
@Singleton
class MedicationRepository
@Inject
constructor(
    private val medicationDao: MedicationDao,
    private val medicationDoseDao: MedicationDoseDao,
    private val syncTracker: SyncTracker,
    private val taskBehaviorPreferences: TaskBehaviorPreferences,
    private val widgetUpdateManager: WidgetUpdateManager,
    private val automationEventBus: com.averycorp.prismtask.domain.automation.AutomationEventBus
) {
    fun observeActive(): Flow<List<MedicationEntity>> = medicationDao.getActive()

    fun observeAll(): Flow<List<MedicationEntity>> = medicationDao.getAll()

    fun observeById(id: Long): Flow<MedicationEntity?> = medicationDao.observeById(id)

    fun observeDosesForDate(date: String): Flow<List<MedicationDoseEntity>> =
        medicationDoseDao.getForDate(date)

    fun observeAllDoses(): Flow<List<MedicationDoseEntity>> =
        medicationDoseDao.observeAll()

    fun observeDosesForMedOnDate(
        medicationId: Long,
        date: String
    ): Flow<List<MedicationDoseEntity>> =
        medicationDoseDao.getForMedOnDate(medicationId, date)

    suspend fun getByIdOnce(id: Long): MedicationEntity? =
        medicationDao.getByIdOnce(id)

    suspend fun getByNameOnce(name: String): MedicationEntity? =
        medicationDao.getByNameOnce(name)

    suspend fun getActiveOnce(): List<MedicationEntity> =
        medicationDao.getActiveOnce()

    suspend fun getAllOnce(): List<MedicationEntity> =
        medicationDao.getAllOnce()

    /**
     * Insert a medication, recovering from a `medications.name` UNIQUE
     * collision by adopting the existing same-name row. Callers (the
     * Medication / Refill viewmodels) pre-flight a `getByNameOnce` check,
     * but two concurrent `viewModelScope.launch` calls — or a sync pull
     * landing between the pre-flight and the insert — can still race the
     * unique index and throw `SQLiteConstraintException`. Without this
     * recovery, that exception propagated out of the otherwise-uncaught
     * Room insert lambda and crashed the app (Crashlytics signature:
     * `MedicationDao_Impl.insert$lambda$0` →
     * `UNIQUE constraint failed: medications.name`).
     *
     * On collision: look up the existing row by name and return its id
     * so the caller's downstream slot-link / override writes still land
     * on the surviving medication. Tracking emits `trackCreate` only on
     * a true insert; the adopt branch leaves the existing row untouched
     * so its sync state stays accurate.
     */
    suspend fun insert(medication: MedicationEntity): Long {
        val now = System.currentTimeMillis()
        val toInsert = medication.copy(createdAt = now, updatedAt = now)
        val id = try {
            medicationDao.insert(toInsert)
        } catch (e: SQLiteConstraintException) {
            val existing = medicationDao.getByNameOnce(toInsert.name) ?: throw e
            return existing.id
        }
        syncTracker.trackCreate(id, "medication")
        return id
    }

    suspend fun update(medication: MedicationEntity) {
        val updated = medication.copy(updatedAt = System.currentTimeMillis())
        medicationDao.update(updated)
        syncTracker.trackUpdate(updated.id, "medication")
    }

    suspend fun archive(id: Long) {
        val now = System.currentTimeMillis()
        medicationDao.archive(id, now)
        syncTracker.trackUpdate(id, "medication")
    }

    suspend fun unarchive(id: Long) {
        val now = System.currentTimeMillis()
        medicationDao.unarchive(id, now)
        syncTracker.trackUpdate(id, "medication")
    }

    /**
     * Deletes the medication AND its dose history via the FK CASCADE.
     * UI primary affordance should be [archive], not delete — this call
     * path is for explicit user-initiated deletions with a confirmation
     * dialog, or for test / cleanup code.
     */
    suspend fun delete(medication: MedicationEntity) {
        medicationDao.delete(medication)
        syncTracker.trackDelete(medication.id, "medication")
    }

    suspend fun logDose(
        medicationId: Long,
        slotKey: String,
        takenAt: Long = System.currentTimeMillis(),
        note: String = "",
        doseAmount: String? = null
    ): Long {
        val dayStartHour = taskBehaviorPreferences.getDayStartHour().first()
        val dateLocal = DayBoundary.currentLocalDateString(dayStartHour, takenAt)
        val now = System.currentTimeMillis()
        val dose = MedicationDoseEntity(
            medicationId = medicationId,
            slotKey = slotKey,
            takenAt = takenAt,
            takenDateLocal = dateLocal,
            note = note,
            doseAmount = doseAmount,
            createdAt = now,
            updatedAt = now
        )
        val id = medicationDoseDao.insert(dose)
        syncTracker.trackCreate(id, "medication_dose")
        automationEventBus.emit(
            com.averycorp.prismtask.domain.automation.AutomationEvent.MedicationLogged(
                medicationId = medicationId,
                slotKey = slotKey
            )
        )
        widgetUpdateManager.updateMedicationWidget()
        return id
    }

    /**
     * Inserts a one-time custom dose with no parent [MedicationEntity].
     * The dose carries [name] verbatim in [MedicationDoseEntity.customMedicationName]
     * (after `trim`), `slotKey = "anytime"` (custom doses are inherently
     * ad-hoc), and `medicationId = null`. Used by the Medication Log
     * screen's "log custom dose" affordance for things the user took but
     * doesn't track on a recurring schedule.
     *
     * Throws if [name] is blank — a custom dose without a name has no
     * way to render in the log and would defeat the feature's purpose.
     */
    suspend fun logCustomDose(
        name: String,
        takenAt: Long = System.currentTimeMillis(),
        note: String = ""
    ): Long {
        require(name.isNotBlank()) { "custom medication name must be non-blank" }
        val dayStartHour = taskBehaviorPreferences.getDayStartHour().first()
        val dateLocal = DayBoundary.currentLocalDateString(dayStartHour, takenAt)
        val now = System.currentTimeMillis()
        val dose = MedicationDoseEntity(
            medicationId = null,
            customMedicationName = name.trim(),
            slotKey = "anytime",
            takenAt = takenAt,
            takenDateLocal = dateLocal,
            note = note,
            createdAt = now,
            updatedAt = now
        )
        val id = medicationDoseDao.insert(dose)
        syncTracker.trackCreate(id, "medication_dose")
        widgetUpdateManager.updateMedicationWidget()
        return id
    }

    /**
     * Insert a synthetic-skip dose. The interval-mode reminder rescheduler
     * uses every dose row — including these synthetic ones — as a re-anchor
     * point, so that marking a slot SKIPPED still resets the interval clock.
     * UI dose history filters these out via [MedicationDoseEntity.isSyntheticSkip].
     *
     * Called from the SKIPPED tier-state path in the medication ViewModel
     * once per (medication, slot) covered by the SKIPPED action.
     */
    suspend fun logSyntheticSkipDose(
        medicationId: Long,
        slotKey: String,
        intendedAt: Long = System.currentTimeMillis()
    ): Long {
        val dayStartHour = taskBehaviorPreferences.getDayStartHour().first()
        val dateLocal = DayBoundary.currentLocalDateString(dayStartHour, intendedAt)
        val now = System.currentTimeMillis()
        val dose = MedicationDoseEntity(
            medicationId = medicationId,
            slotKey = slotKey,
            takenAt = intendedAt,
            takenDateLocal = dateLocal,
            note = "",
            isSyntheticSkip = true,
            createdAt = now,
            updatedAt = now
        )
        val id = medicationDoseDao.insert(dose)
        syncTracker.trackCreate(id, "medication_dose")
        widgetUpdateManager.updateMedicationWidget()
        return id
    }

    suspend fun unlogDose(dose: MedicationDoseEntity) {
        medicationDoseDao.delete(dose)
        syncTracker.trackDelete(dose.id, "medication_dose")
        widgetUpdateManager.updateMedicationWidget()
    }

    suspend fun updateDose(dose: MedicationDoseEntity) {
        val updated = dose.copy(updatedAt = System.currentTimeMillis())
        medicationDoseDao.update(updated)
        syncTracker.trackUpdate(updated.id, "medication_dose")
        widgetUpdateManager.updateMedicationWidget()
    }

    suspend fun countDosesForMedOnDateOnce(medicationId: Long, date: String): Int =
        medicationDoseDao.countForMedOnDateOnce(medicationId, date)

    suspend fun getLatestDoseForMedOnce(medicationId: Long): MedicationDoseEntity? =
        medicationDoseDao.getLatestForMedOnce(medicationId)
}
