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

        # The test user carries a ``firebase_uid``, so the bundle loader's
        # task / habit / project / medication sections would normally route
        # through Firestore. Force them to the Postgres path here so the
        # seeded "Demo task" reaches the assertion — Firestore is not
        # available in CI and the wiring this test cares about (loader →
        # handler → service) is the same on either backend.
        from app.routers.ai import context as _ctx

        async def _pg_tasks(db, user_id, today, *, firebase_uid=None):
            return await _ctx._load_open_tasks_postgres(db, user_id, today)

        async def _pg_habits(db, user_id, today, *, firebase_uid=None):
            return await _ctx._load_habits_with_history_postgres(
                db, user_id, today
            )

        async def _pg_projects(db, user_id, today, *, firebase_uid=None):
            return await _ctx._load_active_projects_postgres(
                db, user_id, today
            )

        async def _pg_meds(db, user_id, today, *, firebase_uid=None):
            return await _ctx._load_medications_postgres(db, user_id, today)

        with patch(
            "app.routers.ai.context._load_open_tasks", new=_pg_tasks,
        ), patch(
            "app.routers.ai.context._load_habits_with_history", new=_pg_habits,
        ), patch(
            "app.routers.ai.context._load_active_projects", new=_pg_projects,
        ), patch(
            "app.routers.ai.context._load_medications", new=_pg_meds,
        ), patch(
            "app.services.ai_productivity.generate_chat_response"
        ) as mock_gen:
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


