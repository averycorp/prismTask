package com.averycorp.prismtask.workers

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.averycorp.prismtask.data.calendar.CalendarSyncPreferences
import com.averycorp.prismtask.data.calendar.FREQUENCY_15MIN
import com.averycorp.prismtask.data.calendar.FREQUENCY_HOURLY
import com.averycorp.prismtask.data.calendar.FREQUENCY_MANUAL
import com.averycorp.prismtask.data.calendar.FREQUENCY_REALTIME
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules (or cancels) the periodic [CalendarSyncWorker] based on the
 * user's `sync_frequency` preference. Called on app startup and whenever
 * the user changes sync frequency or toggles the master switch in
 * Settings.
 */
@Singleton
class CalendarSyncScheduler
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val calendarSyncPreferences: CalendarSyncPreferences
) {
    /**
     * Inspects current preferences and (re)schedules [CalendarSyncWorker]
     * accordingly. Safe to call repeatedly — uses `UPDATE` policy so
     * duplicate calls don't pile up jobs.
     *
     * Callers are expected to run this from a coroutine scope (the
     * application scope on startup, or `viewModelScope` from Settings).
     * DataStore reads suspend, so this stays off the main thread by
     * the caller's dispatcher rather than wrapping in a runBlocking.
     */
    suspend fun applyPreferences() {
        val enabled = calendarSyncPreferences.getCalendarSyncEnabled()
        if (!enabled) {
            cancel()
            return
        }
        val frequency = calendarSyncPreferences.getSyncFrequency().first()
        val intervalMinutes = when (frequency) {
            FREQUENCY_REALTIME, FREQUENCY_15MIN -> 15L
            FREQUENCY_HOURLY -> 60L
            FREQUENCY_MANUAL -> null
            else -> 15L
        }
        if (intervalMinutes == null) {
            cancel()
            return
        }
        val request = PeriodicWorkRequestBuilder<CalendarSyncWorker>(
            intervalMinutes,
            TimeUnit.MINUTES
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(UNIQUE_NAME)
            .build()
        try {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule calendar sync worker", e)
        }
    }

    fun cancel() {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel calendar sync worker", e)
        }
    }

    companion object {
        const val UNIQUE_NAME = "gcal-sync"
        private const val TAG = "CalendarSyncScheduler"
    }
}
