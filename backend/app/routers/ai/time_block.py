"""Time-block endpoint — AI-proposed daily schedule with calendar awareness."""

from datetime import date, datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.middleware.auth import get_active_user
from app.middleware.rate_limit import daily_ai_rate_limiter
from app.models import User
from app.schemas.ai import TimeBlockRequest, TimeBlockResponse
from app.services.beta_codes import resolve_effective_tier
from app.services.firestore_tasks import (
    filter_for_time_block,
    filter_for_time_block_range,
)

from ._common import _get_incomplete_tasks, _log_empty_short_circuit

router = APIRouter()


@router.post("/time-block", response_model=TimeBlockResponse)
async def time_block(
    data: TimeBlockRequest,
    request: Request,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    from app.routers import ai as _ai_pkg
    _ai_pkg.time_block_rate_limiter.check(request, is_admin=current_user.is_admin)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier, is_admin=current_user.is_admin)

    try:
        target_date = date.fromisoformat(data.date) if data.date else date.today()
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid date format; expected YYYY-MM-DD")

    horizon_days = max(1, min(data.horizon_days, 7))
    horizon_end = target_date + timedelta(days=horizon_days - 1)

    incomplete = await _get_incomplete_tasks(current_user)
    if horizon_days <= 1:
        tasks = [t.to_briefing_dict() for t in filter_for_time_block(incomplete, target_date)]
    else:
        tasks = [
            t.to_briefing_dict()
            for t in filter_for_time_block_range(incomplete, target_date, horizon_end)
        ]

    if not tasks:
        _log_empty_short_circuit(current_user, "time-block")
        from app.schemas.ai import TimeBlockStats

        return TimeBlockResponse(
            schedule=[],
            unscheduled_tasks=[],
            stats=TimeBlockStats(
                total_work_minutes=0,
                total_break_minutes=0,
                total_free_minutes=0,
                tasks_scheduled=0,
                tasks_deferred=0,
            ),
            proposed=True,
            horizon_days=horizon_days,
        )

    # Fetch real Google Calendar events for the target date so the AI
    # planner can schedule around them. The call is best-effort: if the
    # user hasn't connected Calendar, their settings disable sync, or
    # the backend can't reach Google right now, fall back to an empty
    # list and let the planner schedule as if the day were clear.
    import json as _json

    from app.models import CalendarSyncSettings as _CalSettings
    from app.services import calendar_service as _calendar_service

    calendar_events: list[dict] = []
    try:
        cal_settings_result = await db.execute(
            select(_CalSettings).where(_CalSettings.user_id == current_user.id)
        )
        cal_settings = cal_settings_result.scalar_one_or_none()
        if cal_settings is not None and cal_settings.enabled and cal_settings.show_events:
            try:
                display_ids = _json.loads(cal_settings.display_calendar_ids_json or "[]")
            except ValueError:
                display_ids = []
            calendar_ids = display_ids or [cal_settings.target_calendar_id]
            day_start_dt = datetime.combine(
                target_date, datetime.min.time(), tzinfo=timezone.utc
            )
            raw_events = await _calendar_service.list_events_in_window(
                db,
                current_user.id,
                calendar_ids,
                time_min=day_start_dt,
                time_max=day_start_dt + timedelta(days=horizon_days),
                limit=50 * horizon_days,
            )
            calendar_events = [
                {
                    "title": e["title"],
                    "start_millis": e["start_millis"],
                    "end_millis": e["end_millis"],
                    "all_day": e["all_day"],
                }
                for e in raw_events
            ]
    except Exception:  # noqa: BLE001
        calendar_events = []

    # Passthrough: client-supplied per-task signals and pre-existing blocks.
    # Validated at the pydantic boundary, so we can forward the dict shape
    # directly to the AI prompt without re-validation.
    task_signals_payload = [s.model_dump() for s in data.task_signals]
    existing_blocks_payload = [b.model_dump() for b in data.existing_blocks]

    try:
        from app.services.ai_productivity import generate_time_blocks as ai_time_block

        result = ai_time_block(
            target_date=target_date,
            day_start=data.day_start,
            day_end=data.day_end,
            block_size_minutes=data.block_size_minutes,
            include_breaks=data.include_breaks,
            break_frequency_minutes=data.break_frequency_minutes,
            break_duration_minutes=data.break_duration_minutes,
            tasks=tasks,
            calendar_events=calendar_events,
            tier=tier,
            horizon_days=horizon_days,
            horizon_end=horizon_end,
            task_signals=task_signals_payload,
            existing_blocks=existing_blocks_payload,
        )
    except RuntimeError:
        raise HTTPException(status_code=503, detail="AI service temporarily unavailable")
    except ValueError:
        raise HTTPException(status_code=500, detail="AI returned an invalid response")

    # Force the "proposed" contract: the Android client must treat this as a
    # preview and never auto-commit. The service layer doesn't set this —
    # the router does, so tests that stub the service still get the flag.
    result.setdefault("proposed", True)
    result["horizon_days"] = horizon_days

    return TimeBlockResponse(**result)
