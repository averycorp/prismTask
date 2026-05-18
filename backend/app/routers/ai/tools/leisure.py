"""Stub for get_leisure_logs; replaced with real implementation in Task 10."""

from datetime import date

from app.routers.ai.tools._base import ToolResult


class GetLeisureLogsHandler:
    name = "get_leisure_logs"
    schema = {
        "name": "get_leisure_logs",
        "description": "stub — Task 10 will fill this in",
        "input_schema": {"type": "object", "properties": {}, "required": []},
    }

    async def dispatch(self, *, user, db, args, logical_today: date):
        return ToolResult(data={}, summary="stub")
