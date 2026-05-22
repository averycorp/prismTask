"""Integration endpoints — Gmail scan, suggestion inbox, accept/reject."""

import json
import logging

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.middleware.ai_gate import require_ai_features_enabled
from app.middleware.auth import get_current_user
from app.models import (
    IntegrationConfig,
    IntegrationSource,
    SuggestedTask,
    SuggestionStatus,
    User,
)
from app.schemas.integration import (
    GmailScanResponse,
    SuggestionAcceptOverrides,
    SuggestionAcceptResponse,
    SuggestionBatchRequest,
    SuggestedTaskResponse,
)
from app.services.integrations.base import accept_suggestion

logger = logging.getLogger(__name__)

router = APIRouter(tags=["integrations"])


def _suggestion_to_response(s: SuggestedTask) -> SuggestedTaskResponse:
    tags = None
    if s.suggested_tags_json:
        try:
            tags = json.loads(s.suggested_tags_json)
        except (json.JSONDecodeError, TypeError):
            tags = None

    return SuggestedTaskResponse(
        id=s.id,
        source=s.source.value if hasattr(s.source, "value") else s.source,
        source_id=s.source_id,
        source_title=s.source_title,
        source_url=s.source_url,
        suggested_title=s.suggested_title,
        suggested_description=s.suggested_description,
        suggested_due_date=s.suggested_due_date,
        suggested_priority=s.suggested_priority,
        suggested_project=s.suggested_project,
        suggested_tags=tags,
        confidence=s.confidence,
        status=s.status.value if hasattr(s.status, "value") else s.status,
        extracted_at=s.extracted_at,
        created_at=s.created_at,
    )


# ---------- Gmail scan ----------


