"""Escape-hatch query tool — narrow, closed-set filter grammar per entity type.

Unknown filter keys raise ToolError so the model can correct on the next
loop iteration. No raw SQL — every dispatch hits a typed helper.

Phase 1 ships the contract (schema + filter validation). Per-entity
backends are ``NotImplementedError`` stubs in
``app/routers/ai/context.py``; the handler converts those to
``ToolError`` so the AI sees a recoverable "not implemented yet" and
falls back to a narrow tool.
"""

from __future__ import annotations

from datetime import date
from typing import Any, Awaitable, Callable

from app.routers.ai.tools._base import (
    ToolError,
    ToolResult,
    validate_one_of,
    validate_positive_int,
)


_ENTITY_TYPES = {
    "TASK", "HABIT", "PROJECT", "MEDICATION",
    "MOOD_LOG", "LEISURE_LOG", "CHECK_IN", "GOAL",
}
_MAX_LIMIT = 100


ALLOWED_FILTERS: dict[str, set[str]] = {
    "TASK": {
        "completed", "due_before", "due_after", "priority_in",
        "tag_in", "project_id", "life_category_in", "text_contains",
    },
    "HABIT": {"category", "is_archived", "is_built_in"},
    "PROJECT": {"status", "due_before", "due_after"},
    "MEDICATION": {"is_archived", "refill_due_within_days"},
    "MOOD_LOG": {"logged_after", "logged_before", "min_mood", "max_mood"},
    "LEISURE_LOG": {"category", "logged_after", "logged_before"},
    "CHECK_IN": {"logged_after", "logged_before"},
    "GOAL": {"status", "target_before", "target_after"},
}


async def _query_tasks(user, db, *, filters, fields, limit, today):
    from app.routers.ai.context import query_tasks
    return await query_tasks(user, db, filters=filters, fields=fields, limit=limit, today=today)


async def _query_habits(user, db, *, filters, fields, limit, today):
    from app.routers.ai.context import query_habits
    return await query_habits(user, db, filters=filters, fields=fields, limit=limit, today=today)


async def _query_projects(user, db, *, filters, fields, limit, today):
    from app.routers.ai.context import query_projects
    return await query_projects(user, db, filters=filters, fields=fields, limit=limit, today=today)


async def _query_medications(user, db, *, filters, fields, limit, today):
    from app.routers.ai.context import query_medications
    return await query_medications(user, db, filters=filters, fields=fields, limit=limit, today=today)


async def _query_mood_logs(user, db, *, filters, fields, limit, today):
    from app.routers.ai.context import query_mood_logs
    return await query_mood_logs(user, db, filters=filters, fields=fields, limit=limit, today=today)


async def _query_leisure_logs(user, db, *, filters, fields, limit, today):
    from app.routers.ai.context import query_leisure_logs
    return await query_leisure_logs(user, db, filters=filters, fields=fields, limit=limit, today=today)


async def _query_check_ins(user, db, *, filters, fields, limit, today):
    from app.routers.ai.context import query_check_ins
    return await query_check_ins(user, db, filters=filters, fields=fields, limit=limit, today=today)


async def _query_goals(user, db, *, filters, fields, limit, today):
    from app.routers.ai.context import query_goals
    return await query_goals(user, db, filters=filters, fields=fields, limit=limit, today=today)


_DISPATCH_NAMES: dict[str, str] = {
    "TASK": "_query_tasks",
    "HABIT": "_query_habits",
    "PROJECT": "_query_projects",
    "MEDICATION": "_query_medications",
    "MOOD_LOG": "_query_mood_logs",
    "LEISURE_LOG": "_query_leisure_logs",
    "CHECK_IN": "_query_check_ins",
    "GOAL": "_query_goals",
}


class QueryHandler:
    name = "query"
    schema = {
        "name": "query",
        "description": (
            "Run an ad-hoc filtered read across one user-owned entity type. "
            "Use this ONLY when no narrow tool (get_tasks, get_habits, …) "
            "covers the question. Filters are entity-typed; unknown keys "
            "are rejected. Max 100 rows per call. Phase 1 backends may "
            "return 'not implemented yet' — fall back to a narrow tool."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "entity_type": {"type": "string", "enum": sorted(_ENTITY_TYPES)},
                "filters": {"type": "object"},
                "fields": {
                    "type": "array",
                    "items": {"type": "string"},
                    "default": [],
                },
                "limit": {
                    "type": "integer",
                    "minimum": 1,
                    "maximum": _MAX_LIMIT,
                    "default": 50,
                },
                "order_by": {"type": "string"},
            },
            "required": ["entity_type", "filters"],
        },
    }

    async def dispatch(self, *, user, db, args: dict, logical_today: date) -> ToolResult:
        entity_type = validate_one_of(
            args.get("entity_type"), _ENTITY_TYPES, field="entity_type",
        )
        filters = args.get("filters")
        if not isinstance(filters, dict):
            raise ToolError("filters must be an object", field="filters")
        allowed = ALLOWED_FILTERS[entity_type]
        for k in filters.keys():
            if k not in allowed:
                raise ToolError(
                    f"filter {k!r} not allowed for {entity_type}; "
                    f"allowed: {sorted(allowed)}",
                    field=f"filters.{k}",
                )
        fields = args.get("fields") or []
        if not isinstance(fields, list) or any(not isinstance(f, str) for f in fields):
            raise ToolError("fields must be a list of strings", field="fields")
        limit = validate_positive_int(
            args.get("limit", 50), max_value=_MAX_LIMIT, field="limit",
        )

        import app.routers.ai.tools.query as _self
        dispatch_fn = getattr(_self, _DISPATCH_NAMES[entity_type])
        try:
            rows = await dispatch_fn(
                user, db,
                filters=filters, fields=fields, limit=limit, today=logical_today,
            )
        except NotImplementedError:
            raise ToolError(
                f"query backend for {entity_type} not implemented yet; "
                f"use a narrow tool (get_tasks / get_habits / ...) instead",
                field="entity_type",
            )
        return ToolResult(
            data={"entity_type": entity_type, "rows": rows, "count": len(rows)},
            summary=f"{len(rows)} {entity_type.lower()}(s)",
        )
