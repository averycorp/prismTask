"""Leisure Budget v2.0 router.

Three resource collections under ``/api/v1/leisure``:

* ``GET / POST / PATCH / DELETE /activities`` — manage the user's
  leisure-activity pool.
* ``GET / POST /sessions`` — leisure-session history (timer + manual).
* ``GET / PATCH /settings`` — singleton per-user settings.

The settings PATCH is gated by ``require_leisure_enforcement_choice``
so non-Pro users can't escalate to MEDIUM / HARD enforcement. All other
endpoints are tier-agnostic per Q1 lock — the refresh limit and the
feature itself are universally available.
"""

import json
from datetime import datetime, timezone
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import desc, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.middleware.auth import get_current_user
from app.middleware.leisure_gate import require_leisure_enforcement_choice
from app.models import LeisureActivity, LeisureSession, LeisureSettings, User
from app.schemas.leisure import (
    LeisureActivityCreate,
    LeisureActivityResponse,
    LeisureActivityUpdate,
    LeisureSessionCreate,
    LeisureSessionResponse,
    LeisureSettingsResponse,
    LeisureSettingsUpdate,
)

router = APIRouter(prefix="/leisure", tags=["leisure"])


_DEFAULT_CATEGORIES = ["PHYSICAL", "SOCIAL", "CREATIVE", "PASSIVE"]


def _settings_to_response(row: LeisureSettings) -> LeisureSettingsResponse:
    try:
        categories = json.loads(row.enabled_categories) if row.enabled_categories else _DEFAULT_CATEGORIES
    except (ValueError, TypeError):
        # Defense-in-depth: if a stale row carries malformed JSON,
        # surface the spec default rather than 500ing the client.
        categories = _DEFAULT_CATEGORIES
    return LeisureSettingsResponse(
        daily_target_minutes=row.daily_target_minutes,
        weekend_target_minutes=row.weekend_target_minutes,
        enforcement_mode=row.enforcement_mode,
        refresh_limit=row.refresh_limit,
        enabled_categories=categories,
        pending_enforcement_mode=row.pending_enforcement_mode,
        pending_enforcement_effective_date=row.pending_enforcement_effective_date,
        updated_at=row.updated_at,
    )


async def _get_or_create_settings(
    db: AsyncSession, user: User
) -> LeisureSettings:
    result = await db.execute(
        select(LeisureSettings).where(LeisureSettings.user_id == user.id)
    )
    row = result.scalar_one_or_none()
    if row is not None:
        return row
    now = datetime.now(timezone.utc)
    row = LeisureSettings(
        user_id=user.id,
        daily_target_minutes=60,
        weekend_target_minutes=None,
        enforcement_mode="SOFT",
        refresh_limit=3,
        enabled_categories=json.dumps(_DEFAULT_CATEGORIES),
        updated_at=now,
    )
    db.add(row)
    await db.flush()
    return row


