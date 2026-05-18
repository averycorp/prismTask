"""Stub for get_projects; replaced with real implementation in Task 7."""

from datetime import date

from app.routers.ai.tools._base import ToolResult


class GetProjectsHandler:
    name = "get_projects"
    schema = {
        "name": "get_projects",
        "description": "stub — Task 7 will fill this in",
        "input_schema": {"type": "object", "properties": {}, "required": []},
    }

    async def dispatch(self, *, user, db, args, logical_today: date):
        return ToolResult(data={}, summary="stub")
