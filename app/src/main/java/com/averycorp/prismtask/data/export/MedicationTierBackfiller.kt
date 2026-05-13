package com.averycorp.prismtask.data.export

import com.averycorp.prismtask.data.local.dao.MedicationDao
import com.averycorp.prismtask.data.local.dao.MedicationSlotDao
import com.averycorp.prismtask.data.local.dao.MedicationTierStateDao
import com.averycorp.prismtask.data.local.dao.SelfCareDao
import com.averycorp.prismtask.data.local.entity.MedicationTierStateEntity
import com.google.gson.JsonParser

/**
 * D8 Item 8 — after a v3 backup restore that re-introduces legacy
 * `tiers_by_time` JSON content on `self_care_logs`, populate
 * `medication_tier_states` so forward consumers (Firestore sync, future
 * readers) see the normalized state without waiting for the user to touch
 * the medication card. Mirrors `MIGRATION_59_60` semantics: DEFAULT slot
 * only, max-tier across timeOfDay entries, `tier_source = "computed"`.
 *
 * Idempotent — if a row already exists for `(med, default_slot, date)` its
 * tier is updated only when different. Restores executed before any
 * medications exist (or before the DEFAULT slot is seeded) skip silently;
 * the next `setTierForTime` call will dual-write the row.
 */
internal class MedicationTierBackfiller(
    private val selfCareDao: SelfCareDao,
    private val medicationDao: MedicationDao,
    private val medicationSlotDao: MedicationSlotDao,
    private val medicationTierStateDao: MedicationTierStateDao
) {
    suspend fun backfillAfterRestore(ctx: ImportContext) {
        val defaultSlot = medicationSlotDao.getByNameOnce("Default") ?: return
        val activeMeds = medicationDao.getActiveOnce()
        if (activeMeds.isEmpty()) return
        val medicationLogs = selfCareDao.getAllLogsOnce()
            .filter { it.routineType == "medication" }
        val tierOrder = listOf("essential", "prescription", "complete")
        val now = System.currentTimeMillis()
        for (log in medicationLogs) {
            val raw = log.tiersByTime
            if (raw.isBlank() || raw == "{}") continue
            val parsedTiers = parseTiersByTimeRaw(raw)
            val maxTier = parsedTiers.values
                .filter { it in tierOrder }
                .maxByOrNull { tierOrder.indexOf(it) }
                ?: continue
            val date = epochMillisToLocalDateString(log.date)
            for (med in activeMeds) {
                val existing = medicationTierStateDao.getForTripleOnce(med.id, date, defaultSlot.id)
                if (existing == null) {
                    medicationTierStateDao.insert(
                        MedicationTierStateEntity(
                            medicationId = med.id,
                            slotId = defaultSlot.id,
                            logDate = date,
                            tier = maxTier,
                            tierSource = "computed",
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                    ctx.medicationTierStatesBackfilled++
                } else if (existing.tier != maxTier) {
                    medicationTierStateDao.update(
                        existing.copy(tier = maxTier, updatedAt = now)
                    )
                    ctx.medicationTierStatesBackfilled++
                }
            }
        }
    }

    private fun parseTiersByTimeRaw(json: String): Map<String, String> = try {
        val obj = JsonParser.parseString(json).asJsonObject
        obj.entrySet().associate { (k, v) ->
            k to (v?.takeIf { !it.isJsonNull }?.asString ?: "")
        }.filterValues { it.isNotBlank() }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun epochMillisToLocalDateString(epochMs: Long): String {
        val instant = java.time.Instant.ofEpochMilli(epochMs)
        return instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
    }
}
