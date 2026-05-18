"""Unit tests for the escape-hatch query tool handler."""

from datetime import date
from unittest.mock import AsyncMock, patch

import pytest

from app.routers.ai.tools._base import ToolError
from app.routers.ai.tools.query import ALLOWED_FILTERS, QueryHandler


class _FakeUser:
    id = 19


class _FakeDb:
    pass


@pytest.mark.asyncio
async def test_query_rejects_unknown_entity_type():
    handler = QueryHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=_FakeDb(),
            args={"entity_type": "WIDGET", "filters": {}},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "entity_type"


@pytest.mark.asyncio
async def test_query_rejects_unknown_filter_key():
    handler = QueryHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=_FakeDb(),
            args={"entity_type": "TASK", "filters": {"nonsense": 1}},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "filters.nonsense"


@pytest.mark.asyncio
async def test_query_caps_limit_at_100():
    handler = QueryHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=_FakeDb(),
            args={"entity_type": "TASK", "filters": {}, "limit": 500},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "limit"


@pytest.mark.asyncio
async def test_query_rejects_non_object_filters():
    handler = QueryHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=_FakeDb(),
            args={"entity_type": "TASK", "filters": "not a dict"},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "filters"


@pytest.mark.asyncio
async def test_query_rejects_non_list_fields():
    handler = QueryHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=_FakeDb(),
            args={"entity_type": "TASK", "filters": {}, "fields": "id,title"},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "fields"


@pytest.mark.asyncio
async def test_query_task_routes_to_task_dispatch():
    handler = QueryHandler()
    with patch(
        "app.routers.ai.tools.query._query_tasks",
        new=AsyncMock(return_value=[{"id": "t1"}]),
    ) as mock_dispatch:
        result = await handler.dispatch(
            user=_FakeUser(), db=_FakeDb(),
            args={
                "entity_type": "TASK",
                "filters": {"completed": False, "due_before": "2026-05-25"},
                "fields": ["id", "title"],
                "limit": 10,
            },
            logical_today=date(2026, 5, 18),
        )
    mock_dispatch.assert_awaited_once()
    assert result.data["count"] == 1
    assert result.data["entity_type"] == "TASK"


@pytest.mark.asyncio
async def test_query_unimplemented_backend_returns_toolerror():
    """All Phase 1 backends raise NotImplementedError; the handler converts
    that to a ToolError so the model sees a recoverable message."""
    handler = QueryHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=_FakeDb(),
            args={"entity_type": "GOAL", "filters": {}},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "entity_type"
    assert "not implemented" in exc.value.message.lower()


def test_allowed_filters_covers_every_entity_type():
    expected = {
        "TASK", "HABIT", "PROJECT", "MEDICATION",
        "MOOD_LOG", "LEISURE_LOG", "CHECK_IN", "GOAL",
    }
    assert set(ALLOWED_FILTERS.keys()) == expected
