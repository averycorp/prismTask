"""A7 — Automation action AI endpoints (``ai.complete`` / ``ai.summarize``).

Two endpoints invoked by the on-device automation engine. They live
under the existing ``/ai/`` router prefix so they automatically inherit:
  * the PII-egress AI gate (``require_ai_features_enabled``)
  * the Pro-tier daily AI rate limiter (per-user budget)
  * the auth dependency

The on-device handlers (``AiCompleteActionHandler`` /
``AiSummarizeActionHandler``) own the master-AI-toggle short-circuit and
the result-mapping (HTTP 451 -> ActionResult.Skipped, others ->
ActionResult.Error). The router stays uniform with its siblings.
"""

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.middleware.auth import get_active_user
from app.middleware.rate_limit import daily_ai_rate_limiter
from app.models import User
from app.schemas.ai import (
    AutomationCompleteRequest,
    AutomationCompleteResponse,
    AutomationSummarizeRequest,
    AutomationSummarizeResponse,
)
from app.services.beta_codes import resolve_effective_tier

router = APIRouter()


@router.post("/automation/complete", response_model=AutomationCompleteResponse)
async def automation_complete(
    data: AutomationCompleteRequest,
    request: Request,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    """`ai.complete` automation action — free-form Anthropic completion.

    Called from the on-device engine when a rule's action chain includes
    ``ai.complete``. The rule author's prompt is forwarded verbatim along
    with optional opaque trigger context.
    """
    from app.routers import ai as _ai_pkg

    _ai_pkg.automation_action_rate_limiter.check(request)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier)

    try:
        from app.services.ai_productivity import (
            generate_automation_completion as ai_complete,
        )

        text = ai_complete(
            prompt=data.prompt,
            context=data.context,
            tier=tier,
        )
    except RuntimeError:
        raise HTTPException(status_code=503, detail="AI service temporarily unavailable")
    except ValueError:
        raise HTTPException(status_code=500, detail="AI returned an invalid response")

    return AutomationCompleteResponse(text=text)


@router.post("/automation/summarize", response_model=AutomationSummarizeResponse)
async def automation_summarize(
    data: AutomationSummarizeRequest,
    request: Request,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    """`ai.summarize` automation action — scoped activity summary.

    Called from the on-device engine when a rule's action chain includes
    ``ai.summarize``. ``scope`` is one of a small closed set the client
    knows how to label ("today", "week", "month", etc.); ``max_items`` is
    the cap on entities the prompt can mention.
    """
    from app.routers import ai as _ai_pkg

    _ai_pkg.automation_action_rate_limiter.check(request)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier)

    try:
        from app.services.ai_productivity import (
            generate_automation_summary as ai_summary,
        )

        summary = ai_summary(
            scope=data.scope,
            max_items=data.max_items,
            context=data.context,
            tier=tier,
        )
    except RuntimeError:
        raise HTTPException(status_code=503, detail="AI service temporarily unavailable")
    except ValueError:
        raise HTTPException(status_code=500, detail="AI returned an invalid response")

    return AutomationSummarizeResponse(summary=summary)