@router.post(
    "/integrations/gmail/scan",
    response_model=GmailScanResponse,
    # AI-features opt-out gate (PII egress audit follow-up, 2026-05-01).
    # `scan_gmail` calls Anthropic's Claude API with user email subjects /
    # snippets / from-addresses (see `gmail_integration.py:227`), so it has
    # to honor the same `X-PrismTask-AI-Features: disabled` opt-out as the
    # `/ai/*` and `/tasks/parse*` routes. The original PR #788/#790 audit
    # missed this endpoint because the integrations router landed two weeks
    # earlier (commit 385b340d, 2026-04-11). The 2026-05-01 re-audit
    # (`cowork_outputs/pii_leak_surface_reaudit_REPORT.md`) caught it.
    dependencies=[Depends(require_ai_features_enabled)],
)
async def scan_gmail_inbox(
    since_hours: int = Query(default=24, ge=1, le=168),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """Trigger an immediate Gmail inbox scan for the authenticated user."""
    from app.services.integrations.gmail_integration import scan_gmail

    # Look up Gmail integration config for credentials
    result = await db.execute(
        select(IntegrationConfig).where(
            IntegrationConfig.user_id == current_user.id,
            IntegrationConfig.source == IntegrationSource.GMAIL.value,
        )
    )
    config = result.scalar_one_or_none()
    credentials_json = None
    if config and config.config_json:
        credentials_json = config.config_json

    try:
        suggestions = await scan_gmail(
            db=db,
            user_id=current_user.id,
            since_hours=since_hours,
            credentials_json=credentials_json,
        )
    except Exception as e:
        logger.error(f"Gmail scan failed for user {current_user.id}: {e}")
        raise HTTPException(status_code=502, detail=f"Gmail scan failed: {e}")

    # Update last_scan_at
    if config:
        from datetime import datetime, timezone

        config.last_scan_at = datetime.now(timezone.utc)
        await db.flush()

    # Fetch the newly stored rows for a full response
    new_ids = [s["id"] for s in suggestions]
    stored_rows: list[SuggestedTask] = []
    if new_ids:
        result = await db.execute(
            select(SuggestedTask).where(SuggestedTask.id.in_(new_ids))
        )
        stored_rows = list(result.scalars().all())

    return GmailScanResponse(
        scanned=len(suggestions),
        new_suggestions=len(stored_rows),
        suggestions=[_suggestion_to_response(s) for s in stored_rows],
    )


# ---------- Suggestion inbox ----------


@router.get(
    "/integrations/suggestions",
    response_model=list[SuggestedTaskResponse],
)
async def list_suggestions(
    source: str | None = Query(default=None),
    suggestion_status: str = Query(default="pending", alias="status"),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """List all suggestions for the current user, filterable by source and status."""
    query = select(SuggestedTask).where(
        SuggestedTask.user_id == current_user.id,
    )

    if suggestion_status:
        try:
            status_enum = SuggestionStatus(suggestion_status)
        except ValueError:
            raise HTTPException(status_code=400, detail=f"Invalid status: {suggestion_status}")
        query = query.where(SuggestedTask.status == status_enum)

    if source:
        try:
            source_enum = IntegrationSource(source)
        except ValueError:
            raise HTTPException(status_code=400, detail=f"Invalid source: {source}")
        query = query.where(SuggestedTask.source == source_enum)

    query = query.order_by(SuggestedTask.extracted_at.desc())

    result = await db.execute(query)
    rows = result.scalars().all()
    return [_suggestion_to_response(s) for s in rows]


# ---------- Accept / Reject ----------


@router.post(
    "/integrations/suggestions/{suggestion_id}/accept",
    response_model=SuggestionAcceptResponse,
)
async def accept_suggestion_endpoint(
    suggestion_id: int,
    overrides: SuggestionAcceptOverrides | None = None,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """Accept a suggestion and create a real task from it."""
    result = await db.execute(
        select(SuggestedTask).where(
            SuggestedTask.id == suggestion_id,
            SuggestedTask.user_id == current_user.id,
        )
    )
    suggestion = result.scalar_one_or_none()
    if not suggestion:
        raise HTTPException(status_code=404, detail="Suggestion not found")

    if suggestion.status != SuggestionStatus.PENDING:
        raise HTTPException(status_code=400, detail="Suggestion is not pending")

    override_dict = overrides.model_dump(exclude_unset=True) if overrides else {}

    try:
        task = await accept_suggestion(db, suggestion, current_user.id, override_dict)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    return SuggestionAcceptResponse(
        suggestion_id=suggestion.id,
        task_id=task.id,
        task_title=task.title,
    )


@router.post(
    "/integrations/suggestions/{suggestion_id}/reject",
    status_code=status.HTTP_204_NO_CONTENT,
)
async def reject_suggestion_endpoint(
    suggestion_id: int,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """Reject a suggestion."""
    result = await db.execute(
        select(SuggestedTask).where(
            SuggestedTask.id == suggestion_id,
            SuggestedTask.user_id == current_user.id,
        )
    )
    suggestion = result.scalar_one_or_none()
    if not suggestion:
        raise HTTPException(status_code=404, detail="Suggestion not found")

    suggestion.status = SuggestionStatus.REJECTED
    await db.flush()


# ---------- Batch operations ----------


@router.post(
    "/integrations/suggestions/batch",
    response_model=dict,
)
async def batch_suggestions(
    body: SuggestionBatchRequest,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """Batch accept and/or reject multiple suggestions at once."""
    accepted_tasks: list[dict] = []
    rejected_count = 0

    # Accept
    for sid in body.accept:
        result = await db.execute(
            select(SuggestedTask).where(
                SuggestedTask.id == sid,
                SuggestedTask.user_id == current_user.id,
                SuggestedTask.status == SuggestionStatus.PENDING,
            )
        )
        suggestion = result.scalar_one_or_none()
        if suggestion:
            try:
                task = await accept_suggestion(db, suggestion, current_user.id)
                accepted_tasks.append({
                    "suggestion_id": suggestion.id,
                    "task_id": task.id,
                    "task_title": task.title,
                })
            except ValueError:
                continue

    # Reject
    if body.reject:
        from sqlalchemy import update

        result = await db.execute(
            update(SuggestedTask)
            .where(
                SuggestedTask.id.in_(body.reject),
                SuggestedTask.user_id == current_user.id,
                SuggestedTask.status == SuggestionStatus.PENDING,
            )
            .values(status=SuggestionStatus.REJECTED)
        )
        rejected_count += result.rowcount

    await db.flush()

    return {
        "accepted": accepted_tasks,
        "rejected_count": rejected_count,
    }
