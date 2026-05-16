# ADR: Calendar Sync Architecture

Status: Accepted
Date: 2026-04-16

## Context

PrismTask shipped with a device-calendar sync path built on `CalendarContract`.
A separate Google Calendar OAuth scope and `CalendarManager` also existed on
Android, used as a read-only picker. The UI in `GoogleCalendarSection` exposed
eight toggles (enabled, direction, frequency, target calendar, display
calendars, show events, sync completed, last-synced timestamp) but the
server-side sync path was stubbed.

The April 16 2026 audit (`CALENDAR_SYNC_AUDIT.md`) confirmed:
- `CalendarSyncEntity` and DAO exist but only the device path populates them.
- `CalendarSyncPreferences` already has all eight DataStore keys wired.
- `CalendarManager.getUserCalendars()` already hits the Google Calendar API
  directly from Android for the picker.
- Since April 2026 the `IntegrationSource.CALENDAR` enum is consumed by
  `backend/app/services/integrations/calendar_integration.py` and
  `backend/app/routers/calendar.py`.
- Gmail integration at `backend/app/services/integrations/gmail_integration.py`
  is a template for server-side OAuth plus polling.

## Decision

1. **Backend-mediated sync (Option B).** The FastAPI backend holds the user's
   Google Calendar OAuth refresh token (encrypted at rest), talks to the
   Google Calendar API v3, and exposes sync endpoints consumed by Android.
   Android no longer stores Google Calendar OAuth tokens; it holds a Firebase
   ID token for talking to our backend.
2. **Hard-deprecate the device calendar path this sprint.** Delete
   `CalendarSyncService`, `CalendarPreferences`, `DeviceCalendarSection`, the
   `READ_CALENDAR` / `WRITE_CALENDAR` permissions, and the nine call sites in
   `TaskRepository.kt`. Preserve only the `toUtcDayStartMillis()` helper and
   the all-day detection logic, relocated to
   `data/calendar/util/CalendarTimeUtil.kt`.
3. **No UI gating.** `GoogleCalendarSection` ships live. No "Coming soon"
   chips, no disabled toggles. The product owner has accepted the launch-week
   trust risk and the Play Store policy risk.

## Alternatives considered

- **Client-side direct (keep CalendarManager, add write operations):**
  rejected. We plan cross-platform parity (iOS Q3 2026) and need a single
  sync engine. Doing OAuth refresh, quota management, and loop prevention
  twice (Android + iOS) is wasted work. Backend-mediated also keeps the
  calendar scope off the Android client for users who only want dashboard
  reads.
- **Keep device calendar as a feature-flagged fallback:** rejected. Parallel
  sync paths invite drift. Hard deprecation is one week of pain instead of
  six months of maintenance.
- **Soften rollout with "Coming soon" chips:** rejected by product owner. The
  UI toggles ship live; the backend is expected to land in the same PR.

## Scope

### In scope this sprint
- Backend OAuth (`google-auth-oauthlib`), token storage, refresh.
- Backend services for list/create/update/delete/incremental-sync events.
- Backend sync endpoints (push single task, pull changes, full resync).
- Backend `CalendarSyncSettings` table + Alembic migration.
- Android `CalendarSyncRepository` + Retrofit interface.
- Android `CalendarSyncWorker` and push worker; wire into
  `TaskRepository.kt` at the nine sites where `calendarSyncService` was
  removed.
- Sync-loop prevention via `calendarEventId` persistence + Google
  `extendedProperties.private.prismtask_source = "true"` marker.
- Route `MedicationReminderScheduler` through the backend so the Android
  Google scope becomes optional.
- AI time-block planner: replace placeholder empty list with real events
  fetched by the backend (`backend/app/routers/ai.py:397-399`).

### Out of scope — captured in `docs/FUTURE-CALENDAR-WORK.md`
- Outlook, iCloud, CalDAV.
- Recurring event series edits beyond basic RRULE copy-through.
- Meeting invites / attendees.
- iOS client (weeks 13–16 of roadmap).
- Multiple Google accounts per user.
- Color sync between PrismTask priorities and Google event colors.
- Webhook push notifications (`events.watch`) — stubbed behind periodic
  sync in this sprint.

## Accepted risks

- If the new backend path fails at launch, users lose calendar sync with no
  device fallback. Product owner acknowledged.
- The UI toggles are live before the backend is load-tested against Google
  quotas. Product owner acknowledged.
- Play Store review may challenge the new Google Calendar OAuth scope on
  the server side. We're reusing the Gmail-integration pattern which has
  passed review before.

## Open questions / future work

- Is APScheduler the right periodic runner, or does the existing backend
  already use a different scheduler? Confirmed: APScheduler via
  `AsyncIOScheduler` — see Step 7 of the implementation plan.
- Webhook (`events.watch`) + Redis-backed fan-out for "real-time" frequency
  is stubbed. The 15-minute periodic sync covers real-time today.
- User timezone: currently the backend uses `zoneinfo` with a per-user
  timezone column. Timezone inference at sign-up time is deferred.
