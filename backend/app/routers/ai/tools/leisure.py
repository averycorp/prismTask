"""get_leisure_logs read-tool handler.

Returns leisure minute aggregates by category for ``today`` or
``last_7d``. Reads from Postgres via
``app.routers.ai.context.load_leisure``.

TODO(phase-1-followup): add ``last_30d`` window + per-entry detail
(loader currently exposes only today + last_7_days aggregates).
"""

from __future__ import annotations

from datetime import date

from app.routers.ai.tools._base import (
    ToolError,
    ToolResult,
    validate_one_of,
)


_WINDOWS = {"today", "last_7d"}


async def _load_leisure(db, user_id: int, today: date) -> dict:
    from app.routers.ai.context import load_leisure
    return await load_leisure(db, user_id, today)


class GetLeisureLogsHandler:
    name = "get_leisure_logs"
    schema = {
        "name": "get_leisure_logs",
        "description": (
            "Read leisure (rest / play) totals by category. window=today "
            "or last_7d. Optional category narrows to a single category."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "window": {"type": "string", "enum": sorted(_WINDOWS)},
                "category": {"type": "string"},
            },
            "required": ["window"],
        },
    }

    async def dispatch(self, *, user, db, args: dict, logical_today: date) -> ToolResult:
        if db is None:
            raise ToolError("missing db session", field="db")
        user_id = getattr(user, "id", None)
        if user_id is None:
            raise ToolError("missing user id", field="user")
        window = validate_one_of(args.get("window"), _WINDOWS, field="window")
        category = args.get("category")
        if category is not None and not isinstance(category, str):
            raise ToolError("category must be a string", field="category")

        bundle = await _load_leisure(db, user_id, logical_today)
        block = bundle.get("today" if window == "today" else "last_7_days") or {}
        by_category = dict(block.get("by_category") or {})
        total_minutes = int(block.get("total_minutes", 0) or 0)

        if category is not None:
            value = by_category.get(category)
            by_category = {category: int(value)} if isinstance(value, int) else {}
            total_minutes = int(value) if isinstance(value, int) else 0

        return ToolResult(
            data={
                "window": window,
                "total_minutes": total_minutes,
                "by_category": by_category,
            },
            summary=f"{total_minutes} leisure minute(s) ({window})",
        )
