"""Shared protocol + validation helpers for AI Assistant read tools.

Every tool handler in this package conforms to ``ToolHandler``. The
validation helpers raise ``ToolError`` instead of generic ValueErrors so
the registry can convert them into structured ``tool_result`` blocks
that the model can recover from on the next loop iteration.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from typing import Any, Protocol

from sqlalchemy.ext.asyncio import AsyncSession

from app.models import User


class ToolError(Exception):
    """Raised by a tool handler when its args fail validation.

    The registry converts this into a structured tool_result the model
    can read on the next loop iteration ("you passed bucket='never';
    must be one of {today, tomorrow}").
    """

    def __init__(self, message: str, *, field: str | None = None):
        super().__init__(message)
        self.message = message
        self.field = field


@dataclass
class ToolResult:
    """Wrap a tool's structured payload so the registry can summarize it
    for persistence to ``chat_messages.tool_calls`` without bloating the
    row with the full data response."""

    data: Any
    summary: str = ""

    def to_dict(self) -> dict:
        return {"data": self.data, "summary": self.summary}


class ToolHandler(Protocol):
    """One Claude tool. Registered in ``tool_registry``."""

    name: str = ""
    schema: dict = {}  # Anthropic tool schema (passed to ``client.messages.create(tools=...)``)

    async def dispatch(
        self,
        *,
        user: User,
        db: AsyncSession,
        args: dict,
        logical_today: date,
    ) -> ToolResult: ...


# --- validation helpers -----------------------------------------------------


def validate_one_of(value: Any, allowed: set[str], *, field: str) -> str:
    if not isinstance(value, str) or value not in allowed:
        raise ToolError(
            f"{field}={value!r} not in {sorted(allowed)}",
            field=field,
        )
    return value


def validate_iso_date(value: Any, *, field: str) -> date:
    if not isinstance(value, str):
        raise ToolError(f"{field} must be an ISO YYYY-MM-DD string", field=field)
    try:
        return date.fromisoformat(value)
    except ValueError as e:
        raise ToolError(f"{field}={value!r} is not ISO YYYY-MM-DD", field=field) from e


def validate_positive_int(value: Any, *, max_value: int, field: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool):
        raise ToolError(f"{field} must be an int", field=field)
    if value < 1 or value > max_value:
        raise ToolError(
            f"{field}={value} out of range [1, {max_value}]",
            field=field,
        )
    return value
