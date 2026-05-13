"""AI Coach single-shot ``POST /chat`` + ``GET /chat/history``.

The token-streaming variant lives in ``chat_stream.py``. Both write
endpoints persist user+assistant rows to ``chat_messages`` (D11 E.3)
and apply AI-proposed preference diffs (D12).
"""

import logging
import uuid
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from pydantic import ValidationError
from sqlalchemy import desc, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.middleware.auth import get_active_user
from app.middleware.rate_limit import daily_ai_rate_limiter
from app.models import ChatMessage as ChatMessageModel, User
from app.schemas.ai import (
    ChatActionPayload,
    ChatHistoryResponse,
    ChatMessageRecord,
    ChatRequest,
    ChatResponse,
    ChatTaskContext,
    ChatTokensUsed,
)
from app.services.beta_codes import resolve_effective_tier

from .memory import (
    _apply_preference_diff,
    _load_user_preferences,
    _to_preference_record,
)

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post("/chat", response_model=ChatResponse)
async def chat(
    data: ChatRequest,
    request: Request,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    """Conversational coaching chat backed by Claude Haiku.

    Stateless from the backend's POV: the client owns the rolling history
    (last N=6 user/assistant pairs) and re-sends it on every turn via
    ``data.history``. The backend forwards it as a proper Anthropic
    ``messages`` array so the model has actual multi-turn memory.

    ``data.task_context`` carries a snapshot (title, description, due,
    priority, project) of the task the chat was opened from, when set —
    so the AI can ground its reply in the task content rather than the
    opaque integer ``task_context_id`` it cannot dereference.
    """
    from app.routers import ai as _ai_pkg

    _ai_pkg.chat_rate_limiter.check(request)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier)

    # Load the user's stored AI-memory preferences once up-front so we can
    # both forward them to Claude as context AND apply the diff after the
    # turn returns. Capped at 15; kept in updated_at-desc order so the AI's
    # eviction prompt biases toward the most recently touched entries.
    existing_prefs = await _load_user_preferences(db, current_user.id)
    prefs_for_prompt = [
        {"id": p.id, "text": p.preference_text} for p in existing_prefs
    ]

    try:
        from app.services.ai_productivity import generate_chat_response

        result = generate_chat_response(
            message=data.message,
            conversation_id=data.conversation_id,
            task_context_id=data.task_context_id,
            task_context=(
                data.task_context.model_dump(exclude_none=True)
                if data.task_context is not None
                else None
            ),
            history=[h.model_dump() for h in data.history],
            user_preferences=prefs_for_prompt,
        )
    except RuntimeError:
        raise HTTPException(status_code=503, detail="AI service temporarily unavailable")
    except ValueError:
        raise HTTPException(status_code=500, detail="AI returned an invalid response")

    # Validate AI-proposed actions against ChatActionPayload and drop
    # any that don't conform (Claude occasionally invents fields).
    # ``remember_preference`` / ``forget_preference`` are server-only
    # tool calls — split out for in-handler processing.
    validated_actions: list[ChatActionPayload] = []
    pending_remembers: list[str] = []
    pending_forgets: list[str] = []
    for raw in result.get("actions", []) or []:
        if not isinstance(raw, dict):
            continue
        action_type = raw.get("type")
        if action_type == "remember_preference":
            text = raw.get("preference_text")
            if isinstance(text, str) and text.strip():
                pending_remembers.append(text.strip())
            continue
        if action_type == "forget_preference":
            pid = raw.get("preference_id")
            if isinstance(pid, str) and pid.strip():
                pending_forgets.append(pid.strip())
            continue
        try:
            validated_actions.append(ChatActionPayload(**raw))
        except ValidationError:
            logger.info(
                "Dropping malformed chat action: user_id=%s type=%s",
                current_user.id,
                raw.get("type"),
            )
            continue

    tokens = result.get("tokens_used") or {}
    tokens_used = ChatTokensUsed(
        input=int(tokens.get("input", 0) or 0),
        output=int(tokens.get("output", 0) or 0),
    )

    # D11 E.3 — persist both turns to chat_messages. Server-authored writes
    # land here so cross-device GET /chat/history returns a consistent view.
    # Failures are logged but never bubble up to the user — the AI response
    # already returned successfully and the next history pull will reconcile.
    # D12 Gate (b): pre-allocate IDs so the response can carry them back to
    # the Android client, which uses them as local Room PKs. Defaulted to
    # None so a persistence failure surfaces a usable response without IDs.
    user_msg_id: str | None = None
    assistant_msg_id: str | None = None
    try:
        now = datetime.now(timezone.utc)
        user_msg_id = uuid.uuid4().hex
        assistant_msg_id = uuid.uuid4().hex
        user_row = ChatMessageModel(
            id=user_msg_id,
            user_id=current_user.id,
            conversation_id=data.conversation_id,
            role="user",
            content=data.message,
            task_context_snapshot=(
                data.task_context.model_dump(exclude_none=True)
                if data.task_context is not None
                else None
            ),
            created_at=now,
        )
        assistant_row = ChatMessageModel(
            id=assistant_msg_id,
            user_id=current_user.id,
            conversation_id=data.conversation_id,
            role="assistant",
            content=result["message"],
            actions=[a.model_dump() for a in validated_actions] or None,
            tokens_input=tokens_used.input,
            tokens_output=tokens_used.output,
            # +1µs so chronological retrieval orders user-then-assistant
            # even when wall-clock collapses to identical timestamps.
            created_at=now + timedelta(microseconds=1),
        )
        db.add(user_row)
        db.add(assistant_row)
        await db.commit()
    except Exception:
        logger.exception(
            "Failed to persist chat turn for user_id=%s conversation_id=%s",
            current_user.id,
            data.conversation_id,
        )
        await db.rollback()
        user_msg_id = None
        assistant_msg_id = None

    # Apply AI-proposed preference diff (forgets first so a same-turn
    # forget+remember can re-occupy a freed slot). Persistence failures
    # are logged but never bubble — the chat reply already succeeded.
    try:
        await _apply_preference_diff(
            db=db,
            user_id=current_user.id,
            forgets=pending_forgets,
            remembers=pending_remembers,
            source_message_id=assistant_msg_id,
        )
    except Exception:
        logger.exception(
            "Failed to apply preference diff for user_id=%s",
            current_user.id,
        )
        await db.rollback()

    updated_prefs = await _load_user_preferences(db, current_user.id)

    return ChatResponse(
        message=result["message"],
        actions=validated_actions,
        conversation_id=data.conversation_id,
        tokens_used=tokens_used,
        user_message_id=user_msg_id,
        assistant_message_id=assistant_msg_id,
        user_preferences=[_to_preference_record(p) for p in updated_prefs],
    )


