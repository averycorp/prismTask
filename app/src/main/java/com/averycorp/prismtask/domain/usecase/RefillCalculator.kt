package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.local.entity.MedicationEntity
import com.averycorp.prismtask.data.local.entity.MedicationRefillEntity
import com.averycorp.prismtask.data.preferences.RefillUrgencyConfig
import kotlin.math.max

/**
 * How a medication's refill forecast reads out in the UI.
 */
enum class RefillUrgency {
    HEALTHY, // > 7 days of pills remaining
    UPCOMING, // 3-7 days
    URGENT, // < 3 days or already out
    OUT_OF_STOCK
}

/**
 * Result of a refill forecast computation.
 *
 * @property daysRemaining Number of whole days of pills still on hand at
 *                         the current daily dosage.
 * @property refillDateMillis Calendar date (midnight-normalized in caller's
 *                            timezone) when the user is expected to run out.
 * @property reminderDateMillis When to fire a "time to refill" reminder —
 *                              the refill date minus [MedicationRefillEntity.reminderDaysBefore].
 * @property urgency UI-facing bucket (HEALTHY / UPCOMING / URGENT / OUT_OF_STOCK).
 */
data class RefillForecast(val daysRemaining: Int, val refillDateMillis: Long, val reminderDateMillis: Long, val urgency: RefillUrgency)

/**
 * Pure-function refill date + adherence calculator (v1.4.0 V10).
 *
 * Takes the existing [MedicationRefillEntity] shape (pill count, pills per
 * dose, doses per day, last refill date) and emits a forecast the UI can
 * render. Intentionally free of Android/Room dependencies so it's trivial
 * to unit-test deterministically.
 */
object RefillCalculator {
    private const val MILLIS_PER_DAY: Long = 24L * 60 * 60 * 1000

    fun forecast(
        refill: MedicationRefillEntity,
        now: Long = System.currentTimeMillis(),
        urgencyConfig: RefillUrgencyConfig = RefillUrgencyConfig()
    ): RefillForecast {
        val dailyUsage = max(1, refill.pillsPerDose * refill.dosesPerDay)
        val daysRemaining = (refill.pillCount / dailyUsage).coerceAtLeast(0)
        val anchor = refill.lastRefillDate ?: now
        val refillDate = anchor + daysRemaining.toLong() * MILLIS_PER_DAY
        val reminderDate = refillDate - refill.reminderDaysBefore.toLong() * MILLIS_PER_DAY
        val urgency = urgencyFor(daysRemaining, refill.pillCount, urgencyConfig)
        return RefillForecast(
            daysRemaining = daysRemaining,
            refillDateMillis = refillDate,
            reminderDateMillis = reminderDate,
            urgency = urgency
        )
    }

    /**
     * Decrement the pill count by one daily dose. Returns a new entity —
     * callers persist it via the DAO. If the decrement would go negative
     * we floor at zero.
     */
    fun applyDailyDose(refill: MedicationRefillEntity, now: Long = System.currentTimeMillis()): MedicationRefillEntity {
        val dose = refill.pillsPerDose * refill.dosesPerDay
        val newCount = (refill.pillCount - dose).coerceAtLeast(0)
        return refill.copy(pillCount = newCount, updatedAt = now)
    }

    /**
     * Reset the pill count to [newSupply] and stamp `lastRefillDate` to [now].
     * Used by the "Refilled" button in the medication UI.
     */
    fun applyRefill(
        refill: MedicationRefillEntity,
        newSupply: Int,
        now: Long = System.currentTimeMillis()
    ): MedicationRefillEntity = refill.copy(
        pillCount = newSupply.coerceAtLeast(0),
        lastRefillDate = now,
        updatedAt = now
    )

    /**
     * Adherence rate: (doses taken) / (doses expected). Expected is the
     * number of calendar days in the range multiplied by doses per day.
     * Returns a float in [0f, 1f]; 0 when the period is empty.
     */
    fun adherenceRate(
        refill: MedicationRefillEntity,
        dosesTaken: Int,
        rangeDays: Int
    ): Float {
        if (rangeDays <= 0 || refill.dosesPerDay <= 0) return 0f
        val expected = rangeDays * refill.dosesPerDay
        if (expected <= 0) return 0f
        return (dosesTaken.toFloat() / expected.toFloat()).coerceIn(0f, 1f)
    }

    private fun urgencyFor(
        daysRemaining: Int,
        pillCount: Int,
        config: RefillUrgencyConfig = RefillUrgencyConfig()
    ): RefillUrgency = when {
        pillCount <= 0 -> RefillUrgency.OUT_OF_STOCK
        daysRemaining < config.urgentDays -> RefillUrgency.URGENT
        daysRemaining <= config.upcomingDays -> RefillUrgency.UPCOMING
        else -> RefillUrgency.HEALTHY
    }

    // --- MedicationEntity overloads (v1.4 top-level refactor) ---
    //
    // The top-level [MedicationEntity] subsumed the old
    // [MedicationRefillEntity] fields inline. Refill tracking on the new
    // entity is optional (pillCount is nullable), so the MedicationEntity
    // overloads return null / no-op when pill tracking isn't enabled.

    /**
     * Refill forecast for a [MedicationEntity]. Returns `null` when the
     * medication doesn't participate in refill tracking
     * (`pillCount == null`).
     */
    fun forecast(
        med: MedicationEntity,
        now: Long = System.currentTimeMillis(),
        urgencyConfig: RefillUrgencyConfig = RefillUrgencyConfig()
    ): RefillForecast? {
        val pillCount = med.pillCount ?: return null
        val dailyUsage = max(1, med.pillsPerDose * med.dosesPerDay)
        val daysRemaining = (pillCount / dailyUsage).coerceAtLeast(0)
        val anchor = med.lastRefillDate ?: now
        val refillDate = anchor + daysRemaining.toLong() * MILLIS_PER_DAY
        val reminderDate = refillDate - med.reminderDaysBefore.toLong() * MILLIS_PER_DAY
        val urgency = urgencyFor(daysRemaining, pillCount, urgencyConfig)
        return RefillForecast(
            daysRemaining = daysRemaining,
            refillDateMillis = refillDate,
            reminderDateMillis = reminderDate,
            urgency = urgency
        )
    }

    /**
     * Decrement the pill count by one daily dose on a [MedicationEntity].
     * Returns the medication unchanged when `pillCount` is null
     * (no refill tracking) — caller should gate on nullability.
     */
    fun applyDailyDose(
        med: MedicationEntity,
        now: Long = System.currentTimeMillis()
    ): MedicationEntity {
        val pillCount = med.pillCount ?: return med
        val dose = med.pillsPerDose * med.dosesPerDay
        val newCount = (pillCount - dose).coerceAtLeast(0)
        return med.copy(pillCount = newCount, updatedAt = now)
    }

    /**
     * Reset the pill count to [newSupply] and stamp `lastRefillDate`.
     * Works on every medication regardless of prior tracking state —
     * calling this enables refill tracking on a medication that didn't
     * have `pillCount` set before.
     */
    fun applyRefill(
        med: MedicationEntity,
        newSupply: Int,
        now: Long = System.currentTimeMillis()
    ): MedicationEntity = med.copy(
        pillCount = newSupply.coerceAtLeast(0),
        lastRefillDate = now,
        updatedAt = now
    )
}
