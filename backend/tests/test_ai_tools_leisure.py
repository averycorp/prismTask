"""Unit tests for the get_leisure_logs tool handler."""

from datetime import date
from unittest.mock import AsyncMock, patch

import pytest

from app.routers.ai.tools._base import ToolError
from app.routers.ai.tools.leisure import GetLeisureLogsHandler


class _FakeUser:
    id = 17


class _FakeDb:
    pass


_SAMPLE = {
    "today": {
        "total_minutes": 75,
        "by_category": {"reading": 45, "gaming": 30},
    },
    "last_7_days": {
        "total_minutes": 360,
        "by_category": {"reading": 220, "gaming": 140},
    },
}


@pytest.mark.asyncio
async def test_rejects_unknown_window():
    handler = GetLeisureLogsHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=_FakeDb(),
            args={"window": "last_30d"},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "window"


@pytest.mark.asyncio
async def test_rejects_missing_db():
    handler = GetLeisureLogsHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"window": "today"},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "db"


@pytest.mark.asyncio
async def test_today_returns_today_aggregate():
    handler = GetLeisureLogsHandler()
    fake_db = _FakeDb()
    with patch(
        "app.routers.ai.tools.leisure._load_leisure",
        new=AsyncMock(return_value=_SAMPLE),
    ) as mock_load:
        result = await handler.dispatch(
            user=_FakeUser(), db=fake_db,
            args={"window": "today"},
            logical_today=date(2026, 5, 18),
        )
    mock_load.assert_awaited_once_with(fake_db, 17, date(2026, 5, 18))
    assert result.data["window"] == "today"
    assert result.data["total_minutes"] == 75
    assert result.data["by_category"] == {"reading": 45, "gaming": 30}


@pytest.mark.asyncio
async def test_last_7d_returns_window_aggregate():
    handler = GetLeisureLogsHandler()
    with patch(
        "app.routers.ai.tools.leisure._load_leisure",
        new=AsyncMock(return_value=_SAMPLE),
    ):
        result = await handler.dispatch(
            user=_FakeUser(), db=_FakeDb(),
            args={"window": "last_7d"},
            logical_today=date(2026, 5, 18),
        )
    assert result.data["window"] == "last_7d"
    assert result.data["total_minutes"] == 360
    assert result.data["by_category"]["reading"] == 220


@pytest.mark.asyncio
async def test_category_filter_narrows_to_single_key():
    handler = GetLeisureLogsHandler()
    with patch(
        "app.routers.ai.tools.leisure._load_leisure",
        new=AsyncMock(return_value=_SAMPLE),
    ):
        result = await handler.dispatch(
            user=_FakeUser(), db=_FakeDb(),
            args={"window": "today", "category": "reading"},
            logical_today=date(2026, 5, 18),
        )
    assert result.data["by_category"] == {"reading": 45}
    # total_minutes for category filter reflects only that category
    assert result.data["total_minutes"] == 45


@pytest.mark.asyncio
async def test_category_filter_missing_returns_zero():
    handler = GetLeisureLogsHandler()
    with patch(
        "app.routers.ai.tools.leisure._load_leisure",
        new=AsyncMock(return_value=_SAMPLE),
    ):
        result = await handler.dispatch(
            user=_FakeUser(), db=_FakeDb(),
            args={"window": "today", "category": "cooking"},
            logical_today=date(2026, 5, 18),
        )
    assert result.data["by_category"] == {}
    assert result.data["total_minutes"] == 0
