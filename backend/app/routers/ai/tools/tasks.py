"""get_tasks read-tool handler.

Returns task summaries from one of six buckets that mirror the
panels in ``load_user_context_bundle``: overdue / today / planned /
upcoming / backlog / recently_completed. Optional project_id narrows
the bucket further.

Source of truth is Firestore (``users/{uid}/tasks``), read via the same
helpers ``load_user_context_bundle`` uses so the bucket semantics
match exactly.

TODO(phase-1-followup): when ``tag`` and ``life_category`` propagate
from Firestore into ``TaskDTO``, re-enable those filter args (they are
documented in the spec but not yet surfaced on the DTO).
"""

from __future__ import annotations

from datetime import date
from typing import Any

from app.routers.ai.tools._base import (
    ToolError,
    ToolResult,
    validate_one_of,
    validate_positive_int,
)
from app.services.firestore_tasks import TaskDTO


_BUCKETS = {
    "overdue",
    "today",
    "planned",
    "upcoming",
    "backlog",
    "recently_completed",
}
_MAX_LIMIT = 50
_RECENTLY_COMPLETED_WINDOW_DAYS = 7
_UPCOMING_WINDOW_DAYS = 14


async def _fetch_incomplete_tasks(uid: str) -> list[TaskDTO]:
    """Indirection so tests can patch a single symbol instead of Firestore."""
    from app.routers import ai as _ai_pkg
    return await _ai_pkg.fetch_incomplete_tasks(uid)


async def _fetch_recently_completed(uid: str, *, days: int) -> list[TaskDTO]:
    from app.routers import ai as _ai_pkg
    return await _ai_pkg.fetch_recently_completed_tasks(uid, days=days)


def _parse_due(t: TaskDTO) -> date | None:
    if not t.due_date:
        return None
    try:
        return date.fromisoformat(t.due_date)
    except ValueError:
        return None


def _summarize(t: TaskDTO, today: date) -> dict[str, Any]:
    d = _parse_due(t)
    days_overdue = (today - d).days if d and d < today else 0
    days_until = (d - today).days if d and d >= today else None
    return {
        "task_id": t.task_id,
        "title": t.title,
        "due": t.due_date,
        "days_overdue": days_overdue if days_overdue > 0 else None,
        "days_until_due": days_until,
        "priority": t.priority,
        "project_id": t.project_id,
    }


def _filter(tasks: list[TaskDTO], *, project_id: str | None) -> list[TaskDTO]:
    if project_id is None:
        return tasks
    return [t for t in tasks if t.project_id == project_id]


def _split_buckets(
    tasks: list[TaskDTO], today: date
) -> dict[str, list[TaskDTO]]:
    overdue, due_today, planned, upcoming, backlog = [], [], [], [], []
    from datetime import timedelta
    horizon = today + timedelta(days=_UPCOMING_WINDOW_DAYS)
    for t in tasks:
        d = _parse_due(t)
        if d is None:
            backlog.append(t)
        elif d < today:
            overdue.append(t)
        elif d == today:
            due_today.append(t)
        elif d <= horizon:
            upcoming.append(t)
        else:
            planned.append(t)
    return {
        "overdue": overdue,
        "today": due_today,
        "planned": planned,
        "upcoming": upcoming,
        "backlog": backlog,
    }


class GetTasksHandler:
    name = "get_tasks"
    schema = {
        "name": "get_tasks",
        "description": (
            "Read tasks from one of six bucket views that mirror the user's "
            "Today screen: overdue (due_date < today, not done), today "
            "(due_date == today), upcoming (due in next 14d), planned "
            "(due_date > today+14d), backlog (no due_date), "
            "recently_completed (completed in last 7d). Optional project_id "
            "narrows to a single project."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "bucket": {
                    "type": "string",
                    "enum": sorted(_BUCKETS),
                },
                "limit": {
                    "type": "integer",
                    "minimum": 1,
                    "maximum": _MAX_LIMIT,
                    "default": 20,
                },
                "project_id": {"type": "string"},
            },
            "required": ["bucket"],
        },
    }

    async def dispatch(self, *, user, db, args: dict, logical_today: date) -> ToolResult:
        uid = getattr(user, "firebase_uid", None)
        if not uid:
            raise ToolError("user is not Firebase-linked", field="user")
        bucket = validate_one_of(args.get("bucket"), _BUCKETS, field="bucket")
        limit = validate_positive_int(
            args.get("limit", 20), max_value=_MAX_LIMIT, field="limit",
        )
        project_id = args.get("project_id")

        if bucket == "recently_completed":
            raw = await _fetch_recently_completed(
                uid, days=_RECENTLY_COMPLETED_WINDOW_DAYS,
            )
            filtered = _filter(raw, project_id=project_id)[:limit]
            return ToolResult(
                data={
                    "bucket": bucket,
                    "tasks": [_summarize(t, logical_today) for t in filtered],
                    "count": len(filtered),
                },
                summary=f"{len(filtered)} task(s) in {bucket}",
            )

        raw = await _fetch_incomplete_tasks(uid)
        filtered = _filter(raw, project_id=project_id)
        bucketed = _split_buckets(filtered, logical_today)[bucket][:limit]
        return ToolResult(
            data={
                "bucket": bucket,
                "tasks": [_summarize(t, logical_today) for t in bucketed],
                "count": len(bucketed),
            },
            summary=f"{len(bucketed)} task(s) in {bucket}",
        )
