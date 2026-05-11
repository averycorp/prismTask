package com.averycorp.prismtask.data.repository

import android.util.Log
import com.averycorp.prismtask.data.calendar.CalendarEventInfo
import com.averycorp.prismtask.data.calendar.CalendarSyncPreferences
import com.averycorp.prismtask.data.calendar.DIRECTION_PULL
import com.averycorp.prismtask.data.calendar.DIRECTION_PUSH
import com.averycorp.prismtask.data.local.dao.CalendarSyncDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.entity.CalendarSyncEntity
import com.averycorp.prismtask.data.remote.api.CalendarBackendApi
import com.averycorp.prismtask.data.remote.api.CalendarPushRequest
import com.averycorp.prismtask.data.remote.api.CalendarSettingsPayload
import com.averycorp.prismtask.data.remote.api.EventsPullRequest
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android-side facade for the backend-mediated Google Calendar sync
 * surface. Holds a tiny HTTP client, mirrors sync settings to the backend
 * on change, and writes/reads [CalendarSyncEntity] rows so push/pull can
 * correlate local tasks with their Google event IDs and avoid sync loops.
 */
@Singleton
class CalendarSyncRepository
@Inject
constructor(
    private val api: CalendarBackendApi,
    private val calendarSyncDao: CalendarSyncDao,
    private val taskDao: TaskDao,
    private val calendarSyncPreferences: CalendarSyncPreferences
) : CalendarEventRepository {
    enum class PushOutcome { SUCCESS, RETRYABLE, AUTH_REQUIRED, DISABLED }

    suspend fun pushTask(taskId: Long): PushOutcome {
        if (!calendarSyncPreferences.getCalendarSyncEnabled()) return PushOutcome.DISABLED
        // Respect the user's sync direction: pull-only mode must never
        // create or edit events in their calendar.
        if (calendarSyncPreferences.getSyncDirectionOnce() == DIRECTION_PULL) {
            return PushOutcome.DISABLED
        }
        val task = taskDao.getTaskByIdOnce(taskId) ?: return PushOutcome.SUCCESS
        val syncCompleted = calendarSyncPreferences.getSyncCompletedTasks().first()
        if (task.isCompleted && !syncCompleted) {
            // Treat like a delete — drop the event if one exists.
            return deleteTaskEvent(taskId)
        }
        if (task.dueDate == null) {
            // Task with no due date cannot be represented as an event.
            return deleteTaskEvent(taskId)
        }
        return try {
            val mapping = calendarSyncDao.getByTaskId(taskId)
            val response = api.pushTask(
                CalendarPushRequest(
                    taskId = taskId,
                    title = task.title,
                    description = task.description,
                    notes = task.notes,
                    dueDateMillis = task.dueDate,
                    dueTimeMillis = task.dueTime,
                    scheduledStartMillis = task.scheduledStartTime,
                    estimatedDurationMinutes = task.estimatedDuration,
                    priority = task.priority,
                    isCompleted = task.isCompleted,
                    knownEventId = mapping?.calendarEventId,
                    knownCalendarId = mapping?.calendarId
                )
            )
            if (response.eventId.isNullOrBlank() || response.calendarId.isNullOrBlank()) {
                // Backend deliberately did nothing (e.g. task has no due date).
                if (mapping != null) calendarSyncDao.deleteByTaskId(taskId)
                return PushOutcome.SUCCESS
            }
            calendarSyncDao.upsert(
                CalendarSyncEntity(
                    taskId = taskId,
                    calendarEventId = response.eventId,
                    calendarId = response.calendarId,
                    lastSyncedAt = System.currentTimeMillis(),
                    lastSyncedVersion = task.updatedAt,
                    syncState = "SYNCED",
                    etag = response.etag
                )
            )
            PushOutcome.SUCCESS
        } catch (e: HttpException) {
            if (e.code() == 401 || e.code() == 403) {
                Log.w(TAG, "Auth required for push taskId=$taskId", e)
                PushOutcome.AUTH_REQUIRED
            } else if (e.code() in 500..599) {
                PushOutcome.RETRYABLE
            } else {
                Log.w(TAG, "Non-retryable push failure taskId=$taskId code=${e.code()}")
                PushOutcome.RETRYABLE
            }
        } catch (e: IOException) {
            PushOutcome.RETRYABLE
        }
    }

    suspend fun deleteTaskEvent(taskId: Long): PushOutcome {
        val mapping = calendarSyncDao.getByTaskId(taskId) ?: return PushOutcome.SUCCESS
        if (!calendarSyncPreferences.getCalendarSyncEnabled()) {
            calendarSyncDao.deleteByTaskId(taskId)
            return PushOutcome.SUCCESS
        }
        if (calendarSyncPreferences.getSyncDirectionOnce() == DIRECTION_PULL) {
            // Pull-only: forget the local mapping but don't touch the
            // user's calendar.
            calendarSyncDao.deleteByTaskId(taskId)
            return PushOutcome.DISABLED
        }
        return try {
            api.deleteTaskEvent(taskId, mapping.calendarId, mapping.calendarEventId)
            calendarSyncDao.deleteByTaskId(taskId)
            PushOutcome.SUCCESS
        } catch (e: HttpException) {
            when (e.code()) {
                401, 403 -> PushOutcome.AUTH_REQUIRED
                404, 410 -> {
                    calendarSyncDao.deleteByTaskId(taskId)
                    PushOutcome.SUCCESS
                }
                else -> PushOutcome.RETRYABLE
            }
        } catch (e: IOException) {
            PushOutcome.RETRYABLE
        }
    }

    suspend fun pullUpdates(): Result<PullSummary> {
        if (!calendarSyncPreferences.getCalendarSyncEnabled()) {
            return Result.success(PullSummary.EMPTY)
        }
        // Push-only mode: skip pulls entirely so the user's tasks never
        // get overwritten by remote edits they didn't ask to sync.
        if (calendarSyncPreferences.getSyncDirectionOnce() == DIRECTION_PUSH) {
            return Result.success(PullSummary.EMPTY)
        }
        val displayIds = calendarSyncPreferences.getSelectedDisplayCalendarIds().first()
        val targetId = calendarSyncPreferences.getSyncCalendarIdOnce()
        val ids = (displayIds + targetId).filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return Result.success(PullSummary.EMPTY)
        return try {
            val response = api.pullEvents(EventsPullRequest(calendarIds = ids))
            calendarSyncPreferences.setLastSyncTimestamp(System.currentTimeMillis())
            Result.success(
                PullSummary(
                    created = response.created,
                    updated = response.updated,
                    deleted = response.deleted
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Pull failed", e)
            Result.failure(e)
        }
    }

    suspend fun syncNow(): Result<Unit> = try {
        api.fullSync()
        calendarSyncPreferences.setLastSyncTimestamp(System.currentTimeMillis())
        Result.success(Unit)
    } catch (e: Exception) {
        Log.w(TAG, "Manual sync failed", e)
        Result.failure(e)
    }

    suspend fun listCalendars(): Result<List<com.averycorp.prismtask.data.calendar.CalendarInfo>> = try {
        val response = api.listCalendars()
        Result.success(
            response.calendars.map { dto ->
                com.averycorp.prismtask.data.calendar.CalendarInfo(
                    id = dto.id,
                    name = dto.name,
                    color = dto.color ?: "#4285F4",
                    isPrimary = dto.primary
                )
            }
        )
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Pushes the user's current sync preferences to the backend. Called
     * on settings-screen open + whenever a toggle changes so the backend
     * periodic job respects the user's current direction / frequency /
     * target-calendar choices.
     */
    suspend fun syncSettingsToBackend(): Result<Unit> = try {
        val payload = CalendarSettingsPayload(
            enabled = calendarSyncPreferences.getCalendarSyncEnabled(),
            direction = calendarSyncPreferences.getSyncDirectionOnce(),
            frequency = calendarSyncPreferences.getSyncFrequency().first(),
            targetCalendarId = calendarSyncPreferences.getSyncCalendarIdOnce(),
            displayCalendarIds = calendarSyncPreferences.getSelectedDisplayCalendarIds().first().toList(),
            showEvents = calendarSyncPreferences.getShowCalendarEvents().first(),
            syncCompletedTasks = calendarSyncPreferences.getSyncCompletedTasks().first()
        )
        api.updateSettings(payload)
        Result.success(Unit)
    } catch (e: Exception) {
        Log.w(TAG, "Settings push failed", e)
        Result.failure(e)
    }

    /**
     * Fuzzy search for events whose summary contains [pattern]. Used by
     * the habit reminder scheduler so it can find habit-linked calendar
     * events without holding the Calendar scope on the device.
     */
    suspend fun searchEventsBySummary(
        pattern: String,
        calendarId: String = "primary",
        timeMinMillis: Long? = null,
        timeMaxMillis: Long? = null
    ): List<com.averycorp.prismtask.data.remote.api.EventSearchItem> = try {
        api.searchEvents(
            pattern = pattern,
            calendarId = calendarId,
            timeMin = timeMinMillis,
            timeMax = timeMaxMillis
        ).events
    } catch (e: Exception) {
        Log.w(TAG, "Event search failed", e)
        emptyList()
    }

    override suspend fun getTodayUpcomingEvents(
        now: Long,
        dayEnd: Long,
        limit: Int
    ): List<CalendarEventInfo> {
        if (!calendarSyncPreferences.getShowCalendarEvents().first()) return emptyList()
        return try {
            val response = api.listTodayEvents(startMillis = now, endMillis = dayEnd, limit = limit)
            response.events.map { dto ->
                CalendarEventInfo(
                    title = dto.title,
                    startMillis = dto.startMillis,
                    endMillis = dto.endMillis,
                    isAllDay = dto.allDay
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Today events fetch failed", e)
            emptyList()
        }
    }

    data class PullSummary(val created: Int, val updated: Int, val deleted: Int) {
        companion object {
            val EMPTY = PullSummary(0, 0, 0)
        }
    }

    private companion object {
        const val TAG = "CalendarSyncRepo"
    }
}
