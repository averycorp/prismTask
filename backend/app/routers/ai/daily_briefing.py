"""Daily briefing endpoint."""

import logging
from datetime import date, datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Request
from google.api_core.exceptions import FailedPrecondition
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.middleware.auth import get_active_user
from app.middleware.rate_limit import daily_ai_rate_limiter
from app.models import Habit, User
from app.schemas.ai import DailyBriefingRequest, DailyBriefingResponse
from app.services.beta_codes import resolve_effective_tier
from app.services.firestore_tasks import (
    filter_due_on,
    filter_overdue_before,
    filter_planned_on,
)

from ._common import _log_empty_short_circuit, _require_firebase_uid

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post("/daily-briefing", response_model=DailyBriefingResponse)
async def daily_briefing(
    data: DailyBriefingRequest,
    request: Request,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    from app.routers import ai as _ai_pkg
    _ai_pkg.briefing_rate_limiter.check(request, is_admin=current_user.is_admin)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier, is_admin=current_user.is_admin)

    try:
        target_date = date.fromisoformat(data.date) if data.date else date.today()
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid date format; expected YYYY-MM-DD")

    uid = _require_firebase_uid(current_user)

    # One Firestore read, then partition in Python. Keeps query count bounded
    # even for users with many incomplete tasks.
    incomplete_tasks = await _ai_pkg.fetch_incomplete_tasks(uid)
    overdue_tasks = [t.to_briefing_dict() for t in filter_overdue_before(incomplete_tasks, target_date)]
    today_tasks = [t.to_briefing_dict() for t in filter_due_on(incomplete_tasks, target_date)]
    planned_tasks = [t.to_briefing_dict() for t in filter_planned_on(incomplete_tasks, target_date)]

    # Habits still live in Postgres — unchanged by this migration.
    habits_query = select(Habit).where(
        Habit.user_id == current_user.id,
        Habit.is_active.is_(True),
    )
    habits_result = await db.execute(habits_query)
    habits = [{"name": h.name, "frequency": h.frequency.value} for h in habits_result.scalars().all()]

    # Recently completed (last 24h) comes from Firestore too now. The query
    # is equality + range across two fields, which requires the composite
    # index defined in firestore.indexes.json. Surface a missing index as
    # 503 (with a loud log line) instead of an opaque 500 so an unprovisioned
    # environment is diagnosable from the response alone.
    yesterday = datetime.now(timezone.utc) - timedelta(hours=24)
    try:
        completed_dtos = await _ai_pkg.fetch_recently_completed_tasks(uid, yesterday)
    except FailedPrecondition as exc:
        logger.error("daily-briefing Firestore index missing: %s", exc)
        raise HTTPException(
            status_code=503,
            detail="Daily briefing temporarily unavailable — backend index is rebuilding",
        ) from exc
    completed_tasks = [{"task_id": t.task_id, "title": t.title} for t in completed_dtos]

    all_tasks = overdue_tasks + today_tasks + planned_tasks
    if not all_tasks and not habits:
        _log_empty_short_circuit(current_user, "daily-briefing")
        return DailyBriefingResponse(
            greeting="Good morning! You have a clear day ahead.",
            top_priorities=[],
            heads_up=[],
            suggested_order=[],
            habit_reminders=[],
            day_type="light",
        )

    try:
        from app.services.ai_productivity import generate_daily_briefing as ai_briefing

        result = ai_briefing(
            today=target_date,
            overdue_tasks=overdue_tasks,
            today_tasks=today_tasks,
            planned_tasks=planned_tasks,
            habits=habits,
            completed_tasks=completed_tasks,
            tier=tier,
        )
    except RuntimeError:
        raise HTTPException(status_code=503, detail="AI service temporarily unavailable")
    except ValueError:
        raise HTTPException(status_code=500, detail="AI returned an invalid response")

    return DailyBriefingResponse(**result)
