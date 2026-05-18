"""Unit tests for the get_tasks tool handler."""

from datetime import date
from unittest.mock import AsyncMock, patch

import pytest

from app.routers.ai.tools._base import ToolError
from app.routers.ai.tools.tasks import GetTasksHandler
from app.services.firestore_tasks import TaskDTO


class _FakeUser:
    id = 7
    firebase_uid = "firebase-7"


def _task(task_id: str, *, due: str | None, project_id: str | None = None,
          completed_at: str | None = None, priority: int = 0) -> TaskDTO:
    return TaskDTO(
        task_id=task_id,
        title=f"Task {task_id}",
        description=None,
        due_date=due,
        priority=priority,
        project_id=project_id,
        completed_at=completed_at,
    )


@pytest.mark.asyncio
async def test_get_tasks_rejects_unknown_bucket():
    handler = GetTasksHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"bucket": "yesterday"},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "bucket"


@pytest.mark.asyncio
async def test_get_tasks_rejects_oversized_limit():
    handler = GetTasksHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"bucket": "today", "limit": 999},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "limit"


@pytest.mark.asyncio
async def test_get_tasks_rejects_missing_firebase_uid():
    class _NoUid:
        id = 1
        firebase_uid = None
    handler = GetTasksHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_NoUid(), db=None,
            args={"bucket": "today"},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "user"


@pytest.mark.asyncio
async def test_get_tasks_overdue_bucket_filters_correctly():
    sample = [
        _task("a", due="2026-05-10"),   # overdue (today=2026-05-18)
        _task("b", due="2026-05-18"),   # today
        _task("c", due="2026-05-20"),   # upcoming
        _task("d", due=None),           # backlog
    ]
    handler = GetTasksHandler()
    with patch(
        "app.routers.ai.tools.tasks._fetch_incomplete_tasks",
        new=AsyncMock(return_value=sample),
    ) as mock_fetch:
        result = await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"bucket": "overdue", "limit": 10},
            logical_today=date(2026, 5, 18),
        )
    mock_fetch.assert_awaited_once_with("firebase-7")
    assert [t["task_id"] for t in result.data["tasks"]] == ["a"]
    assert result.data["tasks"][0]["days_overdue"] == 8


@pytest.mark.asyncio
async def test_get_tasks_today_bucket_returns_only_today():
    sample = [
        _task("a", due="2026-05-10"),
        _task("b", due="2026-05-18"),
        _task("c", due="2026-05-20"),
    ]
    handler = GetTasksHandler()
    with patch(
        "app.routers.ai.tools.tasks._fetch_incomplete_tasks",
        new=AsyncMock(return_value=sample),
    ):
        result = await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"bucket": "today"},
            logical_today=date(2026, 5, 18),
        )
    assert [t["task_id"] for t in result.data["tasks"]] == ["b"]


@pytest.mark.asyncio
async def test_get_tasks_filters_by_project_id():
    sample = [
        _task("a", due="2026-05-10", project_id="p1"),
        _task("b", due="2026-05-10", project_id="p2"),
    ]
    handler = GetTasksHandler()
    with patch(
        "app.routers.ai.tools.tasks._fetch_incomplete_tasks",
        new=AsyncMock(return_value=sample),
    ):
        result = await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"bucket": "overdue", "project_id": "p1"},
            logical_today=date(2026, 5, 18),
        )
    assert [t["task_id"] for t in result.data["tasks"]] == ["a"]


@pytest.mark.asyncio
async def test_get_tasks_recently_completed_uses_separate_fetch():
    handler = GetTasksHandler()
    with patch(
        "app.routers.ai.tools.tasks._fetch_recently_completed",
        new=AsyncMock(return_value=[]),
    ) as mock_recent:
        result = await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"bucket": "recently_completed", "limit": 10},
            logical_today=date(2026, 5, 18),
        )
    mock_recent.assert_awaited_once_with("firebase-7", days=7)
    assert result.data["bucket"] == "recently_completed"
    assert result.data["count"] == 0


@pytest.mark.asyncio
async def test_get_tasks_summary_includes_count_and_bucket():
    handler = GetTasksHandler()
    with patch(
        "app.routers.ai.tools.tasks._fetch_incomplete_tasks",
        new=AsyncMock(return_value=[]),
    ):
        result = await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"bucket": "today"},
            logical_today=date(2026, 5, 18),
        )
    assert "0 task" in result.summary
    assert "today" in result.summary
