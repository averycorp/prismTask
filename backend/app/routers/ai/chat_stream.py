"""Token-streaming AI Coach chat endpoint (``POST /chat/stream``).

Split out of ``chat.py`` so each file stays under the 300-LOC cap. The
single-shot ``/chat`` and ``/chat/history`` endpoints live next door in
``chat.py``; both endpoints share the persistence + preference-diff
contract so cross-device ``GET /chat/history`` is consistent regardless
of which endpoint produced the turn.
"""

import json
import logging
import uuid
from datetime import datetime, timedelta, timezone

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse
from pydantic import ValidationError
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.middleware.auth import get_active_user
from app.middleware.rate_limit import daily_ai_rate_limiter
from app.models import ChatMessage as ChatMessageModel, User
from app.schemas.ai import ChatActionPayload, ChatRequest
from app.services.beta_codes import resolve_effective_tier
from app.services.crisis_keywords import (
    contains_crisis_signal,
    crisis_safety_response,
)

from .memory import (
    _apply_preference_diff,
    _load_user_preferences,
    _to_preference_record,
)

logger = logging.getLogger(__name__)

router = APIRouter()


def _format_sse_event(event: dict) -> bytes:
    """Format a service-layer event dict into an SSE frame.

    Strips the ``type`` discriminator into the SSE ``event:`` line and
    serializes the rest of the dict as JSON in the ``data:`` line.
    """
    event_type = event.get("type", "message")
    payload = {k: v for k, v in event.items() if k != "type"}
    return f"event: {event_type}\ndata: {json.dumps(payload, default=str)}\n\n".encode("utf-8")


