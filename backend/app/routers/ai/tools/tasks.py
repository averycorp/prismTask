"""Stub for get_tasks; replaced with real implementation in Task 5."""

from datetime import date

from app.routers.ai.tools._base import ToolResult


class GetTasksHandler:
    name = "get_tasks"
    schema = {
        "name": "get_tasks",
        "description": "stub — Task 5 will fill this in",
        "input_schema": {"type": "object", "properties": {}, "required": []},
    }

    async def dispatch(self, *, user, db, args, logical_today: date):
        return ToolResult(data={}, summary="stub")
