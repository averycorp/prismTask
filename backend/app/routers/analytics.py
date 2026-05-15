from datetime import date, timedelta

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.middleware.ai_gate import require_ai_features_enabled
from app.middleware.auth import get_current_user
from app.middleware.rate_limit import RateLimiter
from app.models import Habit, HabitCompletion, Task, TaskStatus, User
from app.schemas.analytics import (
    AnalyticsSummaryResponse,
    HabitCorrelationResponse,
    ProductivityScoreResponse,
    ProjectProgressResponse,
    TimeTrackingResponse,
)
from app.services.analytics import (
    compute_daily_productivity_scores,
    compute_project_burndown,
    compute_summary,
    compute_time_tracking_stats,
    determine_trend,
)

router = APIRouter(prefix="/analytics", tags=["analytics"])

# Rate limiter for habit correlations: 1 call per user per day (86400 seconds)
correlation_rate_limiter = RateLimiter(max_requests=1, window_seconds=86400)


@router.get("/productivity-score", response_model=ProductivityScoreResponse)
async def productivity_score(
    period: str = Query(default="daily", pattern="^(daily|weekly|monthly)$"),
    start_date: date | None = Query(default=None),
    end_date: date | None = Query(default=None),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    today = date.today()
    if end_date is None:
        end_date = today
    if start_date is None:
        start_date = today - timedelta(days=30)

    scores = await compute_daily_productivity_scores(db, current_user.id, start_date, end_date)

    if period == "weekly":
        scores = _aggregate_scores_by_period(scores, 7)
    elif period == "monthly":
        scores = _aggregate_scores_by_period(scores, 30)

    score_values = [s["score"] for s in scores]
    average_score = round(sum(score_values) / len(score_values), 1) if score_values else 0.0
    trend = determine_trend(scores)

    best_day = None
    worst_day = None
    if scores:
        best = max(scores, key=lambda s: s["score"])
        worst = min(scores, key=lambda s: s["score"])
        best_day = {"date": best["date"], "score": best["score"]}
        worst_day = {"date": worst["date"], "score": worst["score"]}

    return ProductivityScoreResponse(
        scores=scores,
        average_score=average_score,
        trend=trend,
        best_day=best_day,
        worst_day=worst_day,
    )


def _aggregate_scores_by_period(daily_scores: list[dict], period_days: int) -> list[dict]:
    """Aggregate daily scores into weekly or monthly buckets."""
    if not daily_scores:
        return []

    result = []
    for i in range(0, len(daily_scores), period_days):
        chunk = daily_scores[i : i + period_days]
        avg_score = round(sum(s["score"] for s in chunk) / len(chunk), 1)
        avg_breakdown = {
            "task_completion": round(sum(s["breakdown"]["task_completion"] for s in chunk) / len(chunk), 1),
            "on_time": round(sum(s["breakdown"]["on_time"] for s in chunk) / len(chunk), 1),
            "habit_completion": round(sum(s["breakdown"]["habit_completion"] for s in chunk) / len(chunk), 1),
            "estimation_accuracy": round(sum(s["breakdown"]["estimation_accuracy"] for s in chunk) / len(chunk), 1),
        }
        result.append({
            "date": chunk[0]["date"],
            "score": avg_score,
            "breakdown": avg_breakdown,
        })

    return result


@router.get("/time-tracking", response_model=TimeTrackingResponse)
async def time_tracking(
    start_date: date | None = Query(default=None),
    end_date: date | None = Query(default=None),
    group_by: str = Query(default="day", pattern="^(project|tag|priority|day)$"),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    today = date.today()
    if end_date is None:
        end_date = today
    if start_date is None:
        start_date = today - timedelta(days=30)

    stats = await compute_time_tracking_stats(db, current_user.id, start_date, end_date, group_by)
    return TimeTrackingResponse(**stats)


@router.get("/project-progress", response_model=ProjectProgressResponse)
async def project_progress(
    project_id: int = Query(...),
    start_date: date | None = Query(default=None),
    end_date: date | None = Query(default=None),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    today = date.today()
    if end_date is None:
        end_date = today
    if start_date is None:
        start_date = today - timedelta(days=30)

    result = await compute_project_burndown(db, current_user.id, project_id, start_date, end_date)
    if result is None:
        raise HTTPException(status_code=404, detail="Project not found")

    return ProjectProgressResponse(**result)


@router.get(
    "/habit-correlations",
    response_model=HabitCorrelationResponse,
    # The endpoint calls Anthropic via `analyze_habit_correlations`, so it
    # has to honour the per-user master AI toggle just like every other
    # AI-touching endpoint does (see `tasks.py:213`, `syllabus.py:121`).
    # Without this dependency, a user who disabled AI features could still
    # trigger an LLM call by hitting this route — the same gap PR #1038
    # closed for `/integrations/gmail/scan`.
    dependencies=[Depends(require_ai_features_enabled)],
)
async def habit_correlations(
    request: Request,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    correlation_rate_limiter.check(request, is_admin=current_user.is_admin)

    today = date.today()
    start = today - timedelta(days=90)

    # Fetch active habits
    habits_result = await db.execute(
        select(Habit).where(Habit.user_id == current_user.id, Habit.is_active.is_(True))
    )
    habits = habits_result.scalars().all()

    if not habits:
        return HabitCorrelationResponse(
            correlations=[],
            top_insight="No active habits found.",
            recommendation="Create some habits to track correlations with your productivity.",
        )

    # Build daily data for the last 90 days
    habit_names = {h.id: h.name for h in habits}

    completions_result = await db.execute(
        select(HabitCompletion).join(Habit).where(
            Habit.user_id == current_user.id,
            HabitCompletion.date >= start,
            HabitCompletion.date <= today,
        )
    )
    completions = completions_result.scalars().all()

    # Map date -> set of habit names completed
    completions_by_date: dict[date, set[str]] = {}
    for c in completions:
        d = c.date
        if d not in completions_by_date:
            completions_by_date[d] = set()
        name = habit_names.get(c.habit_id)
        if name:
            completions_by_date[d].add(name)

    # Compute daily task completion rates
    daily_data = []
    current = start
    while current <= today:
        due_result = await db.execute(
            select(func.count()).select_from(Task).where(
                Task.user_id == current_user.id,
                Task.due_date == current,
                Task.status != TaskStatus.CANCELLED,
            )
        )
        tasks_due = due_result.scalar() or 0

        done_result = await db.execute(
            select(func.count()).select_from(Task).where(
                Task.user_id == current_user.id,
                Task.due_date == current,
                Task.status == TaskStatus.DONE,
            )
        )
        tasks_done = done_result.scalar() or 0

        rate = round(tasks_done / tasks_due * 100, 1) if tasks_due > 0 else 100.0

        daily_data.append({
            "date": current.isoformat(),
            "habits_completed": sorted(completions_by_date.get(current, set())),
            "task_completion_rate": rate,
        })

        current += timedelta(days=1)

    try:
        from app.services.ai_productivity import analyze_habit_correlations

        result = analyze_habit_correlations(daily_data)
    except RuntimeError:
        raise HTTPException(status_code=503, detail="AI service temporarily unavailable")
    except ValueError:
        raise HTTPException(status_code=500, detail="AI returned an invalid response")

    return HabitCorrelationResponse(**result)


@router.get("/summary", response_model=AnalyticsSummaryResponse)
async def analytics_summary(
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    today = date.today()
    result = await compute_summary(db, current_user.id, today)
    return AnalyticsSummaryResponse(**result)
