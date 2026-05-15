"""Tests for the chat current-state context bundle.

Covers three layers:

1. ``load_user_context_bundle`` — direct DB-backed loader. Seeds a user
   with tasks/habits/projects/leisure/medications and verifies bucketing,
   limits, and counts.
2. ``_format_chat_system_prompt`` — the system-prompt renderer. Verifies
   the base prompt text stays intact and that the current-state block
   appends correctly (or no-ops when no bundle is provided).
3. End-to-end wire test on ``POST /chat`` — confirms the handler passes a
   populated ``current_state`` through to the service layer on every
   chat turn, so the AI sees today's grounding.
"""

import sys
import types
from datetime import date, datetime, timedelta, timezone
from unittest.mock import MagicMock, patch

import pytest
import pytest_asyncio
from httpx import AsyncClient
from sqlalchemy import select

from app.models import (
    DailyEssentialSlotCompletion,
    Goal,
    Habit,
    HabitCompletion,
    LeisureSession,
    Medication,
    MedicationSlot,
    Project,
    Task,
    User as UserModel,
)


@pytest_asyncio.fixture(autouse=True)
async def _mock_anthropic_module():
    """Mock the anthropic SDK so the ai_productivity module imports clean."""
    mock_mod = types.ModuleType("anthropic")
    mock_mod.Anthropic = MagicMock  # type: ignore
    mock_mod.APIError = Exception  # type: ignore
    sys.modules["anthropic"] = mock_mod

    import importlib

    import app.services.ai_productivity
    importlib.reload(app.services.ai_productivity)

    yield

    if "anthropic" in sys.modules and sys.modules["anthropic"] is mock_mod:
        del sys.modules["anthropic"]
    importlib.reload(app.services.ai_productivity)


@pytest_asyncio.fixture
async def _clear_rate_limiter():
    from app.routers.ai import chat_rate_limiter
    chat_rate_limiter._requests.clear()
    yield
    chat_rate_limiter._requests.clear()


async def _resolve_test_user_id() -> int:
    from tests.conftest import TestSessionLocal

    async with TestSessionLocal() as session:
        stmt = select(UserModel).where(UserModel.email == "test@example.com")
        return (await session.execute(stmt)).scalar_one().id


async def _seed_project(user_id: int, title: str = "Default project") -> int:
    """Seed a Goal + Project pair (Project.goal_id is non-null). Returns project id."""
    from tests.conftest import TestSessionLocal

    async with TestSessionLocal() as session:
        goal = Goal(user_id=user_id, title=f"{title} goal")
        session.add(goal)
        await session.flush()
        project = Project(
            user_id=user_id, goal_id=goal.id, title=title, status="active"
        )
        session.add(project)
        await session.commit()
        return project.id


