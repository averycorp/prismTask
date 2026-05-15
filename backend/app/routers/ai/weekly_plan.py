"""Weekly plan endpoint."""

from datetime import date, timedelta

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.middleware.auth import get_active_user
from app.middleware.rate_limit import daily_ai_rate_limiter
from app.models import User
from app.schemas.ai import WeeklyPlanRequest, WeeklyPlanResponse
from app.services.beta_codes import resolve_effective_tier
from app.services.firestore_tasks import filter_recurring

from ._common import _get_incomplete_tasks, _log_empty_short_circuit

router = APIRouter()


@router.post("/weekly-plan", response_model=WeeklyPlanResponse)
async def weekly_plan(
    data: WeeklyPlanRequest,
    request: Request,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    from app.routers import ai as _ai_pkg
    _ai_pkg.weekly_plan_rate_limiter.check(request, is_admin=current_user.is_admin)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier, is_admin=current_user.is_admin)

    if data.week_start:
        try:
            week_start = date.fromisoformat(data.week_start)
        except ValueError:
            raise HTTPException(status_code=400, detail="Invalid week_start format; expected YYYY-MM-DD")
    else:
        # Default to next Monday
        today = date.today()
        days_until_monday = (7 - today.weekday()) % 7
        if days_until_monday == 0:
            days_until_monday = 7
        week_start = today + timedelta(days=days_until_monday)

    week_end = week_start + timedelta(days=6)

    incomplete = await _get_incomplete_tasks(current_user)
    all_tasks = [t.to_briefing_dict() for t in incomplete]
    recurring_tasks = [t.to_briefing_dict() for t in filter_recurring(incomplete)]

    if not all_tasks:
        _log_empty_short_circuit(current_user, "weekly-plan")
        return WeeklyPlanResponse(
            plan={},
            unscheduled=[],
            week_summary="No tasks to plan for this week.",
            tips=[],
        )

    try:
        from app.services.ai_productivity import generate_weekly_plan as ai_plan

        result = ai_plan(
            week_start=week_start,
            week_end=week_end,
            work_days=data.preferences.work_days,
            focus_hours_per_day=data.preferences.focus_hours_per_day,
            prefer_front_loading=data.preferences.prefer_front_loading,
            tasks=all_tasks,
            recurring_tasks=recurring_tasks,
            tier=tier,
        )
    except RuntimeError:
        raise HTTPException(status_code=503, detail="AI service temporarily unavailable")
    except ValueError:
        raise HTTPException(status_code=500, detail="AI returned an invalid response")

    return WeeklyPlanResponse(**result)
