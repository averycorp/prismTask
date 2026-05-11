package com.averycorp.prismtask.data.remote.api

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit binding for the backend-mediated Google Calendar sync surface.
 * All endpoints require the caller to hold a valid Firebase ID token
 * (attached by the shared OkHttp auth interceptor). The backend is the
 * sole custodian of the user's Google OAuth refresh token.
 */
interface CalendarBackendApi {
    @GET("api/v1/integrations/calendar/authorize")
    suspend fun getAuthorizationUrl(): CalendarAuthorizeResponse

    @DELETE("api/v1/integrations/calendar/disconnect")
    suspend fun disconnect()

    @GET("api/v1/integrations/calendar/status")
    suspend fun status(): CalendarStatusResponse

    @GET("api/v1/calendar/calendars")
    suspend fun listCalendars(): CalendarListResponse

    @POST("api/v1/calendar/sync/push")
    suspend fun pushTask(
        @Body request: CalendarPushRequest
    ): CalendarPushResponse

    @DELETE("api/v1/calendar/sync/push/{taskId}")
    suspend fun deleteTaskEvent(
        @Path("taskId") taskId: Long,
        @Query("calendar_id") calendarId: String,
        @Query("event_id") eventId: String
    )

    @POST("api/v1/calendar/sync/pull")
    suspend fun pullEvents(
        @Body request: EventsPullRequest
    ): EventsPullResponse

    @POST("api/v1/calendar/sync/full")
    suspend fun fullSync()

    @GET("api/v1/calendar/events/today")
    suspend fun listTodayEvents(
        @Query("start") startMillis: Long,
        @Query("end") endMillis: Long,
        @Query("limit") limit: Int
    ): EventsListResponse

    @GET("api/v1/calendar/events/search")
    suspend fun searchEvents(
        @Query("pattern") pattern: String,
        @Query("calendar_id") calendarId: String = "primary",
        @Query("time_min") timeMin: Long? = null,
        @Query("time_max") timeMax: Long? = null
    ): EventSearchResponse

    @GET("api/v1/calendar/settings")
    suspend fun getSettings(): CalendarSettingsPayload

    @PUT("api/v1/calendar/settings")
    suspend fun updateSettings(
        @Body payload: CalendarSettingsPayload
    ): CalendarSettingsPayload
}

// --- Request / response DTOs -------------------------------------------------

data class CalendarAuthorizeResponse(val url: String)

data class CalendarStatusResponse(val connected: Boolean, val email: String? = null)

data class CalendarListResponse(val calendars: List<CalendarListItem>)

data class CalendarListItem(
    val id: String,
    val name: String,
    val color: String? = null,
    val primary: Boolean = false,
    val writable: Boolean = true
)

data class CalendarPushRequest(
    val taskId: Long,
    val title: String,
    val description: String? = null,
    val notes: String? = null,
    val dueDateMillis: Long? = null,
    val dueTimeMillis: Long? = null,
    val scheduledStartMillis: Long? = null,
    val estimatedDurationMinutes: Int? = null,
    val priority: Int = 0,
    val isCompleted: Boolean = false,
    val knownEventId: String? = null,
    val knownCalendarId: String? = null
)

data class CalendarPushResponse(val eventId: String? = null, val calendarId: String? = null, val etag: String? = null)

data class EventsPullRequest(val calendarIds: List<String>)

data class EventsPullResponse(val created: Int = 0, val updated: Int = 0, val deleted: Int = 0)

data class EventsListResponse(val events: List<CalendarEventDto>)

data class CalendarEventDto(
    val id: String,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean = false,
    val calendarId: String? = null
)

data class EventSearchResponse(val events: List<EventSearchItem>)

data class EventSearchItem(val id: String, val summary: String, val startMillis: Long, val allDay: Boolean = false)

data class CalendarSettingsPayload(
    val enabled: Boolean = false,
    val direction: String = "both",
    val frequency: String = "15min",
    val targetCalendarId: String = "primary",
    val displayCalendarIds: List<String> = emptyList(),
    val showEvents: Boolean = true,
    val syncCompletedTasks: Boolean = false
)
