"""AI chat current-state context loader.

The chat handler injects a structured "## Current State" block into the
system prompt so the AI can ground replies in what the user is actually
working on right now — today's tasks, active habits and their progress,
active projects, today's leisure totals, and today's medication
adherence — rather than guessing from the message text alone.

Server-side data only. Mood, check-ins, boundary rules, and the live
Pomodoro/timer state live in Android Room and are NOT persisted to the
FastAPI backend; passing them in is a follow-up that requires extending
``ChatRequest`` (see ``docs/audits/AI_CHAT_CURRENT_STATE_AUDIT.md``).

The output dict is rendered by ``_format_current_state_block`` in
``ai_productivity`` and appended after the preferences block. Token
budget is deliberately tight (<500 tokens at p95) so the bundle doesn't
crowd out the user's message in the model context window.
"""

from __future__ import annotations

from datetime import date, timedelta
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import (
    DailyEssentialSlotCompletion,
    Habit,
    HabitCompletion,
    LeisureSession,
    Project,
    Task,
)

_TASKS_DUE_TODAY_LIMIT = 5
_TASKS_OVERDUE_LIMIT = 5
_TASKS_PLANNED_TODAY_LIMIT = 5
_HABITS_LIMIT = 10
_PROJECTS_LIMIT = 5


def _task_brief(task: Task, today: date) -> dict[str, Any]:
    """Return the minimal task dict the AI needs to ground a reply.

    Keeps the payload tight — title + id + priority + relative-day
    deltas. The full Task ORM row carries description/notes/recurrence
    JSON which is too verbose for a context bundle that fires on every
    chat turn.
    """
    brief: dict[str, Any] = {
        "id": task.id,
        "title": task.title,
        "priority": int(task.priority) if task.priority is not None else 0,
    }
    if task.due_date is not None and task.due_date < today:
        brief["days_overdue"] = (today - task.due_date).days
    return brief


async def _load_today_tasks(
    db: AsyncSession,
    user_id: int,
    today: date,
) -> dict[str, Any]:
    """Bucket the user's open tasks into overdue / due-today / planned-today.

    Issues a single SELECT for open tasks the AI could plausibly mention
    in a coaching reply (due ≤ tomorrow OR planned ≤ today), then buckets
    + caps in Python. Cheaper than 3 separate queries and the row count
    stays small in practice (overdue + today is rarely more than ~30).
    """
    tomorrow = today + timedelta(days=1)
    stmt = (
        select(Task)
        .where(
            Task.user_id == user_id,
            Task.status != "done",
            Task.status != "cancelled",
            (
                (Task.due_date <= tomorrow)
                | (Task.planned_date == today)
            ),
        )
        .order_by(Task.due_date.asc().nulls_last(), Task.priority.desc())
    )
    rows = list((await db.execute(stmt)).scalars().all())

    overdue: list[dict[str, Any]] = []
    due_today: list[dict[str, Any]] = []
    planned_today: list[dict[str, Any]] = []
    overdue_count = 0
    due_today_count = 0
    planned_today_count = 0
    for task in rows:
        if task.due_date is not None and task.due_date < today:
            overdue_count += 1
            if len(overdue) < _TASKS_OVERDUE_LIMIT:
                overdue.append(_task_brief(task, today))
        elif task.due_date == today:
            due_today_count += 1
            if len(due_today) < _TASKS_DUE_TODAY_LIMIT:
                due_today.append(_task_brief(task, today))
        elif task.planned_date == today:
            planned_today_count += 1
            if len(planned_today) < _TASKS_PLANNED_TODAY_LIMIT:
                planned_today.append(_task_brief(task, today))

    completed_today_stmt = (
        select(func.count(Task.id))
        .where(
            Task.user_id == user_id,
            Task.status == "done",
            func.date(Task.completed_at) == today,
        )
    )
    completed_today_count = int(
        (await db.execute(completed_today_stmt)).scalar() or 0
    )

    return {
        "due_today_count": due_today_count,
        "due_today": due_today,
        "overdue_count": overdue_count,
        "overdue": overdue,
        "planned_today_count": planned_today_count,
        "planned_today": planned_today,
        "completed_today_count": completed_today_count,
    }


