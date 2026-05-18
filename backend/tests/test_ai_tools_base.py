"""Unit tests for the ToolHandler protocol + validation helpers."""

import pytest

from app.routers.ai.tools._base import (
    ToolError,
    ToolHandler,
    ToolResult,
    validate_iso_date,
    validate_one_of,
    validate_positive_int,
)


def test_tool_error_carries_message_and_field():
    err = ToolError("bad arg", field="bucket")
    assert err.message == "bad arg"
    assert err.field == "bucket"


def test_tool_result_serializes_to_dict():
    r = ToolResult(data={"foo": 1}, summary="ok")
    assert r.to_dict() == {"data": {"foo": 1}, "summary": "ok"}


def test_validate_one_of_accepts_valid_value():
    assert validate_one_of("today", {"today", "tomorrow"}, field="bucket") == "today"


def test_validate_one_of_rejects_invalid_value():
    with pytest.raises(ToolError) as exc:
        validate_one_of("never", {"today", "tomorrow"}, field="bucket")
    assert "never" in exc.value.message
    assert exc.value.field == "bucket"


def test_validate_iso_date_accepts_valid_iso():
    assert validate_iso_date("2026-05-18", field="start").isoformat() == "2026-05-18"


def test_validate_iso_date_rejects_garbage():
    with pytest.raises(ToolError) as exc:
        validate_iso_date("not-a-date", field="start")
    assert exc.value.field == "start"


def test_validate_positive_int_accepts_in_range():
    assert validate_positive_int(50, max_value=100, field="limit") == 50


def test_validate_positive_int_rejects_zero():
    with pytest.raises(ToolError):
        validate_positive_int(0, max_value=100, field="limit")


def test_validate_positive_int_rejects_above_max():
    with pytest.raises(ToolError):
        validate_positive_int(101, max_value=100, field="limit")


def test_tool_handler_protocol_signature():
    """Smoke test that ToolHandler is a Protocol with the expected attrs."""
    assert hasattr(ToolHandler, "name")
    assert hasattr(ToolHandler, "schema")
    assert hasattr(ToolHandler, "dispatch")
