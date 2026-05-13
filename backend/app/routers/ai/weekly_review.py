"""v1.4.0 V6 — AI weekly review endpoint."""

import logging
from datetime import date

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.middleware.auth import get_active_user
from app.middleware.rate_limit import daily_ai_rate_limiter
from app.models import User
from app.schemas.ai import WeeklyReviewRequest, WeeklyReviewResponse
from app.services.beta_codes import resolve_effective_tier
from app.services.firestore_tasks import TaskDTO

from ._common import _require_firebase_uid

logger = logging.getLogger(__name__)

router = APIRouter()


_WEEKLY_REVIEW_OPEN_TASK_CAP = 20


def _rank_open_tasks_for_review(tasks: list[TaskDTO]) -> list[TaskDTO]:
    """Rank + cap open tasks for the weekly-review prompt.

    Ordering: priority DESC, then due_date ASC with nulls last, then
    sort_order ASC as a stable tiebreaker. Capped at 20 items to keep
    Sonnet token usage bounded. If there are <=20 open tasks, we skip the
    ranking and return them all (still applying the stable sort so the
    prompt is deterministic).
    """
    # date.max keeps null due dates at the end of the ASC sort.
    far_future = date.max

    def sort_key(t: TaskDTO):
        return (
            -int(t.priority or 0),
            t.due_date_obj or far_future,
            int(t.sort_order or 0),
        )

    ranked = sorted(tasks, key=sort_key)
    return ranked[:_WEEKLY_REVIEW_OPEN_TASK_CAP]


@router.post("/weekly-review", response_model=WeeklyReviewResponse)
async def weekly_review(
    data: WeeklyReviewRequest,
    request: Request,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    """
    Generate an ADHD-friendly weekly review narrative using a hybrid
    input pattern:
      * The client sends per-task summaries for completed and slipped tasks
        plus optional opaque habit/pomodoro aggregates and free-form notes.
      * The backend enriches with the user's current open tasks from
        Firestore so the "going forward" section of the review is grounded
        in live data.

    Schema v2 — breaking change from the aggregate-counts v1 schema. Old
    clients posting the v1 body shape will get 422 until their prompts
    land. See WeeklyReviewRequest in schemas/ai.py for the v2 contract.
    """
    from app.routers import ai as _ai_pkg

    _ai_pkg.weekly_review_rate_limiter.check(request)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier)

    uid = _require_firebase_uid(current_user)
    open_dtos = await _ai_pkg.fetch_incomplete_tasks(uid)
    top_open = _rank_open_tasks_for_review(open_dtos)

    logger.info(
        "AI weekly_review: user_id=%s endpoint=weekly_review "
        "completed=%d slipped=%d open_total=%d open_included=%d",
        current_user.id,
        len(data.completed_tasks),
        len(data.slipped_tasks),
        len(open_dtos),
        len(top_open),
    )

    completed_dicts = [t.model_dump() for t in data.completed_tasks]
    slipped_dicts = [t.model_dump() for t in data.slipped_tasks]
    open_dicts = [t.to_briefing_dict() for t in top_open]

    try:
        from app.services.ai_productivity import generate_weekly_review as ai_review
        result = ai_review(
            week_start=data.week_start.isoformat(),
            week_end=data.week_end.isoformat(),
            completed_tasks=completed_dicts,
            slipped_tasks=slipped_dicts,
            open_tasks=open_dicts,
            habit_summary=data.habit_summary,
            pomodoro_summary=data.pomodoro_summary,
            notes=data.notes,
            tier=tier,
        )
    except RuntimeError:
        raise HTTPException(status_code=503, detail="AI service temporarily unavailable")
    except ValueError:
        raise HTTPException(status_code=500, detail="AI returned an invalid response")

    return WeeklyReviewResponse(
        week_start=data.week_start,
        week_end=data.week_end,
        wins=result.get("wins", []),
        slips=result.get("slips", []),
        patterns=result.get("patterns", []),
        next_week_focus=result.get("next_week_focus", []),
        narrative=result.get("narrative", ""),
    )
