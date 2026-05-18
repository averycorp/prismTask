"""Stub for get_medications; replaced with real implementation in Task 8."""

from datetime import date

from app.routers.ai.tools._base import ToolResult


class GetMedicationsHandler:
    name = "get_medications"
    schema = {
        "name": "get_medications",
        "description": "stub — Task 8 will fill this in",
        "input_schema": {"type": "object", "properties": {}, "required": []},
    }

    async def dispatch(self, *, user, db, args, logical_today: date):
        return ToolResult(data={}, summary="stub")
