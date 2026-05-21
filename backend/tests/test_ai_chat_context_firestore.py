"""Firestore-path tests for the AI chat current-state bundle.

Sibling to ``test_ai_chat_context.py`` (Postgres path). When the chat
handler resolves a ``firebase_uid`` for the current user, the bundle's
task / habit / project / medication sections route through the
Firestore-backed helpers in ``services/firestore_tasks.py`` +
``services/firestore_state.py`` instead of the Postgres ORM. Goals and
leisure stay on Postgres regardless of source.

Each test patches the narrow Firestore helper symbols imported lazily
inside ``context.py`` so we don't need a live Firestore (or even the
google-cloud-firestore client) to exercise the bucketing + aggregation
shape callers depend on.
"""

from __future__ import annotations

from datetime import date, timedelta
from unittest.mock import AsyncMock, patch

import pytest

from app.routers.ai.context import load_user_context_bundle
from app.services.firestore_state import (
    HabitCompletionDTO,
    HabitDTO,
    MedicationDTO,
    ProjectDTO,
    SlotCompletionDTO,
)
from app.services.firestore_tasks import TaskDTO


def _task(
    task_id: str,
    *,
    title: str = "T",
    due: date | None = None,
    planned: date | None = None,
    priority: int = 0,
    completed_at: str | None = None,
    project_id: str | None = None,
) -> TaskDTO:
    return TaskDTO(
        task_id=task_id,
        title=title,
        description=None,
        due_date=due.isoformat() if due else None,
        priority=priority,
        project_id=project_id,
        completed_at=completed_at,
        due_date_obj=due,
        planned_date_obj=planned,
    )


@pytest.mark.asyncio
async def test_bundle_buckets_tasks_from_firestore():
    """Open tasks bucket correctly when the firebase_uid path is taken."""
    today = date(2026, 5, 20)
    incomplete = [
        _task("a", title="Overdue", due=today - timedelta(days=2), priority=3),
        _task("b", title="Today", due=today, priority=4),
        _task("c", title="Planned", planned=today, priority=2),
        _task("d", title="Upcoming", due=today + timedelta(days=3)),
        _task("e", title="Far Future", due=today + timedelta(days=60)),
        _task("f", title="Backlog"),
    ]
    completed = [
        _task(
            "z",
            title="Done today",
            completed_at=f"{today.isoformat()}T10:00:00+00:00",
        ),
    ]
    with patch(
        "app.services.firestore_tasks.fetch_incomplete_tasks",
        new=AsyncMock(return_value=incomplete),
    ), patch(
        "app.services.firestore_tasks.fetch_recently_completed_tasks",
        new=AsyncMock(return_value=completed),
    ), patch(
        "app.routers.ai.context._load_habits_with_history_firestore",
        new=AsyncMock(return_value={
            "active_count": 0,
            "completed_today_count": 0,
            "today": [],
        }),
    ), patch(
        "app.routers.ai.context._load_active_projects_firestore",
        new=AsyncMock(return_value={"active_count": 0, "active": []}),
    ), patch(
        "app.routers.ai.context._load_medications_firestore",
        new=AsyncMock(return_value={
            "today": {"slots_logged": 0, "slots_taken": 0},
            "last_7_days": {"slots_logged": 0, "slots_taken": 0},
            "active_count": 0,
            "active": [],
        }),
    ), patch(
        "app.routers.ai.context._load_active_goals",
        new=AsyncMock(return_value={"active_count": 0, "active": []}),
    ), patch(
        "app.routers.ai.context._load_leisure",
        new=AsyncMock(return_value={
            "today": {"total_minutes": 0, "by_category": {}},
            "last_7_days": {"total_minutes": 0, "by_category": {}},
        }),
    ):
        bundle = await load_user_context_bundle(
            db=None,
            user_id=1,
            today=today,
            firebase_uid="firebase-1",
        )

    tasks = bundle["tasks"]
    assert tasks["overdue_count"] == 1
    assert tasks["due_today_count"] == 1
    assert tasks["planned_today_count"] == 1
    # Two upcoming-eligible tasks: "Upcoming" (in 3d) is within the 14-day
    # window; "Far Future" (in 60d) is NOT — the loader drops it from the
    # upcoming bucket entirely (matching the Postgres path).
    assert tasks["upcoming_count"] == 1
    assert tasks["backlog_count"] == 1
    assert tasks["completed_today_count"] == 1
    assert tasks["recently_completed_count"] == 1
    assert [t["title"] for t in tasks["overdue"]] == ["Overdue"]


