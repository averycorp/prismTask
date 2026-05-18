"""Stub for get_mood_logs; replaced with real implementation in Task 9."""

from datetime import date

from app.routers.ai.tools._base import ToolResult


class GetMoodLogsHandler:
    name = "get_mood_logs"
    schema = {
        "name": "get_mood_logs",
        "description": "stub — Task 9 will fill this in",
        "input_schema": {"type": "object", "properties": {}, "required": []},
    }

    async def dispatch(self, *, user, db, args, logical_today: date):
        return ToolResult(data={}, summary="stub")
