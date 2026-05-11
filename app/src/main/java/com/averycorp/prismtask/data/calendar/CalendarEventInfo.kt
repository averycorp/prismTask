package com.averycorp.prismtask.data.calendar

/**
 * Lightweight representation of a single calendar event used by the
 * Morning Check-In "Calendar Glance" step and the Calendar widget. After
 * the device-calendar path was removed, these events now originate from
 * the backend-mediated Google Calendar sync (see
 * `backend/app/services/calendar_service.py`). Kept intentionally small —
 * only the fields the UI actually renders.
 */
data class CalendarEventInfo(val title: String, val startMillis: Long, val endMillis: Long, val isAllDay: Boolean) {
    val durationMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0)
}