async def _load_today_habits(
    db: AsyncSession,
    user_id: int,
    today: date,
) -> dict[str, Any]:
    """Return active habits + each habit's today completion count.

    Outer-joined on ``date == today`` so habits not yet completed today
    surface with ``count = 0``. The list is capped at 10; the count of
    active habits and the count completed are surfaced separately so the
    AI can still say "you have 14 habits" when the list is truncated.
    """
    stmt = (
        select(Habit, HabitCompletion.count)
        .outerjoin(
            HabitCompletion,
            (HabitCompletion.habit_id == Habit.id)
            & (HabitCompletion.date == today),
        )
        .where(Habit.user_id == user_id, Habit.is_active.is_(True))
        .order_by(Habit.name.asc())
    )
    rows = list((await db.execute(stmt)).all())

    active_count = len(rows)
    completed_today_count = sum(
        1 for _, completion_count in rows if (completion_count or 0) > 0
    )
    today_list: list[dict[str, Any]] = []
    for habit, completion_count in rows[:_HABITS_LIMIT]:
        today_list.append(
            {
                "name": habit.name,
                "count": int(completion_count or 0),
                "target": int(habit.target_count or 1),
                "category": habit.category or None,
            }
        )
    return {
        "active_count": active_count,
        "completed_today_count": completed_today_count,
        "today": today_list,
    }


async def _load_active_projects(
    db: AsyncSession,
    user_id: int,
) -> dict[str, Any]:
    stmt = (
        select(Project)
        .where(Project.user_id == user_id, Project.status == "active")
        .order_by(Project.sort_order.asc(), Project.id.asc())
    )
    rows = list((await db.execute(stmt)).scalars().all())
    return {
        "active_count": len(rows),
        "active": [
            {"id": p.id, "title": p.title}
            for p in rows[:_PROJECTS_LIMIT]
        ],
    }


async def _load_today_leisure(
    db: AsyncSession,
    user_id: int,
    today: date,
) -> dict[str, Any]:
    """Return aggregate leisure minutes for today, bucketed by category.

    Uses ``func.date(logged_at)`` to keep the query portable across
    SQLite (tests) and Postgres (prod) — both honor the strftime form.
    """
    stmt = (
        select(
            LeisureSession.category,
            func.sum(LeisureSession.duration_minutes),
        )
        .where(
            LeisureSession.user_id == user_id,
            func.date(LeisureSession.logged_at) == today,
        )
        .group_by(LeisureSession.category)
    )
    rows = list((await db.execute(stmt)).all())

    by_category: dict[str, int] = {}
    total = 0
    for category, minutes in rows:
        m = int(minutes or 0)
        by_category[str(category)] = m
        total += m
    return {"total_minutes": total, "by_category": by_category}


async def _load_today_medications(
    db: AsyncSession,
    user_id: int,
    today: date,
) -> dict[str, Any]:
    """Return today's Daily Essentials medication slot adherence.

    ``slots_logged`` counts rows for today; ``slots_taken`` counts those
    with a non-null ``taken_at`` (the user-uncheck case keeps the row
    around with taken_at = NULL — see DailyEssentialSlotCompletion).
    """
    stmt = (
        select(DailyEssentialSlotCompletion)
        .where(
            DailyEssentialSlotCompletion.user_id == user_id,
            DailyEssentialSlotCompletion.date == today,
        )
    )
    rows = list((await db.execute(stmt)).scalars().all())
    slots_taken = sum(1 for r in rows if r.taken_at is not None)
    return {
        "slots_logged": len(rows),
        "slots_taken": slots_taken,
    }


async def load_user_context_bundle(
    db: AsyncSession,
    user_id: int,
    today: date,
) -> dict[str, Any]:
    """Aggregate everything the chat handler injects as current state.

    ``today`` is passed in so the caller (or a test) can pin it instead
    of relying on ``date.today()`` — the chat router uses
    ``datetime.now(timezone.utc).date()`` today; per-user start-of-day is
    a follow-up that needs the client to forward the SoD hour.

    Each sub-loader is independent and failures bubble up; the chat
    handler catches and skips the context block on error so a transient
    DB hiccup degrades to a no-context reply rather than a 500.
    """
    tasks = await _load_today_tasks(db, user_id, today)
    habits = await _load_today_habits(db, user_id, today)
    projects = await _load_active_projects(db, user_id)
    leisure = await _load_today_leisure(db, user_id, today)
    medications = await _load_today_medications(db, user_id, today)
    return {
        "today_iso": today.isoformat(),
        "tasks": tasks,
        "habits": habits,
        "projects": projects,
        "leisure_today": leisure,
        "medications_today": medications,
    }
