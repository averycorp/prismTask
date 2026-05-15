"""A2 — NLP batch schedule operations endpoint (pulled from Phase H)."""

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.middleware.auth import get_active_user
from app.middleware.rate_limit import daily_ai_rate_limiter
from app.models import User
from app.schemas.ai import BatchParseRequest, BatchParseResponse
from app.services.beta_codes import resolve_effective_tier

router = APIRouter()


@router.post("/batch-parse", response_model=BatchParseResponse)
async def batch_parse(
    data: BatchParseRequest,
    request: Request,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    """Parse a natural-language batch command into a structured mutation
    plan. The Android client renders the result as a diff-preview screen
    and only commits after user approval. This endpoint never writes —
    it's pure parsing.

    Stateless by design: the client supplies the entity context inline
    rather than the backend pulling from Firestore. That keeps the
    endpoint side-effect-free, lets the client filter to what's
    actually loaded, and matches the WeeklyReviewRequest pattern.
    """
    from app.routers import ai as _ai_pkg

    _ai_pkg.batch_parse_rate_limiter.check(request, is_admin=current_user.is_admin)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier, is_admin=current_user.is_admin)

    user_context_dict = data.user_context.model_dump()

    try:
        from app.services.ai_productivity import parse_batch_command as ai_batch_parse

        result = ai_batch_parse(
            command_text=data.command_text,
            user_context=user_context_dict,
            tier=tier,
        )
    except RuntimeError:
        raise HTTPException(status_code=503, detail="AI service temporarily unavailable")
    except ValueError:
        raise HTTPException(status_code=500, detail="AI returned an invalid response")

    # Force the proposed contract — even if the service forgot to set it.
    return BatchParseResponse(
        mutations=result.get("mutations", []),
        confidence=float(result.get("confidence", 0.0)),
        ambiguous_entities=result.get("ambiguous_entities", []),
        proposed=True,
    )
