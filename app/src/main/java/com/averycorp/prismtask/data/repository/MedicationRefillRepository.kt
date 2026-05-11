package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.MedicationRefillDao
import com.averycorp.prismtask.data.local.entity.MedicationRefillEntity
import com.averycorp.prismtask.data.remote.SyncTracker
import com.averycorp.prismtask.domain.usecase.RefillCalculator
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for medication refill metadata (v1.4.0 V10).
 *
 * Wraps the DAO with convenience helpers that apply the pure-function
 * [RefillCalculator] so callers can observe rows and apply a daily dose
 * or refill without having to reach into the use case layer.
 */
@Singleton
class MedicationRefillRepository
@Inject
constructor(private val dao: MedicationRefillDao, private val syncTracker: SyncTracker) {
    fun observeAll(): Flow<List<MedicationRefillEntity>> = dao.observeAll()

    suspend fun getAll(): List<MedicationRefillEntity> = dao.getAll()

    suspend fun getByName(name: String): MedicationRefillEntity? = dao.getByName(name)

    suspend fun upsert(refill: MedicationRefillEntity): Long {
        val now = System.currentTimeMillis()
        val stamped = refill.copy(updatedAt = now)
        val id = dao.upsert(stamped)
        if (refill.id == 0L) {
            syncTracker.trackCreate(id, "medication_refill")
        } else {
            syncTracker.trackUpdate(id, "medication_refill")
        }
        return id
    }

    suspend fun applyDailyDose(refill: MedicationRefillEntity) {
        val stamped = RefillCalculator.applyDailyDose(refill).copy(updatedAt = System.currentTimeMillis())
        dao.update(stamped)
        syncTracker.trackUpdate(refill.id, "medication_refill")
    }

    suspend fun applyRefill(refill: MedicationRefillEntity, newSupply: Int) {
        val stamped = RefillCalculator.applyRefill(refill, newSupply).copy(updatedAt = System.currentTimeMillis())
        dao.update(stamped)
        syncTracker.trackUpdate(refill.id, "medication_refill")
    }

    suspend fun delete(id: Long) {
        dao.delete(id)
        syncTracker.trackDelete(id, "medication_refill")
    }
}
