"""get_mood_logs read-tool handler — Phase 1 follow-up (NOT yet wired).

Deferred from Phase 1: the Android ``MoodEnergyLogEntity`` does not yet
have a Postgres mirror in the backend, so this tool has no data source.
The stub class remains so the registry import stays stable, but
``tool_registry.READ_TOOL_NAMES`` + ``default_registry()`` intentionally
exclude it until the backend mirror lands.

TODO(phase-1-followup): when the Postgres ``mood_energy_logs`` table +
loader exist, register ``GetMoodLogsHandler`` in
``app/routers/ai/tool_registry.py`` and replace this stub with the real
implementation. Tests at ``backend/tests/test_ai_tools_mood.py``.
"""

from datetime import date

from app.routers.ai.tools._base import ToolResult


class GetMoodLogsHandler:
    name = "get_mood_logs"
    schema = {
        "name": "get_mood_logs",
        "description": "stub — not registered in Phase 1; awaiting Postgres mood_energy_logs mirror",
        "input_schema": {"type": "object", "properties": {}, "required": []},
    }

    async def dispatch(self, *, user, db, args, logical_today: date):
        return ToolResult(data={}, summary="stub")
