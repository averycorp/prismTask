"""AI memory — user preferences the AI auto-extracts from chat.

The AI Coach emits ``remember_preference`` / ``forget_preference`` tool
calls during chat and the chat handler persists them via
``_apply_preference_diff``. The endpoints below expose the same data
to the Android Settings UI so the user can view, edit, or delete what
the AI has learned. The 15-slot cap is enforced on every write path.

The helpers ``_load_user_preferences`` and ``_apply_preference_diff``
also live here so the chat endpoints can import them without pulling
in the full memory router.
"""

import uuid

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import desc, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.middleware.auth import get_active_user
from app.models import User, UserAiPreference as UserAiPreferenceModel
from app.schemas.ai import (
    USER_AI_PREFERENCE_CAP,
    UserAiPreferenceCreateRequest,
    UserAiPreferenceListResponse,
    UserAiPreferenceRecord,
    UserAiPreferenceUpdateRequest,
)

router = APIRouter()


# ---------------------------------------------------------------------------
# Internal helpers (re-used by the chat endpoints)
# ---------------------------------------------------------------------------


async def _load_user_preferences(
    db: AsyncSession,
    user_id: int,
) -> list[UserAiPreferenceModel]:
    """Return the user's preferences ordered by ``updated_at`` desc.

    The desc ordering biases the AI's eviction heuristic toward the most
    recently touched entries — older / stale preferences naturally sink
    to the bottom of the list and become eviction candidates first.
    """
    stmt = (
        select(UserAiPreferenceModel)
        .where(UserAiPreferenceModel.user_id == user_id)
        .order_by(desc(UserAiPreferenceModel.updated_at))
    )
    return list((await db.execute(stmt)).scalars().all())


def _to_preference_record(row: UserAiPreferenceModel) -> UserAiPreferenceRecord:
    return UserAiPreferenceRecord(
        id=row.id,
        preference_text=row.preference_text,
        source_message_id=row.source_message_id,
        created_at=row.created_at.isoformat(),
        updated_at=row.updated_at.isoformat(),
    )


async def _apply_preference_diff(
    db: AsyncSession,
    user_id: int,
    forgets: list[str],
    remembers: list[str],
    source_message_id: str | None,
) -> None:
    """Apply ``forget_preference`` + ``remember_preference`` tool calls.

    Order: forgets fire first so a same-turn forget+remember can re-occupy
    a freed slot. Each remember is deduped against existing rows by
    case-insensitive text match so the AI doesn't store the same idea
    twice. When the cap is hit and the AI didn't emit its own evict,
    fall back to dropping the least-recently-updated row.
    """
    if not forgets and not remembers:
        return

    if forgets:
        from sqlalchemy import delete

        await db.execute(
            delete(UserAiPreferenceModel)
            .where(UserAiPreferenceModel.user_id == user_id)
            .where(UserAiPreferenceModel.id.in_(forgets))
        )

    for text in remembers:
        text = text.strip()
        if not text:
            continue
        # Re-read remaining prefs after each insert so we know the
        # current count and can dedup against fresh state.
        existing = await _load_user_preferences(db, user_id)
        lowered = text.lower()
        if any(p.preference_text.strip().lower() == lowered for p in existing):
            continue
        # Cap enforcement (defense in depth — the AI is also prompted
        # to evict before exceeding). Drop the oldest by updated_at.
        if len(existing) >= USER_AI_PREFERENCE_CAP:
            oldest = existing[-1]
            await db.delete(oldest)
            await db.flush()
        row = UserAiPreferenceModel(
            id=uuid.uuid4().hex,
            user_id=user_id,
            preference_text=text[:500],
            source_message_id=source_message_id,
        )
        db.add(row)
        await db.flush()

    await db.commit()


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------


@router.get("/memory", response_model=UserAiPreferenceListResponse)
async def list_ai_memory(
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    """List the user's stored AI preferences.

    Returned in ``updated_at`` desc order so the Settings UI naturally
    shows the most recently touched preferences first.
    """
    rows = await _load_user_preferences(db, current_user.id)
    return UserAiPreferenceListResponse(
        preferences=[_to_preference_record(r) for r in rows],
        cap=USER_AI_PREFERENCE_CAP,
    )


@router.post("/memory", response_model=UserAiPreferenceRecord, status_code=201)
async def create_ai_memory(
    data: UserAiPreferenceCreateRequest,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    """Manually add a preference from the Settings UI.

    Enforces the 15-slot cap (returns 409 when full so the UI can show a
    clear error instead of silently dropping). Dedupes against existing
    entries by case-insensitive text match.
    """
    text = data.preference_text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="preference_text is empty")

    existing = await _load_user_preferences(db, current_user.id)
    if any(p.preference_text.strip().lower() == text.lower() for p in existing):
        # Already stored — surface the existing row rather than 409 so
        # the client can settle on the canonical row id.
        for p in existing:
            if p.preference_text.strip().lower() == text.lower():
                return _to_preference_record(p)
    if len(existing) >= USER_AI_PREFERENCE_CAP:
        raise HTTPException(
            status_code=409,
            detail=f"Memory is full (max {USER_AI_PREFERENCE_CAP})",
        )
    row = UserAiPreferenceModel(
        id=uuid.uuid4().hex,
        user_id=current_user.id,
        preference_text=text[:500],
        source_message_id=None,
    )
    db.add(row)
    await db.commit()
    await db.refresh(row)
    return _to_preference_record(row)


@router.patch("/memory/{preference_id}", response_model=UserAiPreferenceRecord)
async def update_ai_memory(
    preference_id: str,
    data: UserAiPreferenceUpdateRequest,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    """Edit the text of an existing preference.

    404 if the id doesn't belong to the current user — multi-tenant
    isolation enforced at the WHERE clause; never trust the path.
    """
    text = data.preference_text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="preference_text is empty")
    stmt = select(UserAiPreferenceModel).where(
        UserAiPreferenceModel.id == preference_id,
        UserAiPreferenceModel.user_id == current_user.id,
    )
    row = (await db.execute(stmt)).scalar_one_or_none()
    if row is None:
        raise HTTPException(status_code=404, detail="Preference not found")
    row.preference_text = text[:500]
    await db.commit()
    await db.refresh(row)
    return _to_preference_record(row)


@router.delete("/memory/{preference_id}", status_code=204)
async def delete_ai_memory(
    preference_id: str,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    """Remove a preference. Idempotent — 204 even if it didn't exist."""
    from sqlalchemy import delete

    await db.execute(
        delete(UserAiPreferenceModel)
        .where(UserAiPreferenceModel.id == preference_id)
        .where(UserAiPreferenceModel.user_id == current_user.id)
    )
    await db.commit()
