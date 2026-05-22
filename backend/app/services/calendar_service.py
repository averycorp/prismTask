"""Google Calendar API service.

Wraps the Google Calendar client behind a small async-friendly facade
that the calendar router and the periodic sync job call into. Handles:

- Listing the user's calendars.
- Creating / updating / deleting events that represent PrismTask tasks.
- Incremental pull via `syncToken`, with 410-GONE fallback to full pull.
- Task ↔ EventDateTime translation that mirrors Android's
  `CalendarTimeUtil` semantics so existing Android rows round-trip.

The router layer is responsible for authorization and persistence; this
module focuses on the Google API surface.
"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any
from zoneinfo import ZoneInfo

try:
    from googleapiclient.discovery import build as google_build
    from googleapiclient.errors import HttpError
except ImportError:  # pragma: no cover
    google_build = None  # type: ignore
    HttpError = Exception  # type: ignore

from sqlalchemy.ext.asyncio import AsyncSession

from app.services.integrations.calendar_integration import load_credentials

logger = logging.getLogger(__name__)

PRISMTASK_EXTENDED_PROPERTY = "prismtask_source"
DEFAULT_LOOKBACK_DAYS = 30
DEFAULT_LOOKAHEAD_DAYS = 365


@dataclass
class CalendarInfo:
    id: str
    name: str
    color: str | None
    primary: bool
    writable: bool


@dataclass
class EventResult:
    event_id: str
    calendar_id: str
    etag: str | None


@dataclass
class PullResult:
    created: int = 0
    updated: int = 0
    deleted: list[str] = None  # type: ignore[assignment]
    next_sync_token: str | None = None

    def __post_init__(self) -> None:
        if self.deleted is None:
            self.deleted = []


def _build_service(credentials: Any):
    if google_build is None:
        raise RuntimeError("google-api-python-client is not installed")
    return google_build(
        "calendar", "v3", credentials=credentials, cache_discovery=False
    )


async def list_calendars(db: AsyncSession, user_id: int) -> list[CalendarInfo]:
    creds = await load_credentials(db, user_id)
    if creds is None:
        return []
    service = _build_service(creds)
    response = service.calendarList().list(minAccessRole="reader").execute()
    items = response.get("items", [])
    out: list[CalendarInfo] = []
    for item in items:
        role = item.get("accessRole", "reader")
        out.append(
            CalendarInfo(
                id=item["id"],
                name=item.get("summary", item["id"]),
                color=item.get("backgroundColor"),
                primary=bool(item.get("primary")),
                writable=role in {"owner", "writer"},
            )
        )
    out.sort(key=lambda c: (not c.primary, c.name.lower()))
    return out


def _task_to_event_datetimes(
    task: dict[str, Any], tz: ZoneInfo
) -> tuple[dict[str, Any], dict[str, Any]]:
    """Returns (start, end) Google EventDateTime dicts for *task*.

    Mirrors the Android `CalendarTimeUtil` rules:
    - Task with no `due_time` and no `scheduled_start_time` → all-day
      (`date` only), end is start + 1 day (exclusive).
    - Task with either of those → timed event at local timezone.
    """
    due_date = task.get("due_date")
    if due_date is None:
        raise ValueError("Task has no due_date; nothing to push")

    due_time = task.get("due_time")
    scheduled_start = task.get("scheduled_start_time")
    if (due_time in (None, 0)) and (scheduled_start in (None, 0)):
        local_date = datetime.fromtimestamp(due_date / 1000, tz=tz).date()
        return (
            {"date": local_date.isoformat()},
            {"date": (local_date + timedelta(days=1)).isoformat()},
        )
    if scheduled_start:
        start_millis = scheduled_start
    else:
        start_millis = due_date + (due_time or 0)
    duration_minutes = task.get("estimated_duration") or 60
    end_millis = start_millis + duration_minutes * 60_000
    start_dt = datetime.fromtimestamp(start_millis / 1000, tz=tz)
    end_dt = datetime.fromtimestamp(end_millis / 1000, tz=tz)
    return (
        {"dateTime": start_dt.isoformat(), "timeZone": tz.key},
        {"dateTime": end_dt.isoformat(), "timeZone": tz.key},
    )


def _build_event_body(task: dict[str, Any], tz: ZoneInfo) -> dict[str, Any]:
    start, end = _task_to_event_datetimes(task, tz)
    body: dict[str, Any] = {
        "summary": task.get("title", "Untitled"),
        "description": _build_description(task),
        "start": start,
        "end": end,
        "extendedProperties": {
            "private": {PRISMTASK_EXTENDED_PROPERTY: "true"},
        },
    }
    return body


def _build_description(task: dict[str, Any]) -> str:
    parts = []
    description = task.get("description")
    if description:
        parts.append(description)
    notes = task.get("notes")
    if notes:
        parts.append(f"Notes: {notes}")
    priority_label = {1: "Low", 2: "Medium", 3: "High", 4: "Urgent"}.get(
        task.get("priority") or 0
    )
    if priority_label:
        parts.append(f"Priority: {priority_label}")
    parts.append("[PrismTask]")
    return "\n".join(parts)


async def create_event(
    db: AsyncSession,
    user_id: int,
    calendar_id: str,
    task: dict[str, Any],
    tz: ZoneInfo,
) -> EventResult:
    creds = await load_credentials(db, user_id)
    if creds is None:
        raise PermissionError("Calendar not connected")
    service = _build_service(creds)
    body = _build_event_body(task, tz)
    event = service.events().insert(calendarId=calendar_id, body=body).execute()
    return EventResult(
        event_id=event["id"],
        calendar_id=calendar_id,
        etag=event.get("etag"),
    )


async def update_event(
    db: AsyncSession,
    user_id: int,
    calendar_id: str,
    event_id: str,
    task: dict[str, Any],
    tz: ZoneInfo,
) -> EventResult:
    creds = await load_credentials(db, user_id)
    if creds is None:
        raise PermissionError("Calendar not connected")
    service = _build_service(creds)
    body = _build_event_body(task, tz)
    try:
        event = (
            service.events()
            .patch(calendarId=calendar_id, eventId=event_id, body=body)
            .execute()
        )
    except HttpError as e:
        # Event was deleted externally; recreate.
        if getattr(e, "resp", None) is not None and e.resp.status in (404, 410):
            return await create_event(db, user_id, calendar_id, task, tz)
        raise
    return EventResult(
        event_id=event["id"],
        calendar_id=calendar_id,
        etag=event.get("etag"),
    )


async def delete_event(
    db: AsyncSession, user_id: int, calendar_id: str, event_id: str
) -> None:
    creds = await load_credentials(db, user_id)
    if creds is None:
        return
    service = _build_service(creds)
    try:
        service.events().delete(calendarId=calendar_id, eventId=event_id).execute()
    except HttpError as e:
        status = getattr(getattr(e, "resp", None), "status", None)
        if status in (404, 410):
            return
        raise


async def list_events(
    db: AsyncSession,
    user_id: int,
    calendar_id: str,
    sync_token: str | None = None,
    time_min: datetime | None = None,
    time_max: datetime | None = None,
) -> PullResult:
    """Pull events for *calendar_id*. When *sync_token* is provided, uses
    incremental sync. Falls back to a bounded full window (last 30 days to
    next 365 days) on first-run or on 410 GONE.
    """
    creds = await load_credentials(db, user_id)
    if creds is None:
        return PullResult()
    service = _build_service(creds)

    def _call(token: str | None) -> dict[str, Any]:
        kwargs: dict[str, Any] = {
            "calendarId": calendar_id,
            "singleEvents": True,
            "showDeleted": True,
            "maxResults": 250,
        }
        if token:
            kwargs["syncToken"] = token
        else:
            now = datetime.now(timezone.utc)
            kwargs["timeMin"] = (
                time_min or now - timedelta(days=DEFAULT_LOOKBACK_DAYS)
            ).isoformat()
            kwargs["timeMax"] = (
                time_max or now + timedelta(days=DEFAULT_LOOKAHEAD_DAYS)
            ).isoformat()
        return service.events().list(**kwargs).execute()

    try:
        response = _call(sync_token)
    except HttpError as e:
        status = getattr(getattr(e, "resp", None), "status", None)
        if status == 410:
            logger.info(
                "syncToken expired for calendar=%s, doing full pull", calendar_id
            )
            response = _call(None)
        else:
            raise

    result = PullResult()
    for event in response.get("items", []):
        # Skip events we originated — avoids sync loops.
        extended = event.get("extendedProperties") or {}
        private = extended.get("private") or {}
        if private.get(PRISMTASK_EXTENDED_PROPERTY) == "true":
            continue
        if event.get("status") == "cancelled":
            result.deleted.append(event["id"])
            continue
        if event.get("created") == event.get("updated"):
            result.created += 1
        else:
            result.updated += 1
    result.next_sync_token = response.get("nextSyncToken")
    return result


async def search_events_by_summary(
    db: AsyncSession,
    user_id: int,
    pattern: str,
    calendar_id: str = "primary",
    time_min: datetime | None = None,
    time_max: datetime | None = None,
) -> list[dict[str, Any]]:
    """Fuzzy-match events whose summary contains *pattern*. Used by the
    medication nag scheduler so it can stop hitting Google from the
    Android client (Step 15 of the calendar-sync plan).
    """
    creds = await load_credentials(db, user_id)
    if creds is None:
        return []
    service = _build_service(creds)
    now = datetime.now(timezone.utc)
    response = (
        service.events()
        .list(
            calendarId=calendar_id,
            q=pattern,
            timeMin=(time_min or now).isoformat(),
            timeMax=(time_max or now + timedelta(days=7)).isoformat(),
            maxResults=10,
            singleEvents=True,
            orderBy="startTime",
        )
        .execute()
    )
    out: list[dict[str, Any]] = []
    for event in response.get("items", []):
        summary = event.get("summary") or ""
        if pattern.lower() not in summary.lower():
            continue
        start = event.get("start") or {}
        start_millis = _datetime_to_millis(start)
        if start_millis is None:
            continue
        out.append(
            {
                "id": event["id"],
                "summary": summary,
                "start_millis": start_millis,
                "all_day": bool(start.get("date")),
            }
        )
    return out


def _datetime_to_millis(event_date_time: dict[str, Any]) -> int | None:
    iso = event_date_time.get("dateTime") or event_date_time.get("date")
    if iso is None:
        return None
    try:
        if "T" in iso:
            return int(
                datetime.fromisoformat(iso.replace("Z", "+00:00")).timestamp() * 1000
            )
        return int(
            datetime.fromisoformat(iso).replace(tzinfo=timezone.utc).timestamp() * 1000
        )
    except ValueError:
        return None


async def list_events_in_window(
    db: AsyncSession,
    user_id: int,
    calendar_ids: list[str],
    time_min: datetime,
    time_max: datetime,
    limit: int = 20,
) -> list[dict[str, Any]]:
    creds = await load_credentials(db, user_id)
    if creds is None:
        return []

    def _fetch_calendar_events(calendar_id: str) -> list[dict[str, Any]]:
        service = _build_service(creds)
        response = (
            service.events()
            .list(
                calendarId=calendar_id,
                timeMin=time_min.isoformat(),
                timeMax=time_max.isoformat(),
                maxResults=limit,
                singleEvents=True,
                orderBy="startTime",
            )
            .execute()
        )
        events = []
        for event in response.get("items", []):
            extended = event.get("extendedProperties") or {}
            private = extended.get("private") or {}
            if private.get(PRISMTASK_EXTENDED_PROPERTY) == "true":
                continue
            start = event.get("start") or {}
            end = event.get("end") or {}
            start_millis = _datetime_to_millis(start)
            end_millis = _datetime_to_millis(end)
            if start_millis is None or end_millis is None:
                continue
            events.append(
                {
                    "id": event["id"],
                    "calendar_id": calendar_id,
                    "title": event.get("summary") or "(No Title)",
                    "start_millis": start_millis,
                    "end_millis": end_millis,
                    "all_day": bool(start.get("date")),
                }
            )
        return events

    tasks = [asyncio.to_thread(_fetch_calendar_events, cid) for cid in calendar_ids]
    results = await asyncio.gather(*tasks)

    out: list[dict[str, Any]] = []
    for events in results:
        out.extend(events)

    out.sort(key=lambda e: e["start_millis"])
    return out[:limit]