@pytest.mark.asyncio
async def test_bundle_habits_from_firestore_compute_streak():
    today = date(2026, 5, 20)
    habit = HabitDTO(
        habit_id="h1",
        name="Meditate",
        category="self_care",
        target_count=1,
        is_active=True,
    )
    # Streak: today + yesterday + day before = 3 days, then a gap, then 4
    # earlier rows for the 7d-count.
    completions = [
        HabitCompletionDTO(habit_id="h1", completed_date=today),
        HabitCompletionDTO(habit_id="h1", completed_date=today - timedelta(days=1)),
        HabitCompletionDTO(habit_id="h1", completed_date=today - timedelta(days=2)),
        HabitCompletionDTO(habit_id="h1", completed_date=today - timedelta(days=4)),
    ]
    with patch(
        "app.services.firestore_state.fetch_active_habits",
        new=AsyncMock(return_value=[habit]),
    ), patch(
        "app.services.firestore_state.fetch_habit_completions_since",
        new=AsyncMock(return_value=completions),
    ), patch(
        "app.routers.ai.context._load_open_tasks_firestore",
        new=AsyncMock(return_value={
            "due_today_count": 0, "due_today": [],
            "overdue_count": 0, "overdue": [],
            "planned_today_count": 0, "planned_today": [],
            "upcoming_count": 0, "upcoming": [],
            "backlog_count": 0, "backlog": [],
            "completed_today_count": 0,
            "recently_completed_count": 0,
            "recently_completed": [],
        }),
    ), patch(
        "app.routers.ai.context._load_active_projects_firestore",
        new=AsyncMock(return_value={"active_count": 0, "active": []}),
    ), patch(
        "app.routers.ai.context._load_medications_firestore",
        new=AsyncMock(return_value={
            "today": {"slots_logged": 0, "slots_taken": 0},
            "last_7_days": {"slots_logged": 0, "slots_taken": 0},
            "active_count": 0,
            "active": [],
        }),
    ), patch(
        "app.routers.ai.context._load_active_goals",
        new=AsyncMock(return_value={"active_count": 0, "active": []}),
    ), patch(
        "app.routers.ai.context._load_leisure",
        new=AsyncMock(return_value={
            "today": {"total_minutes": 0, "by_category": {}},
            "last_7_days": {"total_minutes": 0, "by_category": {}},
        }),
    ):
        bundle = await load_user_context_bundle(
            db=None, user_id=1, today=today, firebase_uid="firebase-1"
        )

    habits = bundle["habits"]
    assert habits["active_count"] == 1
    assert habits["completed_today_count"] == 1
    entry = habits["today"][0]
    assert entry["name"] == "Meditate"
    assert entry["count"] == 1
    assert entry["streak"] == 3
    assert entry["last7_count"] == 4


