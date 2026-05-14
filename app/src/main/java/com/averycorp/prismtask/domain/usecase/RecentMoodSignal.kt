package com.averycorp.prismtask.domain.usecase

import com.averycorp.prismtask.data.repository.MoodEnergyRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mental-Health-First § G7 — read-only mood signal consumed by notification
 * schedulers to suppress non-critical nudge cadence after a recent low-mood
 * log.
 *
 * Per the audit's Phase 4 answer, v1 ships **fixed thresholds**:
 *  - window: last 48 hours
 *  - floor:  mood ≤ 2 (out of 5)
 *
 * The schedulers depend on this consumer surface, not on
 * `MoodCorrelationEngine` directly — keeping the analytics engine read-only
 * and centralising the gate behaviour here. Medication reminders never
 * consult this gate; adherence safety wins over cadence reduction.
 *
 * Silent deferral by design: when the gate trips, the scheduler simply
 * skips this firing — there is no follow-up notification telling the user
 * "we paused this," and no global deferred queue. The next scheduled
 * firing re-evaluates the gate from scratch.
 */
@Singleton
class RecentMoodSignal(
    private val moodEnergyRepository: MoodEnergyRepository,
    private val clock: NowClock
) {
    /**
     * Production constructor — Hilt-injected and pinned to
     * [NowClock.SYSTEM]. The clock seam is constructor-injected (not a
     * default param) because Hilt would otherwise try to resolve a
     * binding for it and fail. Tests instantiate the primary
     * constructor directly with a fixed-instant [NowClock].
     */
    @Inject
    constructor(moodEnergyRepository: MoodEnergyRepository) :
        this(moodEnergyRepository, NowClock.SYSTEM)

    /**
     * Returns true iff at least one mood entry within the last [hours]
     * wall-clock hours has `mood <= ` [LOW_MOOD_CEILING].
     *
     * Defaults match the audit's fixed v1 thresholds. The [hours]
     * parameter is exposed so callers can document the window inline at
     * each call site — making the doctrine ("48-hour window") trivially
     * grep-able when it changes — but every production call site passes
     * [DEFAULT_WINDOW_HOURS].
     */
    suspend fun isLowMoodWithin(hours: Long = DEFAULT_WINDOW_HOURS): Boolean {
        if (hours <= 0L) return false
        val now = clock.nowMillis()
        val cutoff = now - hours * MILLIS_PER_HOUR
        return moodEnergyRepository.hasLowMoodSince(
            moodCeiling = LOW_MOOD_CEILING,
            sinceCreatedAtMillis = cutoff
        )
    }

    /**
     * Tiny injectable clock seam so unit tests can pin "now" without
     * stubbing `System.currentTimeMillis`. Kept inside the same file so
     * the gate's single externally-visible API is `RecentMoodSignal`.
     */
    fun interface NowClock {
        fun nowMillis(): Long

        companion object {
            val SYSTEM: NowClock = NowClock { System.currentTimeMillis() }
        }
    }

    companion object {
        /** v1 fixed threshold — audit § G7 Phase 4. */
        const val LOW_MOOD_CEILING: Int = 2

        /** v1 fixed window — audit § G7 Phase 4. */
        const val DEFAULT_WINDOW_HOURS: Long = 48L

        internal const val MILLIS_PER_HOUR: Long = 60L * 60L * 1000L
    }
}
