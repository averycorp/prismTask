package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.RestDayDao
import com.averycorp.prismtask.data.local.entity.RestDayEntity
import com.averycorp.prismtask.data.preferences.TaskBehaviorPreferences
import com.averycorp.prismtask.util.DayBoundary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rest Day primitive repository (Mental-Health-First audit § G3).
 *
 * Wraps [RestDayDao] with Start-of-Day-aware date resolution so callers
 * don't have to thread the day-start hour through every call site. Any
 * read or write here resolves "today" via
 * [DayBoundary.currentLocalDateString] using the user's configured SoD,
 * matching every other SoD-aware surface (Today task filter, habit
 * completion, NLP date parsing).
 *
 * Rest days compose with the forgiveness-first streak core (treated as
 * "kept" by definition) and gate non-medication notification firing.
 * Medications fire unaffected — see [com.averycorp.prismtask.notifications.NotificationHelper]
 * for the actual fan-out.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class RestDayRepository
@Inject
constructor(
    private val restDayDao: RestDayDao,
    private val taskBehaviorPreferences: TaskBehaviorPreferences
) {
    /**
     * Flow of "is today (logical, SoD-aware) a rest day?". Re-emits when
     * the underlying row is added/removed AND when SoD prefs change so
     * crossing the SoD boundary at 04:00 flips the flag without a manual
     * refresh.
     */
    fun observeIsRestDayToday(now: () -> Long = System::currentTimeMillis): Flow<Boolean> =
        taskBehaviorPreferences.getStartOfDay().flatMapLatest { sod ->
            val isoDate = DayBoundary.currentLocalDateString(
                dayStartHour = sod.hour,
                now = now(),
                dayStartMinute = sod.minute
            )
            restDayDao.observeIsRestDay(isoDate)
        }

    /**
     * One-shot read of "is today (logical) a rest day?". Used by
     * schedulers / workers that fire once at a moment in time and don't
     * need a Flow. Resolves the date via SoD prefs to match the
     * UI-facing flow.
     */
    suspend fun isRestDayToday(now: Long = System.currentTimeMillis()): Boolean {
        val sod = taskBehaviorPreferences.getStartOfDay().first()
        val isoDate = DayBoundary.currentLocalDateString(
            dayStartHour = sod.hour,
            now = now,
            dayStartMinute = sod.minute
        )
        return restDayDao.isRestDay(isoDate)
    }

    /** Look up a specific [LocalDate] (already resolved by the caller). */
    suspend fun isRestDay(date: LocalDate): Boolean =
        restDayDao.isRestDay(date.toString())

    /**
     * Snapshot of every rest-day date currently in the store, as a
     * `Set<LocalDate>` keyed off the ISO column. Used by the streak
     * core via [com.averycorp.prismtask.domain.usecase.DailyForgivenessStreakCore.calculate]
     * to treat the days as "kept" without consuming the grace window.
     */
    suspend fun getAllRestDays(): Set<LocalDate> =
        restDayDao.getAllDatesOnce().mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet()

    fun observeAllRestDays(): Flow<Set<LocalDate>> =
        restDayDao.observeAllDates().map { rows ->
            rows.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet()
        }

    /**
     * Mark today (logical, SoD-aware) as a rest day. Idempotent: the
     * underlying `INSERT OR IGNORE` makes a no-op of double-tapping the
     * Today-screen toggle.
     */
    suspend fun markTodayAsRestDay(now: Long = System.currentTimeMillis()) {
        val sod = taskBehaviorPreferences.getStartOfDay().first()
        val isoDate = DayBoundary.currentLocalDateString(
            dayStartHour = sod.hour,
            now = now,
            dayStartMinute = sod.minute
        )
        restDayDao.upsert(
            RestDayEntity(
                date = isoDate,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    /** Unmark today (logical, SoD-aware). No-op if today wasn't a rest day. */
    suspend fun unmarkTodayAsRestDay(now: Long = System.currentTimeMillis()) {
        val sod = taskBehaviorPreferences.getStartOfDay().first()
        val isoDate = DayBoundary.currentLocalDateString(
            dayStartHour = sod.hour,
            now = now,
            dayStartMinute = sod.minute
        )
        restDayDao.deleteByDate(isoDate)
    }

    /**
     * Mark every date in `from..to` (inclusive) as a rest day. Backs the
     * Streak-Pause feature (Settings → Forgiveness Streak → Pause): the
     * user picks a date range, and every day in that range counts as
     * "kept" in the forgiveness-first streak walk without consuming
     * grace. Idempotent via the DAO's `INSERT OR IGNORE`.
     */
    suspend fun markRangeAsRestDay(from: LocalDate, to: LocalDate) {
        if (to.isBefore(from)) return
        val createdAt = System.currentTimeMillis()
        var d = from
        while (!d.isAfter(to)) {
            restDayDao.upsert(RestDayEntity(date = d.toString(), createdAt = createdAt))
            d = d.plusDays(1)
        }
    }

    /** Clear every rest-day marker in `from..to`. Used to cancel an active pause. */
    suspend fun unmarkRangeAsRestDay(from: LocalDate, to: LocalDate) {
        if (to.isBefore(from)) return
        var d = from
        while (!d.isAfter(to)) {
            restDayDao.deleteByDate(d.toString())
            d = d.plusDays(1)
        }
    }
}
