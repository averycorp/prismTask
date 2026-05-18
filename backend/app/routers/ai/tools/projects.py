"""get_projects read-tool handler.

Returns active projects with progress, due date, task counts. Reads
from Postgres via ``app.routers.ai.context.load_active_projects``.

TODO(phase-1-followup): add archived / all status loaders (only
``status=active`` is supported in Phase 1).
"""

from __future__ import annotations

from datetime import date

from app.routers.ai.tools._base import (
    ToolError,
    ToolResult,
    validate_one_of,
    validate_positive_int,
)


_STATUSES = {"active"}  # Phase 1: active only
_MAX_LIMIT = 50


async def _load_active_projects(db, user_id: int, today: date) -> dict:
    from app.routers.ai.context import load_active_projects
    return await load_active_projects(db, user_id, today)


class GetProjectsHandler:
    name = "get_projects"
    schema = {
        "name": "get_projects",
        "description": (
            "Read user's projects with task counts, progress %, and "
            "days_until_due. Phase 1 supports status=active only."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "status": {"type": "string", "enum": sorted(_STATUSES)},
                "limit": {
                    "type": "integer",
                    "minimum": 1,
                    "maximum": _MAX_LIMIT,
                    "default": 20,
                },
            },
            "required": ["status"],
        },
    }

    async def dispatch(self, *, user, db, args: dict, logical_today: date) -> ToolResult:
        if db is None:
            raise ToolError("missing db session", field="db")
        user_id = getattr(user, "id", None)
        if user_id is None:
            raise ToolError("missing user id", field="user")
        status = validate_one_of(args.get("status"), _STATUSES, field="status")
        limit = validate_positive_int(
            args.get("limit", 20), max_value=_MAX_LIMIT, field="limit",
        )
        raw = await _load_active_projects(db, user_id, logical_today)
        projects = list(raw.get("active") or [])[:limit]
        return ToolResult(
            data={
                "status": status,
                "active_count": int(raw.get("active_count", 0) or 0),
                "projects": projects,
                "count": len(projects),
            },
            summary=f"{len(projects)} project(s) ({status})",
        )