@router.get("/chat/history", response_model=ChatHistoryResponse)
async def chat_history(
    conversation_id: str | None = Query(default=None, max_length=128),
    limit: int = Query(default=50, ge=1, le=200),
    before: str | None = Query(
        default=None,
        description="ISO-8601 cursor; returns messages strictly before this timestamp.",
    ),
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    """Return persisted chat turns for the current user.

    Per ``docs/audits/D11_E3_CHAT_PERSISTENCE_AUDIT.md`` (Item 3). Reads
    are filtered to the current user (multi-tenant isolation enforced
    at the WHERE clause; never trust the client to scope itself). When
    ``conversation_id`` is provided, only that day's thread is returned;
    otherwise messages from all conversations are returned in reverse
    chronological order, suitable for an archive-style listing.
    """
    stmt = select(ChatMessageModel).where(
        ChatMessageModel.user_id == current_user.id
    )
    if conversation_id is not None:
        stmt = stmt.where(ChatMessageModel.conversation_id == conversation_id)
    if before is not None:
        try:
            before_dt = datetime.fromisoformat(before)
        except ValueError:
            raise HTTPException(status_code=400, detail="Invalid 'before' cursor")
        stmt = stmt.where(ChatMessageModel.created_at < before_dt)

    # Pull (limit + 1) so we can detect a next page without a separate
    # COUNT. The extra row, if present, becomes the cursor for the next
    # call and is dropped from the response.
    stmt = stmt.order_by(desc(ChatMessageModel.created_at)).limit(limit + 1)
    rows = (await db.execute(stmt)).scalars().all()

    has_more = len(rows) > limit
    page = rows[:limit]

    # Return chronological order (oldest first) so the client appends
    # naturally to its existing list. ``next_before`` carries the oldest
    # message's created_at — passing it back walks one page earlier.
    page_chrono = list(reversed(page))
    next_before = page[-1].created_at.isoformat() if has_more and page else None

    records: list[ChatMessageRecord] = []
    for row in page_chrono:
        actions_payload = row.actions or []
        validated_actions: list[ChatActionPayload] = []
        for raw in actions_payload:
            if not isinstance(raw, dict):
                continue
            try:
                validated_actions.append(ChatActionPayload(**raw))
            except ValidationError:
                continue
        ctx = None
        if isinstance(row.task_context_snapshot, dict):
            try:
                ctx = ChatTaskContext(**row.task_context_snapshot)
            except ValidationError:
                ctx = None
        tokens = None
        if row.tokens_input is not None or row.tokens_output is not None:
            tokens = ChatTokensUsed(
                input=row.tokens_input or 0,
                output=row.tokens_output or 0,
            )
        records.append(
            ChatMessageRecord(
                id=row.id,
                conversation_id=row.conversation_id,
                role=row.role,
                content=row.content,
                actions=validated_actions,
                task_context_snapshot=ctx,
                tokens_used=tokens,
                created_at=row.created_at.isoformat(),
            )
        )

    return ChatHistoryResponse(messages=records, next_before=next_before)
