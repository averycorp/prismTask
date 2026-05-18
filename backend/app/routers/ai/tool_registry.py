"""Registry + dispatcher for AI Assistant read tools.

The registry binds tool names to handler instances and exposes the
combined Anthropic tool-schema list for inclusion in
``client.messages.create(tools=...)``. The dispatcher is the single
entry point used by the agentic loop in ``ai_productivity.py``.

``READ_TOOL_NAMES`` is the canonical set of names the loop must treat
as server-executed. Any tool_use block whose name is NOT in this set is
either a write chip (forwarded to the client) or a memory tool call
(``remember_preference`` / ``forget_preference`` — handled by the
existing chat handler logic, unchanged).
"""

from __future__ import annotations

from datetime import date
from typing import Iterable

from sqlalchemy.ext.asyncio import AsyncSession

from app.models import User

from .tools._base import ToolError, ToolHandler, ToolResult
from .tools.habits import GetHabitsHandler
from .tools.leisure import GetLeisureLogsHandler
from .tools.medications import GetMedicationsHandler
from .tools.projects import GetProjectsHandler
from .tools.query import QueryHandler
from .tools.tasks import GetTasksHandler

# NOTE: ``get_mood_logs`` is deferred to a Phase 1 follow-up. The Android
# ``MoodEnergyLogEntity`` does not yet have a Postgres mirror, so the tool
# has no data source. Stub remains at ``tools/mood.py`` but is not
# registered or exposed in ``READ_TOOL_NAMES``.


READ_TOOL_NAMES: frozenset[str] = frozenset({
    "get_tasks",
    "get_habits",
    "get_projects",
    "get_medications",
    "get_leisure_logs",
    "query",
})


class ToolRegistry:
    def __init__(self, handlers: Iterable[ToolHandler]):
        self._handlers: dict[str, ToolHandler] = {}
        for h in handlers:
            if h.name in self._handlers:
                raise ValueError(f"duplicate tool handler: {h.name}")
            self._handlers[h.name] = h

    def claude_schemas(self) -> list[dict]:
        return [h.schema for h in self._handlers.values()]

    async def dispatch(
        self,
        *,
        user: User | None,
        db: AsyncSession | None,
        name: str,
        args: dict,
        logical_today: date,
    ) -> ToolResult:
        handler = self._handlers.get(name)
        if handler is None:
            raise ToolError(f"unknown tool name: {name!r}", field="name")
        return await handler.dispatch(
            user=user, db=db, args=args, logical_today=logical_today,
        )


def build_tool_error_result(err: ToolError) -> dict:
    """Render a ToolError into the JSON payload sent back as ``tool_result``
    content so the model can read it and retry with corrected args."""
    return {"error": err.message, "field": err.field}


def default_registry() -> ToolRegistry:
    """Construct the canonical Phase 1 registry. Called from
    ``ai_productivity.generate_chat_response`` once per request."""
    return ToolRegistry([
        GetTasksHandler(),
        GetHabitsHandler(),
        GetProjectsHandler(),
        GetMedicationsHandler(),
        GetLeisureLogsHandler(),
        QueryHandler(),
    ])