class TestLoadAllDataBundle:
    """Coverage for the all-data expansion on top of today-only buckets.

    The today-scoped behavior is already locked down by
    ``TestLoadUserContextBundle`` above; this class focuses on the new
    upcoming/backlog/recently-completed task buckets, per-habit
    streak + 7d count, per-project progress, active goals, leisure
    7-day rollup, and medication 7-day adherence + active med list.
    """

    @pytest.mark.asyncio
    async def test_upcoming_tasks_window_excludes_today_and_far_future(
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
                    title="Tomorrow", status="todo",
                    due_date=today + timedelta(days=1),
                ),
                Task(
                    user_id=user_id, project_id=project_id,
                    title="Day 7", status="todo",
                    due_date=today + timedelta(days=7),
                ),
                Task(
                    user_id=user_id, project_id=project_id,
                    title="Day 14", status="todo",
                    due_date=today + timedelta(days=14),
                ),
                Task(
                    user_id=user_id, project_id=project_id,
                    title="Day 15 (outside window)", status="todo",
                    due_date=today + timedelta(days=15),
                ),
                Task(
                    user_id=user_id, project_id=project_id,
                    title="Today shouldn't appear in upcoming", status="todo",
                    due_date=today,
                ),
            ])
            await session.commit()

        async with TestSessionLocal() as session:
            bundle = await load_user_context_bundle(session, user_id, today)

        upcoming_titles = [t["title"] for t in bundle["tasks"]["upcoming"]]
        assert bundle["tasks"]["upcoming_count"] == 3
        assert set(upcoming_titles) == {"Tomorrow", "Day 7", "Day 14"}
        # Upcoming entries carry days_until_due so the AI can ground its phrasing.
        for entry in bundle["tasks"]["upcoming"]:
            assert entry["days_until_due"] >= 1
            assert "due_date" in entry

    @pytest.mark.asyncio
    async def test_backlog_tasks_no_due_or_planned_date(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from tests.conftest import TestSessionLocal

        from app.routers.ai.context import load_user_context_bundle

        user_id = await _resolve_test_user_id()
        project_id = await _seed_project(user_id)
        today = date(2026, 5, 14)
        async with TestSessionLocal() as session:
            for i in range(3):
                session.add(Task(
                    user_id=user_id, project_id=project_id,
                    title=f"Backlog {i}", status="todo",
                ))
            session.add(Task(
                user_id=user_id, project_id=project_id,
                title="Has due date", status="todo",
                due_date=today + timedelta(days=2),
            ))
            session.add(Task(
                user_id=user_id, project_id=project_id,
                title="Has planned date", status="todo",
                planned_date=today,
            ))
            session.add(Task(
                user_id=user_id, project_id=project_id,
                title="Done", status="done",
            ))
            await session.commit()

        async with TestSessionLocal() as session:
            bundle = await load_user_context_bundle(session, user_id, today)

        backlog_titles = {t["title"] for t in bundle["tasks"]["backlog"]}
        assert bundle["tasks"]["backlog_count"] == 3
        assert backlog_titles == {"Backlog 0", "Backlog 1", "Backlog 2"}

    @pytest.mark.asyncio
    async def test_recently_completed_window_7_days(
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
                    title="Done today", status="done",
                    completed_at=datetime(2026, 5, 14, 9, 0, tzinfo=timezone.utc),
                ),
                Task(
                    user_id=user_id, project_id=project_id,
                    title="Done 3d ago", status="done",
                    completed_at=datetime(2026, 5, 11, 9, 0, tzinfo=timezone.utc),
                ),
                Task(
                    user_id=user_id, project_id=project_id,
                    title="Done 6d ago", status="done",
                    completed_at=datetime(2026, 5, 8, 9, 0, tzinfo=timezone.utc),
                ),
                Task(
                    user_id=user_id, project_id=project_id,
                    title="Done 8d ago (outside)", status="done",
                    completed_at=datetime(2026, 5, 6, 9, 0, tzinfo=timezone.utc),
                ),
            ])
            await session.commit()

        async with TestSessionLocal() as session:
            bundle = await load_user_context_bundle(session, user_id, today)

        titles = [t["title"] for t in bundle["tasks"]["recently_completed"]]
        assert bundle["tasks"]["recently_completed_count"] == 3
        assert set(titles) == {"Done today", "Done 3d ago", "Done 6d ago"}
        for entry in bundle["tasks"]["recently_completed"]:
            assert "completed_on" in entry

    @pytest.mark.asyncio
    async def test_habit_streak_counts_consecutive_days_ending_today(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from tests.conftest import TestSessionLocal

        from app.routers.ai.context import load_user_context_bundle

        user_id = await _resolve_test_user_id()
        today = date(2026, 5, 14)
        async with TestSessionLocal() as session:
            h_streak = Habit(user_id=user_id, name="StreakHabit", target_count=1, is_active=True)
            h_broken = Habit(user_id=user_id, name="BrokenHabit", target_count=1, is_active=True)
            h_new = Habit(user_id=user_id, name="NewHabit", target_count=1, is_active=True)
            session.add_all([h_streak, h_broken, h_new])
            await session.flush()
            for i in range(5):
                session.add(HabitCompletion(
                    habit_id=h_streak.id,
                    date=today - timedelta(days=i),
                    count=1,
                ))
            session.add(HabitCompletion(habit_id=h_broken.id, date=today, count=1))
            session.add(HabitCompletion(
                habit_id=h_broken.id, date=today - timedelta(days=2), count=1,
            ))
            await session.commit()

        async with TestSessionLocal() as session:
            bundle = await load_user_context_bundle(session, user_id, today)

        by_name = {h["name"]: h for h in bundle["habits"]["today"]}
        assert by_name["StreakHabit"]["streak"] == 5
        assert by_name["StreakHabit"]["last7_count"] == 5
        assert by_name["BrokenHabit"]["streak"] == 1
        assert by_name["BrokenHabit"]["last7_count"] == 2
        assert by_name["NewHabit"]["streak"] == 0
        assert by_name["NewHabit"]["last7_count"] == 0

    @pytest.mark.asyncio
    async def test_active_projects_carry_progress_and_due(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from tests.conftest import TestSessionLocal

        from app.routers.ai.context import load_user_context_bundle

        user_id = await _resolve_test_user_id()
        today = date(2026, 5, 14)
        async with TestSessionLocal() as session:
            goal = Goal(user_id=user_id, title="G")
            session.add(goal)
            await session.flush()
            project = Project(
                user_id=user_id, goal_id=goal.id, title="With progress",
                status="active", due_date=today + timedelta(days=10),
            )
            session.add(project)
            await session.flush()
            session.add_all([
                Task(user_id=user_id, project_id=project.id, title="A", status="todo"),
                Task(user_id=user_id, project_id=project.id, title="B", status="in_progress"),
                Task(
                    user_id=user_id, project_id=project.id, title="C",
                    status="done",
                    completed_at=datetime(2026, 5, 10, 9, 0, tzinfo=timezone.utc),
                ),
                Task(
                    user_id=user_id, project_id=project.id, title="D",
                    status="done",
                    completed_at=datetime(2026, 5, 12, 9, 0, tzinfo=timezone.utc),
                ),
            ])
            await session.commit()

        async with TestSessionLocal() as session:
            bundle = await load_user_context_bundle(session, user_id, today)

        active = bundle["projects"]["active"]
        assert len(active) == 1
        entry = active[0]
        assert entry["title"] == "With progress"
        assert entry["open_tasks"] == 2
        assert entry["done_tasks"] == 2
        assert entry["total_tasks"] == 4
        assert entry["progress_pct"] == 50
        assert entry["due_date"] == "2026-05-24"
        assert entry["days_until_due"] == 10

    @pytest.mark.asyncio
    async def test_active_goals_surfaced_with_target_date(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from tests.conftest import TestSessionLocal

        from app.routers.ai.context import load_user_context_bundle

        user_id = await _resolve_test_user_id()
        today = date(2026, 5, 14)
        async with TestSessionLocal() as session:
            session.add_all([
                Goal(
                    user_id=user_id, title="Ship v2", status="active",
                    target_date=today + timedelta(days=30),
                ),
                Goal(user_id=user_id, title="Daily exercise", status="active"),
                Goal(
                    user_id=user_id, title="Archived goal", status="archived",
                    target_date=today + timedelta(days=1),
                ),
            ])
            await session.commit()

        async with TestSessionLocal() as session:
            bundle = await load_user_context_bundle(session, user_id, today)

        titles = [g["title"] for g in bundle["goals"]["active"]]
        assert bundle["goals"]["active_count"] == 2
        assert set(titles) == {"Ship v2", "Daily exercise"}
        ship = next(g for g in bundle["goals"]["active"] if g["title"] == "Ship v2")
        assert ship["target_date"] == "2026-06-13"
        assert ship["days_until_target"] == 30

    @pytest.mark.asyncio
    async def test_leisure_aggregates_today_and_last_7_days(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from tests.conftest import TestSessionLocal

        from app.routers.ai.context import load_user_context_bundle

        user_id = await _resolve_test_user_id()
        today = date(2026, 5, 14)
        async with TestSessionLocal() as session:
            session.add_all([
                LeisureSession(
                    id="ls-today-p", user_id=user_id, category="PHYSICAL",
                    duration_minutes=30,
                    logged_at=datetime(2026, 5, 14, 9, 0, tzinfo=timezone.utc),
                    source="TIMER",
                ),
                LeisureSession(
                    id="ls-4d-p", user_id=user_id, category="PHYSICAL",
                    duration_minutes=20,
                    logged_at=datetime(2026, 5, 10, 9, 0, tzinfo=timezone.utc),
                    source="TIMER",
                ),
                LeisureSession(
                    id="ls-4d-s", user_id=user_id, category="SOCIAL",
                    duration_minutes=60,
                    logged_at=datetime(2026, 5, 10, 9, 0, tzinfo=timezone.utc),
                    source="TIMER",
                ),
                LeisureSession(
                    id="ls-old", user_id=user_id, category="PHYSICAL",
                    duration_minutes=999,
                    logged_at=datetime(2026, 5, 6, 9, 0, tzinfo=timezone.utc),
                    source="TIMER",
                ),
            ])
            await session.commit()

        async with TestSessionLocal() as session:
            bundle = await load_user_context_bundle(session, user_id, today)

        assert bundle["leisure"]["today"]["total_minutes"] == 30
        assert bundle["leisure"]["today"]["by_category"] == {"PHYSICAL": 30}
        assert bundle["leisure"]["last_7_days"]["total_minutes"] == 110
        assert bundle["leisure"]["last_7_days"]["by_category"] == {
            "PHYSICAL": 50,
            "SOCIAL": 60,
        }
        assert bundle["leisure_today"] == bundle["leisure"]["today"]

    @pytest.mark.asyncio
    async def test_medications_7d_adherence_plus_active_list(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from tests.conftest import TestSessionLocal

        from app.routers.ai.context import load_user_context_bundle

        user_id = await _resolve_test_user_id()
        today = date(2026, 5, 14)
        async with TestSessionLocal() as session:
            session.add_all([
                Medication(user_id=user_id, name="Adderall", dosage="20mg"),
                Medication(user_id=user_id, name="Vitamin D"),
                Medication(user_id=user_id, name="Old med", is_active=False),
            ])
            for i in range(7):
                d = today - timedelta(days=i)
                session.add(DailyEssentialSlotCompletion(
                    user_id=user_id, date=d, slot_key="08:00",
                    med_ids_json="[]",
                    taken_at=datetime.combine(
                        d, datetime.min.time(), tzinfo=timezone.utc
                    ),
                ))
                session.add(DailyEssentialSlotCompletion(
                    user_id=user_id, date=d, slot_key="20:00",
                    med_ids_json="[]",
                    taken_at=None if i == 0 else datetime.combine(
                        d, datetime.min.time(), tzinfo=timezone.utc
                    ),
                ))
            await session.commit()

        async with TestSessionLocal() as session:
            bundle = await load_user_context_bundle(session, user_id, today)

        meds = bundle["medications"]
        assert meds["today"]["slots_logged"] == 2
        assert meds["today"]["slots_taken"] == 1
        assert meds["last_7_days"]["slots_logged"] == 14
        assert meds["last_7_days"]["slots_taken"] == 13
        assert meds["last_7_days"]["adherence_pct"] == 93
        names = [m["name"] for m in meds["active"]]
        assert meds["active_count"] == 2
        assert set(names) == {"Adderall", "Vitamin D"}
        # Compat alias keeps the today sub-dict accessible at the legacy key.
        assert bundle["medications_today"] == meds["today"]


class TestFormatAllDataBlock:
    def test_renders_upcoming_backlog_recent_completed_sections(self):
        from app.services.ai_productivity import _format_chat_system_prompt

        bundle = {
            "today_iso": "2026-05-14",
            "tasks": {
                "overdue_count": 0, "overdue": [],
                "due_today_count": 0, "due_today": [],
                "planned_today_count": 0, "planned_today": [],
                "upcoming_count": 2,
                "upcoming": [
                    {"id": 11, "title": "Tax forms", "days_until_due": 3,
                     "due_date": "2026-05-17"},
                    {"id": 12, "title": "Doctor visit", "days_until_due": 7,
                     "due_date": "2026-05-21"},
                ],
                "backlog_count": 1,
                "backlog": [{"id": 13, "title": "Sort photos"}],
                "completed_today_count": 0,
                "recently_completed_count": 1,
                "recently_completed": [
                    {"id": 9, "title": "Submit invoice",
                     "completed_on": "2026-05-13"},
                ],
            },
            "habits": {
                "active_count": 1, "completed_today_count": 1,
                "today": [{
                    "name": "Read", "count": 1, "target": 1,
                    "category": None, "streak": 12, "last7_count": 6,
                }],
            },
            "projects": {
                "active_count": 1,
                "active": [{
                    "id": 4, "title": "Ship v2", "open_tasks": 6,
                    "done_tasks": 4, "total_tasks": 10, "progress_pct": 40,
                    "due_date": "2026-06-13", "days_until_due": 30,
                }],
            },
            "goals": {
                "active_count": 1,
                "active": [{
                    "id": 1, "title": "Healthier 2026",
                    "target_date": "2026-12-31", "days_until_target": 231,
                }],
            },
            "leisure": {
                "today": {"total_minutes": 45, "by_category": {"PHYSICAL": 45}},
                "last_7_days": {
                    "total_minutes": 210,
                    "by_category": {"PHYSICAL": 120, "SOCIAL": 90},
                },
            },
            "medications": {
                "today": {"slots_logged": 2, "slots_taken": 1},
                "last_7_days": {
                    "slots_logged": 14, "slots_taken": 13,
                    "adherence_pct": 93,
                },
                "active_count": 2,
                "active": [
                    {"id": 1, "name": "Adderall", "dosage": "20mg"},
                    {"id": 2, "name": "Vitamin D", "dosage": None},
                ],
            },
        }
        out = _format_chat_system_prompt(user_preferences=None, current_state=bundle)

        assert "Upcoming (next 14d): 2" in out
        assert "Tax forms (in 3d)" in out
        assert "Doctor visit (in 7d)" in out
        assert "Backlog (no date): 1" in out
        assert "Sort photos" in out
        assert "Recently completed (last 7d): 1" in out
        assert "Submit invoice (done 2026-05-13)" in out
        assert "12d streak" in out
        assert "6/7 last week" in out
        assert "Ship v2 #4 (4/10, 40%) (due in 30d)" in out
        assert "Active goals: 1" in out
        assert "Healthier 2026" in out
        assert "Leisure last 7d: 210 min (PHYSICAL 120m, SOCIAL 90m)" in out
        assert "Medications last 7d: 13/14 slots taken (93%)" in out
        assert "Active medications: 2" in out
        assert "Adderall (20mg) #1" in out
        assert "Vitamin D #2" in out

    def test_compat_keys_keep_today_only_callers_working(self):
        """Ensure passing the legacy bundle shape (with only leisure_today /
        medications_today + no goals/leisure/medications keys) still renders
        without crashing — protects external test setups that pre-date the
        all-data expansion."""
        from app.services.ai_productivity import _format_chat_system_prompt

        legacy_bundle = {
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
        out = _format_chat_system_prompt(None, current_state=legacy_bundle)
        # Today blocks still render from compat keys.
        assert "Leisure today: 0 min" in out
        assert "Medications today: (no slots logged)" in out
        # 7-day rollups + goals are silently omitted when the bundle didn't
        # carry them.
        assert "Leisure last 7d" not in out
        assert "Medications last 7d" not in out
        assert "Active goals" not in out