class TestLoadUserContextBundle:
    @pytest.mark.asyncio
    async def test_empty_user_returns_zero_buckets(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        """A fresh user with no tasks/habits/etc returns a fully-zero
        bundle — the loader never raises on an empty DB."""
        from tests.conftest import TestSessionLocal

        from app.routers.ai.context import load_user_context_bundle

        user_id = await _resolve_test_user_id()
        async with TestSessionLocal() as session:
            bundle = await load_user_context_bundle(
                session, user_id, date(2026, 5, 14)
            )

        assert bundle["today_iso"] == "2026-05-14"
        assert bundle["tasks"]["overdue_count"] == 0
        assert bundle["tasks"]["due_today_count"] == 0
        assert bundle["tasks"]["planned_today_count"] == 0
        assert bundle["tasks"]["completed_today_count"] == 0
        assert bundle["habits"]["active_count"] == 0
        assert bundle["habits"]["completed_today_count"] == 0
        assert bundle["habits"]["today"] == []
        assert bundle["projects"]["active_count"] == 0
        assert bundle["leisure_today"] == {"total_minutes": 0, "by_category": {}}
        assert bundle["medications_today"] == {"slots_logged": 0, "slots_taken": 0}

    @pytest.mark.asyncio
    async def test_task_buckets_split_overdue_today_planned(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from tests.conftest import TestSessionLocal

        from app.routers.ai.context import load_user_context_bundle

        user_id = await _resolve_test_user_id()
        project_id = await _seed_project(user_id)
        today = date(2026, 5, 14)
        async with TestSessionLocal() as session:
            session.add_all([
                Task(
                    user_id=user_id, project_id=project_id,
                    title="Overdue A", status="todo", priority=3,
                    due_date=today - timedelta(days=3),
                ),
                Task(
                    user_id=user_id, project_id=project_id,
                    title="Overdue B", status="in_progress", priority=2,
                    due_date=today - timedelta(days=1),
                ),
                Task(
                    user_id=user_id, project_id=project_id,
                    title="Today A", status="todo", priority=4,
                    due_date=today,
                ),
                Task(
                    user_id=user_id, project_id=project_id,
                    title="Planned today", status="todo", priority=1,
                    planned_date=today,
                ),
                Task(
                    user_id=user_id, project_id=project_id,
                    title="Future", status="todo",
                    due_date=today + timedelta(days=5),
                ),
                Task(
                    user_id=user_id, project_id=project_id,
                    title="Done today", status="done",
                    completed_at=datetime(2026, 5, 14, 10, 0, tzinfo=timezone.utc),
                ),
                Task(
                    user_id=user_id, project_id=project_id,
                    title="Cancelled today", status="cancelled",
                    due_date=today,
                ),
            ])
            await session.commit()

        async with TestSessionLocal() as session:
            bundle = await load_user_context_bundle(session, user_id, today)

        assert bundle["tasks"]["overdue_count"] == 2
        overdue_titles = [t["title"] for t in bundle["tasks"]["overdue"]]
        assert set(overdue_titles) == {"Overdue A", "Overdue B"}
        # Each overdue task carries its days_overdue delta.
        for entry in bundle["tasks"]["overdue"]:
            assert entry["days_overdue"] >= 1
        assert bundle["tasks"]["due_today_count"] == 1
        assert bundle["tasks"]["due_today"][0]["title"] == "Today A"
        assert bundle["tasks"]["planned_today_count"] == 1
        assert bundle["tasks"]["planned_today"][0]["title"] == "Planned today"
        assert bundle["tasks"]["completed_today_count"] == 1

    @pytest.mark.asyncio
    async def test_task_bucket_respects_caps_and_surfaces_total(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        """When more than the per-bucket cap of open tasks exist, the
        bundle still surfaces the true count so the AI can mention it."""
        from tests.conftest import TestSessionLocal

        from app.routers.ai.context import load_user_context_bundle

        user_id = await _resolve_test_user_id()
        project_id = await _seed_project(user_id)
        today = date(2026, 5, 14)
        async with TestSessionLocal() as session:
            for i in range(8):
                session.add(Task(
                    user_id=user_id, project_id=project_id,
                    title=f"Overdue {i}", status="todo",
                    due_date=today - timedelta(days=i + 1),
                ))
            await session.commit()

        async with TestSessionLocal() as session:
            bundle = await load_user_context_bundle(session, user_id, today)

        assert bundle["tasks"]["overdue_count"] == 8
        assert len(bundle["tasks"]["overdue"]) == 5  # _TASKS_OVERDUE_LIMIT

    @pytest.mark.asyncio
    async def test_habits_outer_joined_with_today_completions(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from tests.conftest import TestSessionLocal

        from app.routers.ai.context import load_user_context_bundle

        user_id = await _resolve_test_user_id()
        today = date(2026, 5, 14)
        async with TestSessionLocal() as session:
            h1 = Habit(user_id=user_id, name="Read", target_count=1, is_active=True)
            h2 = Habit(user_id=user_id, name="Run", target_count=1, is_active=True)
            h3 = Habit(user_id=user_id, name="Stretch", target_count=2, is_active=True)
            h4 = Habit(user_id=user_id, name="Archived", is_active=False)
            session.add_all([h1, h2, h3, h4])
            await session.flush()
            session.add(HabitCompletion(habit_id=h1.id, date=today, count=1))
            session.add(HabitCompletion(habit_id=h3.id, date=today, count=2))
            await session.commit()

        async with TestSessionLocal() as session:
            bundle = await load_user_context_bundle(session, user_id, today)

        habits = bundle["habits"]
        assert habits["active_count"] == 3  # archived excluded
        assert habits["completed_today_count"] == 2
        by_name = {h["name"]: h for h in habits["today"]}
        assert by_name["Read"]["count"] == 1
        assert by_name["Run"]["count"] == 0
        assert by_name["Stretch"]["count"] == 2
        assert by_name["Stretch"]["target"] == 2
        assert "Archived" not in by_name

    @pytest.mark.asyncio
    async def test_projects_filtered_to_active(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from tests.conftest import TestSessionLocal

        from app.routers.ai.context import load_user_context_bundle

        user_id = await _resolve_test_user_id()
        # Goal + 2 active projects + 1 archived project, all under one goal.
        async with TestSessionLocal() as session:
            goal = Goal(user_id=user_id, title="G")
            session.add(goal)
            await session.flush()
            session.add_all([
                Project(user_id=user_id, goal_id=goal.id, title="Alpha", status="active"),
                Project(user_id=user_id, goal_id=goal.id, title="Beta", status="active"),
                Project(user_id=user_id, goal_id=goal.id, title="Old", status="archived"),
            ])
            await session.commit()

        async with TestSessionLocal() as session:
            bundle = await load_user_context_bundle(
                session, user_id, date(2026, 5, 14)
            )
        titles = [p["title"] for p in bundle["projects"]["active"]]
        assert bundle["projects"]["active_count"] == 2
        assert set(titles) == {"Alpha", "Beta"}

    @pytest.mark.asyncio
    async def test_leisure_aggregates_by_category(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from tests.conftest import TestSessionLocal

        from app.routers.ai.context import load_user_context_bundle

        user_id = await _resolve_test_user_id()
        today = date(2026, 5, 14)
        today_noon = datetime(2026, 5, 14, 12, 0, tzinfo=timezone.utc)
        yday_noon = datetime(2026, 5, 13, 12, 0, tzinfo=timezone.utc)
        async with TestSessionLocal() as session:
            session.add_all([
                LeisureSession(
                    id="ls-1", user_id=user_id, category="PHYSICAL",
                    duration_minutes=30, logged_at=today_noon, source="TIMER",
                ),
                LeisureSession(
                    id="ls-2", user_id=user_id, category="PHYSICAL",
                    duration_minutes=15, logged_at=today_noon, source="MANUAL",
                ),
                LeisureSession(
                    id="ls-3", user_id=user_id, category="SOCIAL",
                    duration_minutes=60, logged_at=today_noon, source="TIMER",
                ),
                LeisureSession(
                    id="ls-yday", user_id=user_id, category="PHYSICAL",
                    duration_minutes=999, logged_at=yday_noon, source="TIMER",
                ),
            ])
            await session.commit()

        async with TestSessionLocal() as session:
            bundle = await load_user_context_bundle(session, user_id, today)

        assert bundle["leisure_today"]["total_minutes"] == 105
        assert bundle["leisure_today"]["by_category"] == {
            "PHYSICAL": 45,
            "SOCIAL": 60,
        }

    @pytest.mark.asyncio
    async def test_medications_today_counts_taken_vs_logged(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from tests.conftest import TestSessionLocal

        from app.routers.ai.context import load_user_context_bundle

        user_id = await _resolve_test_user_id()
        today = date(2026, 5, 14)
        async with TestSessionLocal() as session:
            session.add_all([
                DailyEssentialSlotCompletion(
                    user_id=user_id, date=today, slot_key="08:00",
                    med_ids_json="[]",
                    taken_at=datetime(2026, 5, 14, 8, 5, tzinfo=timezone.utc),
                ),
                DailyEssentialSlotCompletion(
                    user_id=user_id, date=today, slot_key="12:00",
                    med_ids_json="[]",
                    taken_at=datetime(2026, 5, 14, 12, 30, tzinfo=timezone.utc),
                ),
                DailyEssentialSlotCompletion(
                    user_id=user_id, date=today, slot_key="20:00",
                    med_ids_json="[]",
                    taken_at=None,  # Un-checked / not yet taken.
                ),
                DailyEssentialSlotCompletion(
                    user_id=user_id, date=today - timedelta(days=1),
                    slot_key="08:00", med_ids_json="[]",
                    taken_at=datetime(2026, 5, 13, 8, 0, tzinfo=timezone.utc),
                ),
            ])
            await session.commit()

        async with TestSessionLocal() as session:
            bundle = await load_user_context_bundle(session, user_id, today)

        assert bundle["medications_today"] == {"slots_logged": 3, "slots_taken": 2}


class TestFormatChatSystemPrompt:
    def test_no_current_state_falls_back_to_legacy_shape(self):
        from app.services.ai_productivity import (
            _CHAT_SYSTEM_PROMPT_BASE,
            _format_chat_system_prompt,
        )

        out = _format_chat_system_prompt(user_preferences=None)
        assert _CHAT_SYSTEM_PROMPT_BASE in out
        assert "Current stored preferences" in out
        # Without a bundle, no current-state heading should appear.
        assert "Current State" not in out

    def test_with_bundle_appends_current_state_block(self):
        from app.services.ai_productivity import (
            _CHAT_SYSTEM_PROMPT_BASE,
            _format_chat_system_prompt,
        )

        bundle = {
            "today_iso": "2026-05-14",
            "tasks": {
                "overdue_count": 1,
                "overdue": [{"id": 7, "title": "Send report", "priority": 3,
                             "days_overdue": 2}],
                "due_today_count": 1,
                "due_today": [{"id": 8, "title": "Lunch w/ Avery", "priority": 1}],
                "planned_today_count": 0,
                "planned_today": [],
                "completed_today_count": 4,
            },
            "habits": {
                "active_count": 2,
                "completed_today_count": 1,
                "today": [
                    {"name": "Read", "count": 1, "target": 1, "category": None},
                    {"name": "Stretch", "count": 0, "target": 1, "category": "Health"},
                ],
            },
            "projects": {
                "active_count": 1,
                "active": [{"id": 3, "title": "PrismTask v2"}],
            },
            "leisure_today": {"total_minutes": 45, "by_category": {"PHYSICAL": 45}},
            "medications_today": {"slots_logged": 2, "slots_taken": 1},
        }
        out = _format_chat_system_prompt(
            user_preferences=[{"id": "p1", "text": "Prefers calm tone"}],
            current_state=bundle,
        )

        # Base prompt text is preserved verbatim.
        assert _CHAT_SYSTEM_PROMPT_BASE in out
        # Preferences block stays.
        assert "[p1] Prefers calm tone" in out
        # Current-state block is appended with its salient bits.
        assert "Current State (today = 2026-05-14)" in out
        assert "Send report" in out
        assert "(2d overdue)" in out
        assert "Lunch w/ Avery" in out
        assert "Completed today: 4" in out
        assert "Habits: 1/2 done today" in out
        assert "Read" in out
        assert "Active projects: 1" in out
        assert "Leisure today: 45 min" in out
        assert "Medications today: 1/2 slots taken" in out

    def test_empty_bundle_sections_render_as_none_placeholders(self):
        """Zero values render as explicit ``(none)`` markers so the AI
        can tell "no data loaded" from "no items today"."""
        from app.services.ai_productivity import _format_chat_system_prompt

        bundle = {
            "today_iso": "2026-05-14",
            "tasks": {
                "overdue_count": 0, "overdue": [],
                "due_today_count": 0, "due_today": [],
                "planned_today_count": 0, "planned_today": [],
                "completed_today_count": 0,
            },
            "habits": {"active_count": 0, "completed_today_count": 0, "today": []},
            "projects": {"active_count": 0, "active": []},
            "leisure_today": {"total_minutes": 0, "by_category": {}},
            "medications_today": {"slots_logged": 0, "slots_taken": 0},
        }
        out = _format_chat_system_prompt(None, current_state=bundle)
        assert "Overdue: (none)" in out
        assert "Due today: (none)" in out
        assert "Planned today: (none)" in out
        assert "(no active habits)" in out
        assert "Leisure today: 0 min" in out
        assert "Medications today: (no slots logged)" in out


class TestChatEndpointWiresCurrentState:
    """End-to-end: POST /chat populates current_state from the DB and
    forwards it to the service. Confirms the loader → handler → service
    chain stays connected for both the populated and empty cases."""

    @pytest.mark.asyncio
    async def test_chat_post_forwards_current_state_to_service(
        self, client: AsyncClient, pro_auth_headers: dict, _clear_rate_limiter
    ):
        user_id = await _resolve_test_user_id()
        project_id = await _seed_project(user_id)
        from tests.conftest import TestSessionLocal

        async with TestSessionLocal() as session:
            session.add(Task(
                user_id=user_id, project_id=project_id,
                title="Demo task", status="todo",
                due_date=datetime.now(timezone.utc).date(),
            ))
            await session.commit()

        with patch("app.services.ai_productivity.generate_chat_response") as mock_gen:
            mock_gen.return_value = {
                "message": "ok",
                "actions": [],
                "tokens_used": {"input": 1, "output": 1},
            }
            resp = await client.post(
                "/api/v1/ai/chat",
                json={"message": "hi", "conversation_id": "chat_2026-05-14_ctx"},
                headers=pro_auth_headers,
            )
        assert resp.status_code == 200, resp.text
        # Confirm the handler passed a populated current_state through.
        kwargs = mock_gen.call_args.kwargs
        assert "current_state" in kwargs
        bundle = kwargs["current_state"]
        assert bundle is not None
        assert bundle["tasks"]["due_today_count"] == 1
        assert bundle["tasks"]["due_today"][0]["title"] == "Demo task"
