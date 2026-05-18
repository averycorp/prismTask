"""Stub for get_habits; replaced with real implementation in Task 6."""

from datetime import date

from app.routers.ai.tools._base import ToolResult


class GetHabitsHandler:
    name = "get_habits"
    schema = {
        "name": "get_habits",
        "description": "stub — Task 6 will fill this in",
        "input_schema": {"type": "object", "properties": {}, "required": []},
    }

    async def dispatch(self, *, user, db, args, logical_today: date):
        return ToolResult(data={}, summary="stub")
