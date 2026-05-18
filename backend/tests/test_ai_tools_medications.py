"""Unit tests for the get_medications tool handler."""

from datetime import date
from unittest.mock import AsyncMock, patch

import pytest

from app.routers.ai.tools._base import ToolError
from app.routers.ai.tools.medications import GetMedicationsHandler


class _FakeUser:
    id = 13


class _FakeDb:
    pass


_SAMPLE = {
    "today": {"slots_logged": 3, "slots_taken": 2},
    "last_7_days": {"slots_logged": 21, "slots_taken": 19, "adherence_pct": 90},
    "active_count": 2,
    "active": [
        {"id": 100, "name": "Sertraline", "dosage": "50mg"},
        {"id": 101, "name": "Vitamin D", "dosage": None},
    ],
}


@pytest.mark.asyncio
async def test_rejects_unknown_window():
    handler = GetMedicationsHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=_FakeDb(),
            args={"window": "year"},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "window"


@pytest.mark.asyncio
async def test_rejects_missing_db():
    handler = GetMedicationsHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"window": "today"},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "db"


@pytest.mark.asyncio
async def test_today_returns_today_adherence_block():
    handler = GetMedicationsHandler()
    fake_db = _FakeDb()
    with patch(
        "app.routers.ai.tools.medications._load_medications",
        new=AsyncMock(return_value=_SAMPLE),
    ) as mock_load:
        result = await handler.dispatch(
            user=_FakeUser(), db=fake_db,
            args={"window": "today"},
            logical_today=date(2026, 5, 18),
        )
    mock_load.assert_awaited_once_with(fake_db, 13, date(2026, 5, 18))
    assert result.data["window"] == "today"
    assert result.data["adherence"] == {"slots_logged": 3, "slots_taken": 2}
    assert result.data["active_count"] == 2
    assert len(result.data["active"]) == 2


@pytest.mark.asyncio
async def test_last_7d_returns_last_7_days_adherence_with_pct():
    handler = GetMedicationsHandler()
    with patch(
        "app.routers.ai.tools.medications._load_medications",
        new=AsyncMock(return_value=_SAMPLE),
    ):
        result = await handler.dispatch(
            user=_FakeUser(), db=_FakeDb(),
            args={"window": "last_7d"},
            logical_today=date(2026, 5, 18),
        )
    assert result.data["window"] == "last_7d"
    assert result.data["adherence"]["adherence_pct"] == 90
