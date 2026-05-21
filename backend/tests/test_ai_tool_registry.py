"""Unit tests for the AI Assistant tool registry + dispatcher."""

from datetime import date

import pytest

from app.routers.ai.tool_registry import (
    READ_TOOL_NAMES,
    ToolRegistry,
    build_tool_error_result,
)
from app.routers.ai.tools._base import ToolError, ToolResult


class _FakeHandler:
    name = "fake_tool"
    schema = {
        "name": "fake_tool",
        "description": "fake",
        "input_schema": {"type": "object", "properties": {}, "required": []},
    }

    async def dispatch(self, *, user, db, args, logical_today):
        return ToolResult(data={"ok": True, "args": args}, summary="fake summary")


class _RaisingHandler:
    name = "raises"
    schema = {
        "name": "raises",
        "description": "raises",
        "input_schema": {"type": "object", "properties": {}, "required": []},
    }

    async def dispatch(self, *, user, db, args, logical_today):
        raise ToolError("bad arg", field="x")


def test_registry_exposes_claude_schemas():
    reg = ToolRegistry([_FakeHandler()])
    schemas = reg.claude_schemas()
    assert len(schemas) == 1
    assert schemas[0]["name"] == "fake_tool"


def test_registry_rejects_duplicate_names():
    with pytest.raises(ValueError):
        ToolRegistry([_FakeHandler(), _FakeHandler()])


@pytest.mark.asyncio
async def test_registry_dispatches_to_named_handler():
    reg = ToolRegistry([_FakeHandler()])
    result = await reg.dispatch(
        user=None, db=None, name="fake_tool", args={"a": 1},
        logical_today=date(2026, 5, 18),
    )
    assert result.data == {"ok": True, "args": {"a": 1}}


@pytest.mark.asyncio
async def test_registry_unknown_name_raises_toolerror():
    reg = ToolRegistry([_FakeHandler()])
    with pytest.raises(ToolError):
        await reg.dispatch(
            user=None, db=None, name="not_registered", args={},
            logical_today=date(2026, 5, 18),
        )


@pytest.mark.asyncio
async def test_registry_propagates_toolerror_from_handler():
    reg = ToolRegistry([_RaisingHandler()])
    with pytest.raises(ToolError) as exc:
        await reg.dispatch(
            user=None, db=None, name="raises", args={},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "x"


def test_build_tool_error_result_shape():
    payload = build_tool_error_result(ToolError("nope", field="bucket"))
    assert payload == {"error": "nope", "field": "bucket"}


def test_read_tool_names_is_a_frozen_set():
    assert isinstance(READ_TOOL_NAMES, frozenset)
    assert "get_tasks" in READ_TOOL_NAMES
    assert "query" in READ_TOOL_NAMES
