"""AI chat current-state context loader.

The chat handler injects a structured "## Current State" block into the
system prompt so the AI can ground replies in what the user is actually
working on — not just today, but their full open ladder (overdue +
today + upcoming + backlog), recently completed work, every active
habit and its streak, every active project and its progress, the last
week of leisure totals, and the last week of medication adherence.

Server-side data only. Mood, check-ins, boundary rules, and the live
Pomodoro/timer state live in Android Room and are NOT persisted to the
FastAPI backend; passing them in is a follow-up that requires extending
``ChatRequest``.

The output dict is rendered by ``_format_current_state_block`` in
``ai_productivity`` and appended after the preferences block. Per-bucket
caps keep p95 bundle size in the ~1500–1800 token range — comfortably
inside the chat handler's per-call budget while still surfacing the
true counts so the AI never undercounts behind a cap.
"""

from __future__ import annotations

from datetime import date, timedelta
from typing import Any

from sqlalchemy import case, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import (
    DailyEssentialSlotCompletion,
    Goal,
    Habit,
    HabitCompletion,
    LeisureSession,
    Medication,
    Project,
    Task,
)

_TASKS_DUE_TODAY_LIMIT = 5
_TASKS_OVERDUE_LIMIT = 5
_TASKS_PLANNED_TODAY_LIMIT = 5
_TASKS_UPCOMING_LIMIT = 10
_TASKS_BACKLOG_LIMIT = 10
_TASKS_RECENT_COMPLETED_LIMIT = 10
_HABITS_LIMIT = 10
_PROJECTS_LIMIT = 5
_GOALS_LIMIT = 5
_MEDICATIONS_LIMIT = 10

_UPCOMING_WINDOW_DAYS = 14
_RECENT_COMPLETED_WINDOW_DAYS = 7
_HABIT_STREAK_WINDOW_DAYS = 60
_LEISURE_RECENT_WINDOW_DAYS = 7
_MEDICATION_RECENT_WINDOW_DAYS = 7


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
    elif task.due_date is not None and task.due_date > today:
        brief["days_until_due"] = (task.due_date - today).days
        brief["due_date"] = task.due_date.isoformat()
    return brief


def _completed_task_brief(task: Task) -> dict[str, Any]:
    """Return a recently-completed task brief — title + id + completed_at date."""
    brief: dict[str, Any] = {
        "id": task.id,
        "title": task.title,
    }
    if task.completed_at is not None:
        brief["completed_on"] = task.completed_at.date().isoformat()
    return brief


async def _load_open_tasks(
    db: AsyncSession,
    user_id: int,
    today: date,
) -> dict[str, Any]:
    """Bucket open tasks into overdue / due-today / planned-today / upcoming / backlog.

    Single SELECT for every open task the user has — bucketing then
    happens in Python. In practice users have tens, not thousands, of
    open tasks; pulling the lot is cheaper than five window-bounded
    queries and lets the bundle surface accurate counts for every
    bucket.
    """
    stmt = (
        select(Task)
        .where(
            Task.user_id == user_id,
            Task.status != "done",
            Task.status != "cancelled",
        )
        .order_by(Task.due_date.asc().nulls_last(), Task.priority.desc())
    )
    rows = list((await db.execute(stmt)).scalars().all())

    upcoming_cutoff = today + timedelta(days=_UPCOMING_WINDOW_DAYS)

    overdue: list[dict[str, Any]] = []
    due_today: list[dict[str, Any]] = []
    planned_today: list[dict[str, Any]] = []
    upcoming: list[dict[str, Any]] = []
    backlog: list[dict[str, Any]] = []
    overdue_count = 0
    due_today_count = 0
    planned_today_count = 0
    upcoming_count = 0
    backlog_count = 0

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
        elif task.due_date is not None and today < task.due_date <= upcoming_cutoff:
            upcoming_count += 1
            if len(upcoming) < _TASKS_UPCOMING_LIMIT:
                upcoming.append(_task_brief(task, today))
        elif task.due_date is None and task.planned_date is None:
            backlog_count += 1
            if len(backlog) < _TASKS_BACKLOG_LIMIT:
                backlog.append(_task_brief(task, today))

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

    recent_window_start = today - timedelta(days=_RECENT_COMPLETED_WINDOW_DAYS - 1)
    recent_stmt = (
        select(Task)
        .where(
            Task.user_id == user_id,
            Task.status == "done",
            func.date(Task.completed_at) >= recent_window_start,
            func.date(Task.completed_at) <= today,
        )
        .order_by(Task.completed_at.desc())
    )
    recent_rows = list((await db.execute(recent_stmt)).scalars().all())
    recently_completed = [
        _completed_task_brief(t) for t in recent_rows[:_TASKS_RECENT_COMPLETED_LIMIT]
    ]
    recently_completed_count = len(recent_rows)

    return {
        "due_today_count": due_today_count,
        "due_today": due_today,
        "overdue_count": overdue_count,
        "overdue": overdue,
        "planned_today_count": planned_today_count,
        "planned_today": planned_today,
        "upcoming_count": upcoming_count,
        "upcoming": upcoming,
        "backlog_count": backlog_count,
        "backlog": backlog,
        "completed_today_count": completed_today_count,
        "recently_completed_count": recently_completed_count,
        "recently_completed": recently_completed,
    }


