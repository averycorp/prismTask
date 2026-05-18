"""Unit tests for the get_habits tool handler."""

from datetime import date
from unittest.mock import AsyncMock, patch

import pytest

from app.routers.ai.tools._base import ToolError
from app.routers.ai.tools.habits import GetHabitsHandler


class _FakeUser:
    id = 9
    firebase_uid = "fb-9"


class _FakeDb:
    """Stand-in for AsyncSession — not actually used since we patch the loader."""


@pytest.mark.asyncio
async def test_get_habits_rejects_unknown_window():
    handler = GetHabitsHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=_FakeDb(),
            args={"window": "last_30d"},  # Phase 1 only supports "today"
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "window"


@pytest.mark.asyncio
async def test_get_habits_rejects_missing_db():
    handler = GetHabitsHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"window": "today"},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "db"


@pytest.mark.asyncio
async def test_get_habits_today_returns_per_habit_progress():
    sample = {
        "active_count": 2,
        "completed_today_count": 1,
        "today": [
            {"name": "Meditate", "count": 1, "target": 1,
             "streak": 12, "last7_count": 6, "category": "self_care"},
            {"name": "Walk", "count": 0, "target": 1,
             "streak": 0, "last7_count": 3, "category": "health"},
        ],
    }
    handler = GetHabitsHandler()
    fake_db = _FakeDb()
    with patch(
        "app.routers.ai.tools.habits._load_habits_today",
        new=AsyncMock(return_value=sample),
    ) as mock_load:
        result = await handler.dispatch(
            user=_FakeUser(), db=fake_db,
            args={"window": "today"},
            logical_today=date(2026, 5, 18),
        )
    mock_load.assert_awaited_once_with(fake_db, 9, date(2026, 5, 18))
    assert result.data["window"] == "today"
    assert result.data["active_count"] == 2
    assert result.data["completed_today_count"] == 1
    assert len(result.data["habits"]) == 2
    assert result.data["habits"][0]["streak"] == 12


@pytest.mark.asyncio
async def test_get_habits_filters_by_category():
    sample = {
        "active_count": 2,
        "completed_today_count": 0,
        "today": [
            {"name": "Meditate", "count": 0, "target": 1,
             "streak": 0, "last7_count": 0, "category": "self_care"},
            {"name": "Walk", "count": 0, "target": 1,
             "streak": 0, "last7_count": 0, "category": "health"},
        ],
    }
    handler = GetHabitsHandler()
    with patch(
        "app.routers.ai.tools.habits._load_habits_today",
        new=AsyncMock(return_value=sample),
    ):
        result = await handler.dispatch(
            user=_FakeUser(), db=_FakeDb(),
            args={"window": "today", "category": "health"},
            logical_today=date(2026, 5, 18),
        )
    assert [h["name"] for h in result.data["habits"]] == ["Walk"]


@pytest.mark.asyncio
async def test_get_habits_rejects_unknown_category():
    handler = GetHabitsHandler()
    with patch(
        "app.routers.ai.tools.habits._load_habits_today",
        new=AsyncMock(return_value={"active_count": 0, "completed_today_count": 0, "today": []}),
    ):
        with pytest.raises(ToolError) as exc:
            await handler.dispatch(
                user=_FakeUser(), db=_FakeDb(),
                args={"window": "today", "category": "fitness"},
                logical_today=date(2026, 5, 18),
            )
        assert exc.value.field == "category"
