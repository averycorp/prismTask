"""get_medications read-tool handler.

Returns medication adherence aggregates + active med list. Reads
from Postgres via ``app.routers.ai.context.load_medications``.

TODO(phase-1-followup): expose per-med taken_today / missed_today /
refill_due_in_days once the loader emits that detail.
"""

from __future__ import annotations

from datetime import date

from app.routers.ai.tools._base import (
    ToolError,
    ToolResult,
    validate_one_of,
)


_WINDOWS = {"today", "last_7d"}


async def _load_medications(db, user_id: int, today: date) -> dict:
    from app.routers.ai.context import load_medications
    return await load_medications(db, user_id, today)


class GetMedicationsHandler:
    name = "get_medications"
    schema = {
        "name": "get_medications",
        "description": (
            "Read medication adherence: slot-level slots_logged / "
            "slots_taken, plus active medication list (id / name / dosage). "
            "window=today returns today's adherence; window=last_7d also "
            "returns last_7_days adherence_pct."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "window": {"type": "string", "enum": sorted(_WINDOWS)},
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
        bundle = await _load_medications(db, user_id, logical_today)
        if window == "today":
            adherence = dict(bundle.get("today") or {})
        else:
            adherence = dict(bundle.get("last_7_days") or {})
        active = list(bundle.get("active") or [])
        return ToolResult(
            data={
                "window": window,
                "adherence": adherence,
                "active_count": int(bundle.get("active_count", 0) or 0),
                "active": active,
            },
            summary=(
                f"{adherence.get('slots_taken', 0)}/{adherence.get('slots_logged', 0)} "
                f"med slot(s) ({window})"
            ),
        )
