"""get_habits read-tool handler.

Returns per-habit progress for the current day, including streak +
last_7_count, plus aggregate counts. Reads from Postgres via
``app.routers.ai.context.load_habits_today``.

TODO(phase-1-followup): add ``last_7d`` / ``last_30d`` window loaders
(today is the only window the existing context bundle exposes).
"""

from __future__ import annotations

from datetime import date

from app.routers.ai.tools._base import (
    ToolError,
    ToolResult,
    validate_one_of,
)


_WINDOWS = {"today"}  # Phase 1: today only; last_7d/last_30d in follow-up
_CATEGORIES = {"work", "personal", "self_care", "health", "uncategorized"}


async def _load_habits_today(
    db, user_id: int, today: date, *, firebase_uid: str | None = None
) -> dict:
    """Indirection so tests can patch a single symbol."""
    from app.routers.ai.context import load_habits_today
    return await load_habits_today(
        db, user_id, today, firebase_uid=firebase_uid
    )


class GetHabitsHandler:
    name = "get_habits"
    schema = {
        "name": "get_habits",
        "description": (
            "Read per-habit progress for today: count vs target, current "
            "streak, and last_7d count. Plus aggregate active_count and "
            "completed_today_count. Phase 1 supports window=today only."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "window": {"type": "string", "enum": sorted(_WINDOWS)},
                "category": {"type": "string", "enum": sorted(_CATEGORIES)},
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
        if category is not None:
            validate_one_of(category, _CATEGORIES, field="category")

        raw = await _load_habits_today(
            db,
            user_id,
            logical_today,
            firebase_uid=getattr(user, "firebase_uid", None),
        )
        today_list = list(raw.get("today") or [])
        if category is not None:
            today_list = [h for h in today_list if h.get("category") == category]
        return ToolResult(
            data={
                "window": window,
                "active_count": int(raw.get("active_count", 0) or 0),
                "completed_today_count": int(raw.get("completed_today_count", 0) or 0),
                "habits": today_list,
                "count": len(today_list),
            },
            summary=f"{len(today_list)} habit(s) in {window}",
        )
