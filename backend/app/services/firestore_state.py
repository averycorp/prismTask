"""Firestore-backed read helpers for the AI chat current-state bundle.

Companion to ``firestore_tasks.py``. The web client writes habits,
projects, medications, and daily-essential slot completions only to
Firestore (no Postgres mirror); the chat handler's
``load_user_context_bundle`` was reading them from Postgres, so
web-only users (and any Android-created data not yet pushed to
Postgres via ``BackendSyncService``) silently appeared empty.

Field names mirror the canonical web writers:
- ``web/src/api/firestore/habits.ts``
- ``web/src/api/firestore/projects.ts``
- ``web/src/api/firestore/medications.ts``
- ``web/src/api/firestore/dailyEssentialSlotCompletions.ts``

Each helper is wrapped in ``asyncio.to_thread`` because the
``google-cloud-firestore`` client is synchronous and would otherwise
block the FastAPI event loop.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import date, datetime, timezone
from typing import Optional

from google.cloud.firestore_v1.base_query import FieldFilter
from pydantic import BaseModel

from app.services.firestore_tasks import _get_firestore_client

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _millis_to_date(value) -> Optional[date]:
    if value is None:
        return None
    try:
        millis = int(value)
    except (TypeError, ValueError):
        return None
    return datetime.fromtimestamp(millis / 1000.0, tz=timezone.utc).date()


def _normalize_status(value) -> Optional[str]:
    """Project status arrives as the Kotlin enum name (``ACTIVE``) from
    Android writes or the lower-case form (``active``) from web writes.
    Normalize to lower-case so callers can use a single comparison."""
    if not isinstance(value, str) or not value:
        return None
    return value.strip().lower()


def _user_collection(uid: str, name: str):
    return (
        _get_firestore_client()
        .collection("users")
        .document(uid)
        .collection(name)
    )


# ---------------------------------------------------------------------------
# Habits + habit completions
# ---------------------------------------------------------------------------


class HabitDTO(BaseModel):
    habit_id: str
    name: str
    category: Optional[str] = None
    target_count: int = 1
    is_active: bool = True


def _doc_to_habit(doc) -> HabitDTO:
    data = doc.to_dict() or {}
    return HabitDTO(
        habit_id=str(doc.id),
        name=str(data.get("name") or ""),
        category=data.get("category"),
        target_count=int(data.get("targetFrequency") or 1),
        is_active=not bool(data.get("isArchived")),
    )


async def fetch_active_habits(uid: str) -> list[HabitDTO]:
    """Return active (non-archived) habits under ``users/{uid}/habits``.

    Sort by ``name`` in Python to match the Postgres loader's
    ``ORDER BY name ASC`` and stay portable when ``isArchived`` is
    absent on legacy docs (Firestore equality filters cannot match
    missing fields)."""

    def _sync() -> list[HabitDTO]:
        coll = _user_collection(uid, "habits")
        dtos = [_doc_to_habit(d) for d in coll.stream()]
        dtos = [d for d in dtos if d.is_active]
        dtos.sort(key=lambda h: (h.name.lower(), h.habit_id))
        return dtos

    return await asyncio.to_thread(_sync)


class HabitCompletionDTO(BaseModel):
    habit_id: str
    completed_date: date


def _doc_to_habit_completion(doc) -> Optional[HabitCompletionDTO]:
    data = doc.to_dict() or {}
    habit_id = data.get("habitCloudId")
    if not isinstance(habit_id, str) or not habit_id:
        return None
    # Prefer the TZ-neutral logical-day string written by Android v50 +
    # the web client; fall back to the legacy epoch-ms ``completedDate``
    # for older docs. Mirrors `docToCompletion` in habits.ts.
    local = data.get("completedDateLocal")
    if isinstance(local, str) and local:
        try:
            completed = date.fromisoformat(local)
        except ValueError:
            completed = None
    else:
        completed = _millis_to_date(data.get("completedDate"))
    if completed is None:
        return None
    return HabitCompletionDTO(habit_id=habit_id, completed_date=completed)


async def fetch_habit_completions_since(
    uid: str, since: date
) -> list[HabitCompletionDTO]:
    """Return habit completions with ``completedDateLocal >= since``.

    Falls back to the legacy ``completedDate`` epoch field for docs
    written before Android v50; a single stream + Python filter keeps
    the path simple and avoids requiring a composite index. Total docs
    scanned for a typical user (<15 habits × 60 days) sit well under
    the ~10k-doc Firestore stream budget we use elsewhere."""
    since_iso = since.isoformat()

    def _sync() -> list[HabitCompletionDTO]:
        coll = _user_collection(uid, "habit_completions")
        out: list[HabitCompletionDTO] = []
        for d in coll.stream():
            dto = _doc_to_habit_completion(d)
            if dto is None:
                continue
            if dto.completed_date >= since:
                out.append(dto)
        return out

    # ``since_iso`` is captured for log-context only; the streaming
    # filter runs in Python so legacy docs missing the ISO key still
    # qualify via their epoch fallback.
    del since_iso
    return await asyncio.to_thread(_sync)


# ---------------------------------------------------------------------------
# Projects
# ---------------------------------------------------------------------------


class ProjectDTO(BaseModel):
    project_id: str
    title: str
    status: str
    sort_order: int = 0
    due_date: Optional[date] = None


def _doc_to_project(doc) -> ProjectDTO:
    data = doc.to_dict() or {}
    return ProjectDTO(
        project_id=str(doc.id),
        title=str(data.get("name") or data.get("title") or ""),
        status=_normalize_status(data.get("status")) or "active",
        sort_order=int(data.get("sortOrder") or 0),
        due_date=_millis_to_date(data.get("dueDate")),
    )


async def fetch_active_projects(uid: str) -> list[ProjectDTO]:
    """Return projects whose ``status`` normalizes to ``"active"``.

    Mirrors the Postgres loader's ordering: ``sort_order ASC, id ASC``.
    """

    def _sync() -> list[ProjectDTO]:
        coll = _user_collection(uid, "projects")
        dtos = [_doc_to_project(d) for d in coll.stream()]
        dtos = [p for p in dtos if p.status == "active"]
        dtos.sort(key=lambda p: (p.sort_order, p.project_id))
        return dtos

    return await asyncio.to_thread(_sync)


async def count_project_task_buckets(
    uid: str, project_ids: list[str]
) -> dict[str, tuple[int, int, int]]:
    """Return ``{project_id: (done, open, total)}`` over the given projects.

    Streams every task once and bucket-sums in Python. The Postgres
    equivalent is a single ``GROUP BY project_id``; in Firestore we
    can't pre-aggregate without a composite scan per project, so the
    full-stream-once shape is simpler and bounded by total tasks
    (the same data ``fetch_incomplete_tasks`` already touches)."""
    if not project_ids:
        return {}
    wanted = set(project_ids)

    def _sync() -> dict[str, tuple[int, int, int]]:
        coll = _user_collection(uid, "tasks")
        counts: dict[str, list[int]] = {pid: [0, 0, 0] for pid in wanted}
        for d in coll.stream():
            data = d.to_dict() or {}
            project_id = data.get("projectId")
            if project_id is None:
                continue
            pid = str(project_id)
            if pid not in counts:
                continue
            done = bool(data.get("isCompleted"))
            counts[pid][2] += 1  # total
            if done:
                counts[pid][0] += 1  # done
            else:
                counts[pid][1] += 1  # open
        return {pid: (v[0], v[1], v[2]) for pid, v in counts.items()}

    return await asyncio.to_thread(_sync)


# ---------------------------------------------------------------------------
# Medications + daily essential slot completions
# ---------------------------------------------------------------------------


class MedicationDTO(BaseModel):
    medication_id: str
    name: str
    display_label: Optional[str] = None
    is_active: bool = True


def _doc_to_medication(doc) -> MedicationDTO:
    data = doc.to_dict() or {}
    label = data.get("displayLabel")
    return MedicationDTO(
        medication_id=str(doc.id),
        name=str(data.get("name") or ""),
        display_label=label if isinstance(label, str) and label else None,
        is_active=not bool(data.get("isArchived")),
    )


async def fetch_active_medications(uid: str) -> list[MedicationDTO]:
    """Return active (non-archived) medications under
    ``users/{uid}/medications``, ordered by name."""

    def _sync() -> list[MedicationDTO]:
        coll = _user_collection(uid, "medications")
        dtos = [_doc_to_medication(d) for d in coll.stream()]
        dtos = [m for m in dtos if m.is_active]
        dtos.sort(key=lambda m: (m.name.lower(), m.medication_id))
        return dtos

    return await asyncio.to_thread(_sync)


class SlotCompletionDTO(BaseModel):
    date: date
    taken: bool


def _doc_to_slot_completion(doc) -> Optional[SlotCompletionDTO]:
    data = doc.to_dict() or {}
    completed_date = _millis_to_date(data.get("date"))
    if completed_date is None:
        return None
    return SlotCompletionDTO(
        date=completed_date,
        taken=data.get("takenAt") is not None,
    )


async def fetch_slot_completions_between(
    uid: str, start: date, end: date
) -> list[SlotCompletionDTO]:
    """Return slot completions whose ``date`` falls in ``[start, end]``.

    Note (per Batch 5 PR-9 / decision D-E4 in
    ``dailyEssentialSlotCompletions.ts``): newer Android clients no
    longer write to this Firestore collection — they mirror straight
    into Postgres via ``BackendSyncService``. The collection still
    catches web writes and pre-PR-9 Android writes, so it remains the
    web-side source of truth for adherence."""
    start_millis = int(
        datetime(start.year, start.month, start.day, tzinfo=timezone.utc)
        .timestamp() * 1000
    )
    # End-inclusive: convert ``end`` to the start of the next day in ms,
    # then keep ``< end_exclusive`` in the Python filter so docs whose
    # ``date`` epoch happens to land mid-day still qualify on ``end``.
    end_exclusive_dt = datetime(
        end.year, end.month, end.day, tzinfo=timezone.utc
    )

    def _sync() -> list[SlotCompletionDTO]:
        coll = _user_collection(uid, "daily_essential_slot_completions")
        query = coll.where(
            filter=FieldFilter("date", ">=", start_millis)
        )
        out: list[SlotCompletionDTO] = []
        for d in query.stream():
            dto = _doc_to_slot_completion(d)
            if dto is None:
                continue
            if dto.date < start or dto.date > end:
                continue
            out.append(dto)
        return out

    # end_exclusive_dt retained for symmetry / future-extension; the
    # actual filter clamps on the date-object comparison.
    del end_exclusive_dt
    return await asyncio.to_thread(_sync)


__all__ = [
    "HabitDTO",
    "HabitCompletionDTO",
    "ProjectDTO",
    "MedicationDTO",
    "SlotCompletionDTO",
    "fetch_active_habits",
    "fetch_habit_completions_since",
    "fetch_active_projects",
    "count_project_task_buckets",
    "fetch_active_medications",
    "fetch_slot_completions_between",
]