@router.post("/chat/stream")
async def chat_stream(
    data: ChatRequest,
    request: Request,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    """Token-by-token streaming variant of ``/chat`` (F7 D.1).

    Same request schema as the single-shot endpoint. Returns
    ``text/event-stream`` with three event types:

    - ``token``: ``{"text": "<delta>"}`` — incremental ``message`` field
      content as the upstream Claude response accumulates. The route
      filters out the surrounding JSON envelope so the client only sees
      the user-visible reply text.
    - ``done``: ``{"message": "<final>", "actions": [<validated>],
      "tokens_used": {"input": int, "output": int}}`` — emitted once the
      upstream stream completes and the JSON parses + actions validate.
    - ``error``: ``{"message": "<...>", "code": "<short>"}`` — emitted
      on any upstream or parse failure. Stream then closes.

    Auth + AI gate + rate limiting fire BEFORE the SSE response opens,
    so a user over budget gets HTTP 429 on the initial POST without
    seeing a half-opened stream.
    """
    from app.routers import ai as _ai_pkg

    _ai_pkg.chat_rate_limiter.check(request)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier)

    # D12 Gate (a): pre-allocate the IDs we'll use for the persisted rows
    # so we can surface them in the SSE done payload without a second
    # round-trip. Mirrors the single-shot /chat handler's persistence
    # shape — both endpoints now write user+assistant rows to
    # chat_messages so cross-device GET /chat/history is consistent
    # regardless of which endpoint produced the turn.
    user_msg_id = uuid.uuid4().hex
    assistant_msg_id = uuid.uuid4().hex

    # Snapshot AI-memory preferences before the turn so the streaming
    # service has the same context as the single-shot endpoint. The diff
    # is applied after the upstream stream finishes and the updated list
    # is surfaced in the SSE done payload (mirrors ChatResponse.user_preferences).
    existing_prefs = await _load_user_preferences(db, current_user.id)
    prefs_for_prompt = [
        {"id": p.id, "text": p.preference_text} for p in existing_prefs
    ]

    # G2 — defense-in-depth crisis pre-filter. Mirrors the non-streaming
    # /chat handler. We synthesize a single 'done' event with the static
    # safety reply rather than streaming tokens, since the static text is
    # already complete and there is no upstream Claude call to await.
    crisis_short_circuit = contains_crisis_signal(data.message)

    async def event_generator():
        persisted = False
        try:
            if crisis_short_circuit:
                safety = crisis_safety_response()

                def _crisis_stream():
                    yield {
                        "type": "done",
                        "message": safety["message"],
                        "actions": [],
                        "tokens_used": safety["tokens_used"],
                    }

                stream_iter = _crisis_stream()
            else:
                from app.services.ai_productivity import generate_chat_response_stream

                stream_iter = generate_chat_response_stream(
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
            for event in stream_iter:
                if event.get("type") == "done":
                    # Validate each AI-proposed action against
                    # ChatActionPayload and drop any that don't conform.
                    # Keeps the streaming path's action grammar identical
                    # to the single-shot endpoint.
                    # ``remember_preference`` / ``forget_preference`` are
                    # server-only — split out for in-handler processing,
                    # same as the non-streaming /chat endpoint.
                    validated: list[dict] = []
                    pending_remembers: list[str] = []
                    pending_forgets: list[str] = []
                    for raw in event.get("actions", []) or []:
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
                            validated.append(
                                ChatActionPayload(**raw).model_dump(exclude_none=True)
                            )
                        except ValidationError:
                            logger.info(
                                "Dropping malformed chat action: user_id=%s type=%s",
                                current_user.id,
                                raw.get("type"),
                            )
                    event["actions"] = validated
                    event["conversation_id"] = data.conversation_id

                    # D12 Gate (a): persist BOTH turns to chat_messages on
                    # done — mirror the single-shot handler. Failures are
                    # logged but do not bubble up to the user; the next
                    # GET /chat/history reconciles. `persisted` guards
                    # against a theoretical duplicate done event from the
                    # service layer.
                    if not persisted:
                        try:
                            now = datetime.now(timezone.utc)
                            tokens = event.get("tokens_used") or {}
                            db.add(ChatMessageModel(
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
                            ))
                            db.add(ChatMessageModel(
                                id=assistant_msg_id,
                                user_id=current_user.id,
                                conversation_id=data.conversation_id,
                                role="assistant",
                                content=event.get("message", ""),
                                actions=validated or None,
                                tokens_input=int(tokens.get("input", 0) or 0),
                                tokens_output=int(tokens.get("output", 0) or 0),
                                # +1µs so chronological retrieval orders
                                # user-then-assistant even when wall-clock
                                # collapses to identical timestamps.
                                created_at=now + timedelta(microseconds=1),
                            ))
                            await db.commit()
                            persisted = True
                        except Exception:
                            logger.exception(
                                "Failed to persist streaming chat turn for"
                                " user_id=%s conversation_id=%s",
                                current_user.id,
                                data.conversation_id,
                            )
                            await db.rollback()

                    # D12 Gate (b): surface the persisted IDs in the done
                    # payload so the Android client can use them for the
                    # local Room write — keeping client and server PKs in
                    # lockstep so pullHistory() upserts are idempotent.
                    event["user_message_id"] = user_msg_id
                    event["assistant_message_id"] = assistant_msg_id

                    # Apply preference diff in lockstep with the
                    # non-streaming handler. Failures don't bubble; the
                    # done payload always carries the up-to-date list.
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
                            "Failed to apply preference diff (stream) for"
                            " user_id=%s",
                            current_user.id,
                        )
                        await db.rollback()
                    updated_prefs = await _load_user_preferences(
                        db, current_user.id
                    )
                    event["user_preferences"] = [
                        _to_preference_record(p).model_dump()
                        for p in updated_prefs
                    ]
                yield _format_sse_event(event)
        except RuntimeError:
            yield _format_sse_event({
                "type": "error",
                "message": "AI service temporarily unavailable",
                "code": "unavailable",
            })

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            # Discourage proxy buffering so SSE chunks reach the client
            # promptly. Railway's edge already passes text/event-stream
            # without buffering, but X-Accel-Buffering: no is the
            # canonical hint for nginx-flavored intermediaries.
            "X-Accel-Buffering": "no",
            "Cache-Control": "no-cache",
        },
    )
