"""Eisenhower / cognitive-load / life-category classification endpoints.

Three "classify-one-task-by-text" endpoints share this file because they
have identical shape (per-task synchronous classification) and identical
rate-limit profile (20/min burst), just different ML targets:

* ``POST /eisenhower`` — batch Eisenhower categorization for incomplete tasks.
* ``POST /eisenhower/classify_text`` — single-task Eisenhower from text.
* ``POST /cognitive-load/classify_text`` — single-task cognitive load.
* ``POST /life-category/classify_text`` — single-task life category.
"""

from datetime import date

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.middleware.auth import get_active_user
from app.middleware.rate_limit import daily_ai_rate_limiter
from app.models import User
from app.schemas.ai import (
    CognitiveLoadClassifyTextRequest,
    CognitiveLoadClassifyTextResponse,
    EisenhowerClassifyTextRequest,
    EisenhowerClassifyTextResponse,
    EisenhowerRequest,
    EisenhowerResponse,
    EisenhowerSummary,
    LifeCategoryClassifyTextRequest,
    LifeCategoryClassifyTextResponse,
)
from app.services.beta_codes import resolve_effective_tier

from ._common import _get_incomplete_tasks, _log_empty_short_circuit

router = APIRouter()

# Rate limiters are looked up at call time via the package module so
# tests can patch ``app.routers.ai.X`` and have their stub take effect.


@router.post("/eisenhower", response_model=EisenhowerResponse)
async def categorize_eisenhower(
    data: EisenhowerRequest,
    request: Request,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    from app.routers import ai as _ai_pkg
    _ai_pkg.ai_rate_limiter.check(request)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier)

    tasks = await _get_incomplete_tasks(current_user, data.task_ids)
    if not tasks:
        _log_empty_short_circuit(current_user, "eisenhower")
        return EisenhowerResponse(
            categorizations=[],
            summary=EisenhowerSummary(),
        )

    task_dicts = [t.to_ai_dict() for t in tasks]

    try:
        from app.services.ai_productivity import categorize_eisenhower as ai_categorize

        categorizations = ai_categorize(task_dicts, date.today(), tier=tier)
    except RuntimeError:
        raise HTTPException(status_code=503, detail="AI service temporarily unavailable")
    except ValueError:
        raise HTTPException(status_code=500, detail="AI returned an invalid response")

    valid_task_ids = {t.task_id for t in tasks}
    valid_quadrants = {"Q1", "Q2", "Q3", "Q4"}
    cleaned = []
    for cat in categorizations:
        tid = cat.get("task_id")
        if tid is not None:
            tid = str(tid)
        quadrant = cat.get("quadrant", "")
        reason = cat.get("reason", "")
        if tid in valid_task_ids and quadrant in valid_quadrants:
            cleaned.append({"task_id": tid, "quadrant": quadrant, "reason": reason})

    summary = EisenhowerSummary()
    for cat in cleaned:
        current = getattr(summary, cat["quadrant"])
        setattr(summary, cat["quadrant"], current + 1)

    return EisenhowerResponse(
        categorizations=[
            {"task_id": c["task_id"], "quadrant": c["quadrant"], "reason": c["reason"]}
            for c in cleaned
        ],
        summary=summary,
    )


@router.post("/eisenhower/classify_text", response_model=EisenhowerClassifyTextResponse)
async def classify_eisenhower_text(
    data: EisenhowerClassifyTextRequest,
    request: Request,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    """Per-task text-based Eisenhower classification.

    Called fire-and-forget from the Android client immediately after a task
    is created locally, so the classification is present before the task
    has been synced to the backend. Rate-limited separately from the batch
    endpoint — see ``eisenhower_classify_text_rate_limiter``.
    """
    from app.routers import ai as _ai_pkg
    _ai_pkg.eisenhower_classify_text_rate_limiter.check(request)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier)

    try:
        from app.services.ai_productivity import (
            classify_eisenhower_text as ai_classify_text,
        )

        result = ai_classify_text(
            title=data.title,
            description=data.description,
            due_date=data.due_date,
            priority=data.priority,
            today=date.today(),
            tier=tier,
        )
    except RuntimeError:
        raise HTTPException(status_code=503, detail="AI service temporarily unavailable")
    except ValueError:
        raise HTTPException(status_code=500, detail="AI returned an invalid response")

    return EisenhowerClassifyTextResponse(
        quadrant=result["quadrant"],
        reason=result["reason"],
    )


@router.post("/cognitive-load/classify_text", response_model=CognitiveLoadClassifyTextResponse)
async def classify_cognitive_load_text(
    data: CognitiveLoadClassifyTextRequest,
    request: Request,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    """Per-task text-based cognitive-load classification.

    Optional AI upgrade over the on-device keyword classifier. Called
    fire-and-forget from clients on task creation when AI Features are
    enabled — the on-device `CognitiveLoadClassifier` keyword fallback
    is what runs synchronously at save time. Rate-limited separately
    from the Eisenhower endpoint via
    ``cognitive_load_classify_text_rate_limiter``.

    See ``docs/COGNITIVE_LOAD.md`` § Inference rules.
    """
    from app.routers import ai as _ai_pkg
    _ai_pkg.cognitive_load_classify_text_rate_limiter.check(request)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier)

    try:
        from app.services.ai_productivity import (
            classify_cognitive_load_text as ai_classify_load,
        )

        result = ai_classify_load(
            title=data.title,
            description=data.description,
            tier=tier,
        )
    except RuntimeError:
        raise HTTPException(status_code=503, detail="AI service temporarily unavailable")
    except ValueError:
        raise HTTPException(status_code=500, detail="AI returned an invalid response")

    return CognitiveLoadClassifyTextResponse(
        load=result["load"],
        reason=result["reason"],
    )


@router.post("/life-category/classify_text", response_model=LifeCategoryClassifyTextResponse)
async def classify_life_category_text(
    data: LifeCategoryClassifyTextRequest,
    request: Request,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    """Per-task text-based Work-Life Balance category classification.

    Invoked from the Android task editor's OrganizeTab "Auto" button
    (manual user tap). The on-device ``LifeCategoryClassifier`` keyword
    fallback runs synchronously to give instant feedback; this endpoint
    is the AI upgrade that overwrites the on-device guess when AI
    Features are enabled. Rate-limited via
    ``life_category_classify_text_rate_limiter``.
    """
    from app.routers import ai as _ai_pkg
    _ai_pkg.life_category_classify_text_rate_limiter.check(request)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier)

    try:
        from app.services.ai_productivity import (
            classify_life_category_text as ai_classify_life,
        )

        result = ai_classify_life(
            title=data.title,
            description=data.description,
            tier=tier,
        )
    except RuntimeError:
        raise HTTPException(status_code=503, detail="AI service temporarily unavailable")
    except ValueError:
        raise HTTPException(status_code=500, detail="AI returned an invalid response")

    return LifeCategoryClassifyTextResponse(
        category=result["category"],
        reason=result["reason"],
    )