def _streak_from_dates(completion_dates: set[date], today: date) -> int:
    """Walk back from ``today`` counting consecutive days with a completion.

    ``today`` itself counts as a streak day if present. The caller is
    responsible for capping the window — this helper only walks until
    the first gap.
    """
    streak = 0
    cursor = today
    while cursor in completion_dates:
        streak += 1
        cursor -= timedelta(days=1)
    return streak


async def _load_habits_with_history(
    db: AsyncSession,
    user_id: int,
    today: date,
) -> dict[str, Any]:
    """Return active habits + today's count + streak + last-7-day count.

    Pulls active habits and the last ``_HABIT_STREAK_WINDOW_DAYS`` days
    of completion rows in two queries, then assembles streak + 7d count
    in Python. Total rows scanned stay small (typical user: <15 habits
    × 60 days = <1000 rows even fully completed).
    """
    habit_stmt = (
        select(Habit)
        .where(Habit.user_id == user_id, Habit.is_active.is_(True))
        .order_by(Habit.name.asc())
    )
    habits = list((await db.execute(habit_stmt)).scalars().all())

    if not habits:
        return {
            "active_count": 0,
            "completed_today_count": 0,
            "today": [],
        }

    habit_ids = [h.id for h in habits]
    window_start = today - timedelta(days=_HABIT_STREAK_WINDOW_DAYS - 1)
    last7_start = today - timedelta(days=6)
    completion_stmt = (
        select(HabitCompletion)
        .where(
            HabitCompletion.habit_id.in_(habit_ids),
            HabitCompletion.date >= window_start,
            HabitCompletion.date <= today,
        )
    )
    completion_rows = list((await db.execute(completion_stmt)).scalars().all())

    by_habit: dict[int, list[HabitCompletion]] = {hid: [] for hid in habit_ids}
    for row in completion_rows:
        by_habit.setdefault(row.habit_id, []).append(row)

    active_count = len(habits)
    completed_today_count = 0
    today_list: list[dict[str, Any]] = []
    for habit in habits:
        rows_for_habit = by_habit.get(habit.id, [])
        today_count = 0
        last7_count = 0
        completion_dates: set[date] = set()
        for row in rows_for_habit:
            if (row.count or 0) <= 0:
                continue
            completion_dates.add(row.date)
            if row.date == today:
                today_count = int(row.count or 0)
            if last7_start <= row.date <= today:
                last7_count += 1
        if today_count > 0:
            completed_today_count += 1
        if len(today_list) < _HABITS_LIMIT:
            today_list.append(
                {
                    "name": habit.name,
                    "count": today_count,
                    "target": int(habit.target_count or 1),
                    "category": habit.category or None,
                    "streak": _streak_from_dates(completion_dates, today),
                    "last7_count": last7_count,
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
    today: date,
) -> dict[str, Any]:
    """Return active projects + per-project task counts + due/progress.

    Adds a single GROUP BY on ``tasks.project_id`` to count open vs
    completed tasks per project, then renders progress % as
    ``done / total`` when total > 0.
    """
    proj_stmt = (
        select(Project)
        .where(Project.user_id == user_id, Project.status == "active")
        .order_by(Project.sort_order.asc(), Project.id.asc())
    )
    projects = list((await db.execute(proj_stmt)).scalars().all())

    if not projects:
        return {"active_count": 0, "active": []}

    project_ids = [p.id for p in projects]
    done_expr = case((Task.status == "done", 1), else_=0)
    open_expr = case(
        (Task.status.in_(["todo", "in_progress"]), 1), else_=0
    )
    counts_stmt = (
        select(
            Task.project_id,
            func.sum(done_expr).label("done"),
            func.sum(open_expr).label("open"),
            func.count(Task.id).label("total"),
        )
        .where(Task.project_id.in_(project_ids))
        .group_by(Task.project_id)
    )
    counts_rows = (await db.execute(counts_stmt)).all()
    by_project: dict[int, tuple[int, int, int]] = {}
    for project_id, done, open_count, total in counts_rows:
        by_project[int(project_id)] = (int(done or 0), int(open_count or 0), int(total or 0))

    active: list[dict[str, Any]] = []
    for p in projects[:_PROJECTS_LIMIT]:
        done, open_count, total = by_project.get(p.id, (0, 0, 0))
        entry: dict[str, Any] = {
            "id": p.id,
            "title": p.title,
            "open_tasks": open_count,
            "done_tasks": done,
            "total_tasks": total,
        }
        if total > 0:
            entry["progress_pct"] = int(round(100 * done / total))
        if p.due_date is not None:
            entry["due_date"] = p.due_date.isoformat()
            entry["days_until_due"] = (p.due_date - today).days
        active.append(entry)

    return {"active_count": len(projects), "active": active}


async def _load_active_goals(
    db: AsyncSession,
    user_id: int,
    today: date,
) -> dict[str, Any]:
    """Return active goals — title + optional target date.

    Goals are the top of the ladder above projects; surfacing them gives
    the AI orientation on what the active projects ladder up to.
    """
    stmt = (
        select(Goal)
        .where(Goal.user_id == user_id, Goal.status == "active")
        .order_by(Goal.sort_order.asc(), Goal.id.asc())
    )
    goals = list((await db.execute(stmt)).scalars().all())
    active: list[dict[str, Any]] = []
    for g in goals[:_GOALS_LIMIT]:
        entry: dict[str, Any] = {"id": g.id, "title": g.title}
        if g.target_date is not None:
            entry["target_date"] = g.target_date.isoformat()
            entry["days_until_target"] = (g.target_date - today).days
        active.append(entry)
    return {"active_count": len(goals), "active": active}


async def _load_leisure(
    db: AsyncSession,
    user_id: int,
    today: date,
) -> dict[str, Any]:
    """Return today's and last-7-day leisure minutes by category.

    Two GROUP BY queries against ``leisure_sessions`` over today and the
    7-day window — both portable across SQLite (tests) and Postgres
    (prod) via ``func.date(logged_at)``.
    """
    today_stmt = (
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
    today_rows = list((await db.execute(today_stmt)).all())

    by_category_today: dict[str, int] = {}
    total_today = 0
    for category, minutes in today_rows:
        m = int(minutes or 0)
        by_category_today[str(category)] = m
        total_today += m

    recent_start = today - timedelta(days=_LEISURE_RECENT_WINDOW_DAYS - 1)
    recent_stmt = (
        select(
            LeisureSession.category,
            func.sum(LeisureSession.duration_minutes),
        )
        .where(
            LeisureSession.user_id == user_id,
            func.date(LeisureSession.logged_at) >= recent_start,
            func.date(LeisureSession.logged_at) <= today,
        )
        .group_by(LeisureSession.category)
    )
    recent_rows = list((await db.execute(recent_stmt)).all())
    by_category_recent: dict[str, int] = {}
    total_recent = 0
    for category, minutes in recent_rows:
        m = int(minutes or 0)
        by_category_recent[str(category)] = m
        total_recent += m

    return {
        "today": {"total_minutes": total_today, "by_category": by_category_today},
        "last_7_days": {
            "total_minutes": total_recent,
            "by_category": by_category_recent,
        },
    }


async def _load_medications(
    db: AsyncSession,
    user_id: int,
    today: date,
) -> dict[str, Any]:
    """Return medication adherence today + last 7 days + active medication list.

    ``slots_logged`` counts materialized slot rows; ``slots_taken``
    counts those with a non-null ``taken_at``. The 7-day rate divides
    the totals so a single missed dose doesn't look like 0% adherence.
    """
    today_stmt = (
        select(DailyEssentialSlotCompletion)
        .where(
            DailyEssentialSlotCompletion.user_id == user_id,
            DailyEssentialSlotCompletion.date == today,
        )
    )
    today_rows = list((await db.execute(today_stmt)).scalars().all())
    today_slots_taken = sum(1 for r in today_rows if r.taken_at is not None)

    recent_start = today - timedelta(days=_MEDICATION_RECENT_WINDOW_DAYS - 1)
    recent_stmt = (
        select(DailyEssentialSlotCompletion)
        .where(
            DailyEssentialSlotCompletion.user_id == user_id,
            DailyEssentialSlotCompletion.date >= recent_start,
            DailyEssentialSlotCompletion.date <= today,
        )
    )
    recent_rows = list((await db.execute(recent_stmt)).scalars().all())
    recent_logged = len(recent_rows)
    recent_taken = sum(1 for r in recent_rows if r.taken_at is not None)

    med_stmt = (
        select(Medication)
        .where(Medication.user_id == user_id, Medication.is_active.is_(True))
        .order_by(Medication.name.asc())
    )
    meds = list((await db.execute(med_stmt)).scalars().all())
    active_meds = [
        {"id": m.id, "name": m.name, "dosage": m.dosage or None}
        for m in meds[:_MEDICATIONS_LIMIT]
    ]

    bundle: dict[str, Any] = {
        "today": {
            "slots_logged": len(today_rows),
            "slots_taken": today_slots_taken,
        },
        "last_7_days": {
            "slots_logged": recent_logged,
            "slots_taken": recent_taken,
        },
        "active_count": len(meds),
        "active": active_meds,
    }
    if recent_logged > 0:
        bundle["last_7_days"]["adherence_pct"] = int(
            round(100 * recent_taken / recent_logged)
        )
    return bundle


async def load_user_context_bundle(
    db: AsyncSession,
    user_id: int,
    today: date,
) -> dict[str, Any]:
    """Aggregate everything the chat handler injects as current state.

    ``today`` is passed in so the caller (or a test) can pin it instead
    of relying on ``date.today()``. The chat handler resolves it from
    the client-forwarded SoD anchor via ``user_context.today`` (PR
    #1446) so the bundle's notion of "today" matches the rest of the
    app.

    Each sub-loader is independent and failures bubble up; the chat
    handler catches and skips the context block on error so a transient
    DB hiccup degrades to a no-context reply rather than a 500.

    The returned dict has these keys; the formatter (`ai_productivity.
    _format_current_state_block`) renders each section in this order:

    - ``tasks``    — full open ladder (overdue/today/planned/upcoming/
                      backlog) + today's completion count + last 7 days.
    - ``habits``   — active habits with today count, streak, last7 count.
    - ``projects`` — active projects with task counts + progress + due.
    - ``goals``    — active goals with optional target_date.
    - ``leisure``  — today's totals + last 7 days, both by category.
    - ``medications`` — today + 7d adherence + active medication list.

    Keys ``leisure_today`` and ``medications_today`` are retained as
    compat aliases pointing at the today-only sub-dicts so prior callers
    + the PR #1442 test suite keep working unchanged.
    """
    tasks = await _load_open_tasks(db, user_id, today)
    habits = await _load_habits_with_history(db, user_id, today)
    projects = await _load_active_projects(db, user_id, today)
    goals = await _load_active_goals(db, user_id, today)
    leisure = await _load_leisure(db, user_id, today)
    medications = await _load_medications(db, user_id, today)
    return {
        "today_iso": today.isoformat(),
        "tasks": tasks,
        "habits": habits,
        "projects": projects,
        "goals": goals,
        "leisure": leisure,
        "medications": medications,
        # Compat aliases for callers/tests written before the all-data
        # expansion. Removing them would break the PR #1442 test suite
        # without buying anything (the today sub-dict is the same object).
        "leisure_today": leisure["today"],
        "medications_today": medications["today"],
    }


# --- Phase 1 (AI Assistant tool-use loop) public re-exports ---
# Tool handlers in ``app/routers/ai/tools/`` import these public names so
# their unit tests can patch a single symbol per loader instead of the
# private builder. Logic lives in the private implementations above; the
# public surface is intentionally thin.

async def load_habits_today(db, user_id: int, today: date) -> dict:
    """Public wrapper around ``_load_habits_with_history`` for the
    ``get_habits`` tool handler. Same shape, same data."""
    return await _load_habits_with_history(db, user_id, today)


async def load_active_projects(db, user_id: int, today: date) -> dict:
    """Public wrapper around ``_load_active_projects`` for the
    ``get_projects`` tool handler. Same shape, same data."""
    return await _load_active_projects(db, user_id, today)


async def load_medications(db, user_id: int, today: date) -> dict:
    """Public wrapper around ``_load_medications`` for the ``get_medications``
    tool handler. Same shape: today + last_7_days + active list."""
    return await _load_medications(db, user_id, today)
