package com.averycorp.prismtask.core.time

import com.averycorp.prismtask.data.preferences.StartOfDay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reactive Flow over the user's current logical date.
 *
 * Combines a Start-of-Day source with a wall-clock ticker that re-emits at
 * every logical-day boundary crossing. Backed by [TimeProvider] so tests
 * can drive virtual time without monkey-patching `Instant.now()`.
 *
 * Replaces the broken `MutableStateFlow(currentLocalDateString(hour))`
 * snapshot pattern previously used in `MedicationViewModel.todayDate` —
 * see `docs/audits/MEDICATION_SOD_BOUNDARY_AUDIT.md`.
 *
 * The helper takes the SoD source as a parameter (not a constructor
 * dependency) so the class itself depends only on [TimeProvider] and stays
 * out of the data/preferences layer. Callers wire their own
 * `taskBehaviorPreferences.getStartOfDay()` flow in.
 */
@Singleton
class LocalDateFlow @Inject constructor(private val timeProvider: TimeProvider) {
    /**
     * Emit the current logical [LocalDate] on subscription, then re-emit
     * at every logical-day boundary crossing. Re-keys when [sodSource]
     * emits a new Start-of-Day. Deduped — consecutive equal emissions are
     * suppressed.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(sodSource: Flow<StartOfDay>): Flow<LocalDate> =
        sodSource.flatMapLatest { sod ->
            flow {
                while (currentCoroutineContext().isActive) {
                    val now = timeProvider.now()
                    val zone = timeProvider.zone()
                    emit(DayBoundary.logicalDate(now, sod.hour, sod.minute, zone))
                    val nextStart = DayBoundary
                        .nextLogicalDayStart(now, sod.hour, sod.minute, zone)
                        .toEpochMilli()
                    delay((nextStart - now.toEpochMilli()).coerceAtLeast(1L))
                }
            }
        }.distinctUntilChanged()

    /** ISO `yyyy-MM-dd` form of [observe]. */
    fun observeIsoString(sodSource: Flow<StartOfDay>): Flow<String> =
        observe(sodSource).map { it.toString() }
}