@router.get("/activities", response_model=list[LeisureActivityResponse])
async def list_activities(
    enabled_only: bool = Query(False),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> list[LeisureActivityResponse]:
    stmt = select(LeisureActivity).where(
        LeisureActivity.user_id == current_user.id
    )
    if enabled_only:
        stmt = stmt.where(LeisureActivity.enabled.is_(True))
    stmt = stmt.order_by(LeisureActivity.category, LeisureActivity.name)
    result = await db.execute(stmt)
    return [
        LeisureActivityResponse.model_validate(row, from_attributes=True)
        for row in result.scalars().all()
    ]


@router.post(
    "/activities",
    response_model=LeisureActivityResponse,
    status_code=status.HTTP_201_CREATED,
)
async def create_activity(
    body: LeisureActivityCreate,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> LeisureActivityResponse:
    existing = await db.execute(
        select(LeisureActivity).where(LeisureActivity.id == body.id)
    )
    if existing.scalar_one_or_none() is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Activity {body.id} already exists",
        )
    now = datetime.now(timezone.utc)
    row = LeisureActivity(
        id=body.id,
        user_id=current_user.id,
        name=body.name,
        category=body.category,
        default_duration_minutes=body.default_duration_minutes,
        enabled=body.enabled,
        created_at=now,
        updated_at=now,
    )
    db.add(row)
    await db.flush()
    return LeisureActivityResponse.model_validate(row, from_attributes=True)


@router.patch(
    "/activities/{activity_id}", response_model=LeisureActivityResponse
)
async def update_activity(
    activity_id: str,
    body: LeisureActivityUpdate,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> LeisureActivityResponse:
    result = await db.execute(
        select(LeisureActivity).where(
            LeisureActivity.id == activity_id,
            LeisureActivity.user_id == current_user.id,
        )
    )
    row = result.scalar_one_or_none()
    if row is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Activity {activity_id} not found",
        )
    if body.name is not None:
        row.name = body.name
    if body.category is not None:
        row.category = body.category
    if body.default_duration_minutes is not None:
        row.default_duration_minutes = body.default_duration_minutes
    if body.enabled is not None:
        row.enabled = body.enabled
    row.updated_at = datetime.now(timezone.utc)
    await db.flush()
    await db.refresh(row)
    return LeisureActivityResponse.model_validate(row, from_attributes=True)


@router.delete(
    "/activities/{activity_id}", status_code=status.HTTP_204_NO_CONTENT
)
async def delete_activity(
    activity_id: str,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> None:
    result = await db.execute(
        select(LeisureActivity).where(
            LeisureActivity.id == activity_id,
            LeisureActivity.user_id == current_user.id,
        )
    )
    row = result.scalar_one_or_none()
    if row is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Activity {activity_id} not found",
        )
    await db.delete(row)
    await db.flush()


@router.get("/sessions", response_model=list[LeisureSessionResponse])
async def list_sessions(
    since: Optional[datetime] = Query(None),
    limit: int = Query(200, ge=1, le=1000),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> list[LeisureSessionResponse]:
    stmt = select(LeisureSession).where(
        LeisureSession.user_id == current_user.id
    )
    if since is not None:
        stmt = stmt.where(LeisureSession.logged_at >= since)
    stmt = stmt.order_by(desc(LeisureSession.logged_at)).limit(limit)
    result = await db.execute(stmt)
    return [
        LeisureSessionResponse.model_validate(row, from_attributes=True)
        for row in result.scalars().all()
    ]


@router.post(
    "/sessions",
    response_model=LeisureSessionResponse,
    status_code=status.HTTP_201_CREATED,
)
async def create_session(
    body: LeisureSessionCreate,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> LeisureSessionResponse:
    existing = await db.execute(
        select(LeisureSession).where(LeisureSession.id == body.id)
    )
    if existing.scalar_one_or_none() is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Session {body.id} already exists",
        )
    # If activity_id is provided, verify it belongs to the same user.
    # The client is expected to mint a valid ID even for free-text
    # entries (auto-add-to-pool path); pure-orphan sessions (no pool
    # entry) are allowed for forward compatibility but the FK is
    # left null in that case.
    if body.activity_id is not None:
        activity_row = await db.execute(
            select(LeisureActivity).where(
                LeisureActivity.id == body.activity_id,
                LeisureActivity.user_id == current_user.id,
            )
        )
        if activity_row.scalar_one_or_none() is None:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"Activity {body.activity_id} not found for user",
            )
    now = datetime.now(timezone.utc)
    row = LeisureSession(
        id=body.id,
        user_id=current_user.id,
        activity_id=body.activity_id,
        category=body.category,
        duration_minutes=body.duration_minutes,
        logged_at=body.logged_at,
        source=body.source,
        created_at=now,
    )
    db.add(row)
    # Update the denormalized last_completed_at on the activity row so
    # the random-pull algorithm has accurate recency without joining.
    if body.activity_id is not None:
        activity_result = await db.execute(
            select(LeisureActivity).where(
                LeisureActivity.id == body.activity_id
            )
        )
        activity = activity_result.scalar_one_or_none()
        if activity is not None and (
            activity.last_completed_at is None
            or activity.last_completed_at < body.logged_at
        ):
            activity.last_completed_at = body.logged_at
            activity.updated_at = now
    await db.flush()
    return LeisureSessionResponse.model_validate(row, from_attributes=True)


@router.get("/settings", response_model=LeisureSettingsResponse)
async def get_settings(
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> LeisureSettingsResponse:
    row = await _get_or_create_settings(db, current_user)
    return _settings_to_response(row)


@router.patch("/settings", response_model=LeisureSettingsResponse)
async def update_settings(
    body: LeisureSettingsUpdate = Depends(require_leisure_enforcement_choice),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> LeisureSettingsResponse:
    row = await _get_or_create_settings(db, current_user)
    if body.daily_target_minutes is not None:
        row.daily_target_minutes = body.daily_target_minutes
    if body.weekend_target_minutes is not None:
        row.weekend_target_minutes = body.weekend_target_minutes
    if body.refresh_limit is not None:
        row.refresh_limit = body.refresh_limit
    if body.enabled_categories is not None:
        row.enabled_categories = json.dumps(body.enabled_categories)
    if body.enforcement_mode is not None:
        # Deferred-setting pattern: changes take effect next day per
        # the v2.0 spec. Same-day promotion is opt-in via
        # ``promote_pending_enforcement`` (the day-reset worker sets it
        # when promoting on the SoD boundary).
        if body.enforcement_mode != row.enforcement_mode:
            row.pending_enforcement_mode = body.enforcement_mode
            # Effective date computed on the client (it knows the user's
            # SoD-adjusted "tomorrow"); the server stores it raw.
            # If absent, default to today+1 UTC as a sane fallback.
            row.pending_enforcement_effective_date = (
                datetime.now(timezone.utc).date()
            )
    if body.promote_pending_enforcement and row.pending_enforcement_mode:
        row.enforcement_mode = row.pending_enforcement_mode
        row.pending_enforcement_mode = None
        row.pending_enforcement_effective_date = None
    row.updated_at = datetime.now(timezone.utc)
    await db.flush()
    await db.refresh(row)
    return _settings_to_response(row)
