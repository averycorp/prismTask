"""AI urgency-scoring endpoint (Pro).

Single endpoint that scores a batch of tasks for urgency (0.0..1.0) using
Claude Haiku. Surfaced on the Android client as the Pro-only upgrade
over the on-device ``UrgencyScorer`` heuristic: when the user picks the
URGENCY sort on the Tasks screen and the per-feature AI-urgency toggle
is on, the client batches the visible tasks here and uses the returned
scores in place of the local formula. Any task missing from the
response (or any failure) falls back to the on-device formula
per-task on the client — never block sort rendering on this call.
"""

from datetime import date

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.middleware.auth import get_active_user
from app.middleware.rate_limit import daily_ai_rate_limiter
from app.models import User
from app.schemas.ai import UrgencyScoreRequest, UrgencyScoreResponse
from app.services.beta_codes import resolve_effective_tier

router = APIRouter()


@router.post("/urgency/score", response_model=UrgencyScoreResponse)
async def score_urgency(
    data: UrgencyScoreRequest,
    request: Request,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    """Score a batch of up to 50 tasks for urgency using Claude Haiku.

    Pro-only by design — the Android client gates the call with
    ``ProFeatureGate.AI_URGENCY``. The server enforces the AI features
    master switch via the parent router's ``require_ai_features_enabled``
    dependency and the per-tier daily AI cap via
    ``daily_ai_rate_limiter``.
    """
    from app.routers import ai as _ai_pkg
    _ai_pkg.urgency_score_rate_limiter.check(request, is_admin=current_user.is_admin)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier, is_admin=current_user.is_admin)

    payload = [
        {
            "id": t.id,
            "title": t.title,
            "description": t.description or "",
            "due_date": t.due_date,
            "priority": t.priority,
            "created_at": t.created_at,
            "subtask_count": t.subtask_count,
            "subtask_completed": t.subtask_completed,
        }
        for t in data.tasks
    ]

    try:
        from app.services.ai_productivity import score_tasks_urgency

        scored = score_tasks_urgency(payload, date.today(), tier=tier)
    except RuntimeError:
        raise HTTPException(status_code=503, detail="AI service temporarily unavailable")
    except ValueError:
        raise HTTPException(status_code=500, detail="AI returned an invalid response")

    valid_ids = {t.id for t in data.tasks}
    cleaned = [s for s in scored if s.get("id") in valid_ids]
    return UrgencyScoreResponse(scores=cleaned)
