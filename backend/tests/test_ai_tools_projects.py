"""Unit tests for the get_projects tool handler."""

from datetime import date
from unittest.mock import AsyncMock, patch

import pytest

from app.routers.ai.tools._base import ToolError
from app.routers.ai.tools.projects import GetProjectsHandler


class _FakeUser:
    id = 11


class _FakeDb:
    pass


@pytest.mark.asyncio
async def test_get_projects_rejects_unknown_status():
    handler = GetProjectsHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=_FakeDb(),
            args={"status": "frozen"},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "status"


@pytest.mark.asyncio
async def test_get_projects_rejects_missing_db():
    handler = GetProjectsHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"status": "active"},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "db"


@pytest.mark.asyncio
async def test_get_projects_active_returns_loader_data():
    sample = {
        "active_count": 2,
        "active": [
            {"id": 1, "title": "Q2 launch", "open_tasks": 21,
             "done_tasks": 21, "total_tasks": 50, "progress_pct": 42,
             "due_date": "2026-05-25", "days_until_due": 7},
            {"id": 2, "title": "Backlog cleanup", "open_tasks": 0,
             "done_tasks": 0, "total_tasks": 0},
        ],
    }
    handler = GetProjectsHandler()
    fake_db = _FakeDb()
    with patch(
        "app.routers.ai.tools.projects._load_active_projects",
        new=AsyncMock(return_value=sample),
    ) as mock_load:
        result = await handler.dispatch(
            user=_FakeUser(), db=fake_db,
            args={"status": "active"},
            logical_today=date(2026, 5, 18),
        )
    mock_load.assert_awaited_once_with(fake_db, 11, date(2026, 5, 18))
    assert result.data["status"] == "active"
    assert result.data["active_count"] == 2
    assert len(result.data["projects"]) == 2


@pytest.mark.asyncio
async def test_get_projects_limit_caps_returned_rows():
    sample = {
        "active_count": 20,
        "active": [
            {"id": i, "title": f"P{i}", "open_tasks": 0,
             "done_tasks": 0, "total_tasks": 0}
            for i in range(20)
        ],
    }
    handler = GetProjectsHandler()
    with patch(
        "app.routers.ai.tools.projects._load_active_projects",
        new=AsyncMock(return_value=sample),
    ):
        result = await handler.dispatch(
            user=_FakeUser(), db=_FakeDb(),
            args={"status": "active", "limit": 5},
            logical_today=date(2026, 5, 18),
        )
    assert len(result.data["projects"]) == 5
    assert result.data["active_count"] == 20  # loader's count, not the sliced view