@pytest.mark.asyncio
async def test_bundle_projects_from_firestore_aggregate_counts():
    today = date(2026, 5, 20)
    projects = [
        ProjectDTO(
            project_id="p1",
            title="Launch",
            status="active",
            sort_order=0,
            due_date=today + timedelta(days=5),
        ),
    ]
    with patch(
        "app.services.firestore_state.fetch_active_projects",
        new=AsyncMock(return_value=projects),
    ), patch(
        "app.services.firestore_state.count_project_task_buckets",
        new=AsyncMock(return_value={"p1": (3, 7, 10)}),
    ), patch(
        "app.routers.ai.context._load_open_tasks_firestore",
        new=AsyncMock(return_value={
            "due_today_count": 0, "due_today": [],
            "overdue_count": 0, "overdue": [],
            "planned_today_count": 0, "planned_today": [],
            "upcoming_count": 0, "upcoming": [],
            "backlog_count": 0, "backlog": [],
            "completed_today_count": 0,
            "recently_completed_count": 0,
            "recently_completed": [],
        }),
    ), patch(
        "app.routers.ai.context._load_habits_with_history_firestore",
        new=AsyncMock(return_value={
            "active_count": 0, "completed_today_count": 0, "today": [],
        }),
    ), patch(
        "app.routers.ai.context._load_medications_firestore",
        new=AsyncMock(return_value={
            "today": {"slots_logged": 0, "slots_taken": 0},
            "last_7_days": {"slots_logged": 0, "slots_taken": 0},
            "active_count": 0,
            "active": [],
        }),
    ), patch(
        "app.routers.ai.context._load_active_goals",
        new=AsyncMock(return_value={"active_count": 0, "active": []}),
    ), patch(
        "app.routers.ai.context._load_leisure",
        new=AsyncMock(return_value={
            "today": {"total_minutes": 0, "by_category": {}},
            "last_7_days": {"total_minutes": 0, "by_category": {}},
        }),
    ):
        bundle = await load_user_context_bundle(
            db=None, user_id=1, today=today, firebase_uid="firebase-1"
        )

    proj = bundle["projects"]["active"][0]
    assert proj["id"] == "p1"
    assert proj["open_tasks"] == 7
    assert proj["done_tasks"] == 3
    assert proj["total_tasks"] == 10
    assert proj["progress_pct"] == 30
    assert proj["days_until_due"] == 5


@pytest.mark.asyncio
async def test_bundle_medications_from_firestore_compute_adherence():
    today = date(2026, 5, 20)
    meds = [
        MedicationDTO(
            medication_id="m1",
            name="Sertraline",
            display_label="50mg",
            is_active=True,
        ),
    ]
    # Today: 2/2 taken. Last 7d: 6 logged, 5 taken → adherence 83%.
    slot_completions = [
        SlotCompletionDTO(date=today, taken=True),
        SlotCompletionDTO(date=today, taken=True),
        SlotCompletionDTO(date=today - timedelta(days=1), taken=True),
        SlotCompletionDTO(date=today - timedelta(days=1), taken=False),
        SlotCompletionDTO(date=today - timedelta(days=3), taken=True),
        SlotCompletionDTO(date=today - timedelta(days=5), taken=True),
    ]
    with patch(
        "app.services.firestore_state.fetch_active_medications",
        new=AsyncMock(return_value=meds),
    ), patch(
        "app.services.firestore_state.fetch_slot_completions_between",
        new=AsyncMock(return_value=slot_completions),
    ), patch(
        "app.routers.ai.context._load_open_tasks_firestore",
        new=AsyncMock(return_value={
            "due_today_count": 0, "due_today": [],
            "overdue_count": 0, "overdue": [],
            "planned_today_count": 0, "planned_today": [],
            "upcoming_count": 0, "upcoming": [],
            "backlog_count": 0, "backlog": [],
            "completed_today_count": 0,
            "recently_completed_count": 0,
            "recently_completed": [],
        }),
    ), patch(
        "app.routers.ai.context._load_habits_with_history_firestore",
        new=AsyncMock(return_value={
            "active_count": 0, "completed_today_count": 0, "today": [],
        }),
    ), patch(
        "app.routers.ai.context._load_active_projects_firestore",
        new=AsyncMock(return_value={"active_count": 0, "active": []}),
    ), patch(
        "app.routers.ai.context._load_active_goals",
        new=AsyncMock(return_value={"active_count": 0, "active": []}),
    ), patch(
        "app.routers.ai.context._load_leisure",
        new=AsyncMock(return_value={
            "today": {"total_minutes": 0, "by_category": {}},
            "last_7_days": {"total_minutes": 0, "by_category": {}},
        }),
    ):
        bundle = await load_user_context_bundle(
            db=None, user_id=1, today=today, firebase_uid="firebase-1"
        )

    meds_block = bundle["medications"]
    assert meds_block["today"] == {"slots_logged": 2, "slots_taken": 2}
    assert meds_block["last_7_days"]["slots_logged"] == 6
    assert meds_block["last_7_days"]["slots_taken"] == 5
    assert meds_block["last_7_days"]["adherence_pct"] == 83
    assert meds_block["active"][0]["name"] == "Sertraline"
    assert meds_block["active"][0]["dosage"] == "50mg"
