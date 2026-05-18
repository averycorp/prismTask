"""Stub for query; replaced with real implementation in Task 11."""

from datetime import date

from app.routers.ai.tools._base import ToolResult


class QueryHandler:
    name = "query"
    schema = {
        "name": "query",
        "description": "stub — Task 11 will fill this in",
        "input_schema": {"type": "object", "properties": {}, "required": []},
    }

    async def dispatch(self, *, user, db, args, logical_today: date):
        return ToolResult(data={}, summary="stub")
