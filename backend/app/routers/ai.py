import json
import logging
import uuid
from datetime import date, datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Query, Request, status
from fastapi.responses import StreamingResponse
from sqlalchemy import desc, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.middleware.ai_gate import require_ai_features_enabled
from app.middleware.auth import get_active_user
from app.middleware.rate_limit import RateLimiter, daily_ai_rate_limiter
from app.models import ChatMessage as ChatMessageModel, Habit, User
from app.schemas.ai import (
    AutomationCompleteRequest,
    AutomationCompleteResponse,
    AutomationSummarizeRequest,
    AutomationSummarizeResponse,
    BatchParseRequest,
    BatchParseResponse,
    ChatActionPayload,
    ChatHistoryResponse,
    ChatMessageRecord,
    ChatRequest,
    ChatResponse,
    ChatTaskContext,
    ChatTokensUsed,
    DailyBriefingRequest,
    DailyBriefingResponse,
    CognitiveLoadClassifyTextRequest,
    CognitiveLoadClassifyTextResponse,
    EisenhowerClassifyTextRequest,
    EisenhowerClassifyTextResponse,
    LifeCategoryClassifyTextRequest,
    LifeCategoryClassifyTextResponse,
    EisenhowerRequest,
    EisenhowerResponse,
    EisenhowerSummary,
    ExtractFromTextRequest,
    ExtractFromTextResponse,
    ExtractedTaskCandidate,
    PomodoroCoachingRequest,
    PomodoroCoachingResponse,
    PomodoroRequest,
    PomodoroResponse,
    TimeBlockRequest,
    TimeBlockResponse,
    WeeklyPlanRequest,
    WeeklyPlanResponse,
    WeeklyReviewRequest,
    WeeklyReviewResponse,
)
from pydantic import ValidationError
from app.services.beta_codes import resolve_effective_tier
from app.services.firestore_tasks import (
    TaskDTO,
    fetch_incomplete_tasks,
    fetch_recently_completed_tasks,
    fetch_tasks_by_ids,
    filter_due_on,
    filter_for_time_block,
    filter_for_time_block_range,
    filter_overdue_before,
    filter_planned_on,
    filter_recurring,
)

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/ai",
    tags=["ai"],
    # PII egress audit (2026-04-26): every endpoint on this router can
    # transit user data to Anthropic. Reject requests where the client
    # has signalled the user disabled AI features in PrismTask Settings.
    dependencies=[Depends(require_ai_features_enabled)],
)

# Rate limiter: max 1 call per 5 minutes (300 seconds) per IP
ai_rate_limiter = RateLimiter(max_requests=1, window_seconds=300)

# Text-classify runs on every client-side task creation, so it needs a much
# higher ceiling than the batch endpoint. 20/min per IP comfortably covers
# normal burst creation (voice capture, paste-to-extract dumps) without
# handing out free Claude calls.
eisenhower_classify_text_rate_limiter = RateLimiter(max_requests=20, window_seconds=60)

# Cognitive-load text-classify mirrors Eisenhower's per-task creation flow,
# so the same 20/min ceiling applies — burst-tolerant for normal task entry.
cognitive_load_classify_text_rate_limiter = RateLimiter(max_requests=20, window_seconds=60)

# Life-category text-classify is invoked from the OrganizeTab "Auto" button
# (manual user tap, not background fire-and-forget), so 20/min comfortably
# absorbs a user iterating on a task title and re-pressing Auto.
life_category_classify_text_rate_limiter = RateLimiter(max_requests=20, window_seconds=60)

# Rate limiters for new AI endpoints
briefing_rate_limiter = RateLimiter(max_requests=1, window_seconds=3600)  # 1 per hour
weekly_plan_rate_limiter = RateLimiter(max_requests=1, window_seconds=1800)  # 1 per 30 min
# v1.4.40: expanded horizon support (today / +1 / +6 days). Bumped from
# 1-per-15min to 10/hour because horizon=7 requests are higher-value and a
# single parse failure shouldn't lock a Pro user out for 15 minutes.
time_block_rate_limiter = RateLimiter(max_requests=10, window_seconds=3600)
# v1.4.0 V6: weekly review — 1 per hour is plenty, the client caches history.
weekly_review_rate_limiter = RateLimiter(max_requests=1, window_seconds=3600)
# v1.4.0 V9: paste-to-extract — 10 per minute to cover rapid iteration
# (user fixing titles and re-extracting).
extract_rate_limiter = RateLimiter(max_requests=10, window_seconds=60)
# A2 Pomodoro+ coaching — 3 surfaces fire per session, so the window has to
# accommodate a normal 4-session flow (pre + 3 breaks + recap = ~8 calls).
pomodoro_coaching_rate_limiter = RateLimiter(max_requests=15, window_seconds=600)
# A2 NLP batch ops — 10/hour mirrors time-block. Batch parses are deliberate
# user actions (compose command, hit submit, review preview, approve),
# not background polling, so a one-call-every-six-minutes cap is plenty.
batch_parse_rate_limiter = RateLimiter(max_requests=10, window_seconds=3600)
# Conversational coach — chat is interactive and bursty (a user types
# multiple messages in a row), so the per-IP window is sized for a normal
# back-and-forth: ~30 turns/min covers typing-fast users and the
# server-side daily cap (DailyAIRateLimiter PRO=100) is the real budget.
chat_rate_limiter = RateLimiter(max_requests=30, window_seconds=60)
# A7 automation actions — fired from the on-device automation engine.
# Rules can fan out to many actions per trigger; cap at 30/min per IP to
# absorb a reasonable burst (e.g. a "morning routine" rule that triggers
# multiple ai.complete + ai.summarize handlers) while still keeping the
# server-side daily AI budget the real ceiling.
automation_action_rate_limiter = RateLimiter(max_requests=30, window_seconds=60)


def _require_firebase_uid(user: User) -> str:
    """Return the user's Firebase UID or raise 401.

    AI endpoints read tasks from Firestore under ``users/{uid}/tasks``. If the
    JWT-linked User row has no ``firebase_uid``, the identity chain is broken —
    reject rather than silently query a nonexistent collection.
    """
    uid = getattr(user, "firebase_uid", None)
    if not uid:
        logger.warning(
            "AI endpoint rejected: user id=%s has no firebase_uid", user.id
        )
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="User is not linked to a Firebase account",
        )
    return uid


async def _get_incomplete_tasks(
    user: User, task_ids: list[str] | None = None
) -> list[TaskDTO]:
    uid = _require_firebase_uid(user)
    if task_ids:
        tasks = await fetch_tasks_by_ids(uid, task_ids)
        # Match the old Postgres filter: only incomplete tasks. fetch_tasks_by_ids
        # returns any doc that exists; drop completed ones here.
        return [t for t in tasks if t.completed_at is None]
    return await fetch_incomplete_tasks(uid)


def _log_empty_short_circuit(user: User, endpoint: str) -> None:
    logger.info(
        "AI short-circuit: no_incomplete_tasks user_id=%s endpoint=%s",
        user.id,
        endpoint,
    )


@router.post("/eisenhower", response_model=EisenhowerResponse)
async def categorize_eisenhower(
    data: EisenhowerRequest,
    request: Request,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    ai_rate_limiter.check(request)
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
    eisenhower_classify_text_rate_limiter.check(request)
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
    cognitive_load_classify_text_rate_limiter.check(request)
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
    life_category_classify_text_rate_limiter.check(request)
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


@router.post("/pomodoro-plan", response_model=PomodoroResponse)
async def plan_pomodoro(
    data: PomodoroRequest,
    request: Request,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    ai_rate_limiter.check(request)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier)

    tasks = await _get_incomplete_tasks(current_user)
    if not tasks:
        _log_empty_short_circuit(current_user, "pomodoro")
        return PomodoroResponse(
            sessions=[],
            total_sessions=0,
            total_work_minutes=0,
            total_break_minutes=0,
            skipped_tasks=[],
        )

    task_dicts = [t.to_ai_dict() for t in tasks]

    try:
        from app.services.ai_productivity import plan_pomodoro as ai_plan

        plan = ai_plan(
            tasks=task_dicts,
            available_minutes=data.available_minutes,
            session_length=data.session_length,
            break_length=data.break_length,
            long_break_length=data.long_break_length,
            focus_preference=data.focus_preference,
            today=date.today(),
            tier=tier,
        )
    except RuntimeError:
        raise HTTPException(status_code=503, detail="AI service temporarily unavailable")
    except ValueError:
        raise HTTPException(status_code=500, detail="AI returned an invalid response")

    return PomodoroResponse(**plan)


@router.post("/pomodoro-coaching", response_model=PomodoroCoachingResponse)
async def pomodoro_coaching(
    data: PomodoroCoachingRequest,
    request: Request,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    """A2 Pomodoro+ coaching — pre-session, break-activity, or session-recap.

    Returns a single coaching sentence generated by Haiku. Trigger is dispatched
    inside ``generate_pomodoro_coaching``; only the fields relevant to the
    trigger are consulted.
    """
    pomodoro_coaching_rate_limiter.check(request)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier)

    try:
        from app.services.ai_productivity import generate_pomodoro_coaching as ai_coaching

        message = ai_coaching(
            trigger=data.trigger,
            upcoming_tasks=(
                [t.model_dump() for t in data.upcoming_tasks] if data.upcoming_tasks else None
            ),
            session_length_minutes=data.session_length_minutes,
            elapsed_minutes=data.elapsed_minutes,
            break_type=data.break_type,
            recent_suggestions=data.recent_suggestions,
            completed_tasks=(
                [t.model_dump() for t in data.completed_tasks] if data.completed_tasks else None
            ),
            started_tasks=(
                [t.model_dump() for t in data.started_tasks] if data.started_tasks else None
            ),
            session_duration_minutes=data.session_duration_minutes,
            tier=tier,
        )
    except RuntimeError:
        raise HTTPException(status_code=503, detail="AI service temporarily unavailable")
    except ValueError:
        raise HTTPException(status_code=500, detail="AI returned an invalid response")

    return PomodoroCoachingResponse(message=message)


@router.post("/daily-briefing", response_model=DailyBriefingResponse)
async def daily_briefing(
    data: DailyBriefingRequest,
    request: Request,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    briefing_rate_limiter.check(request)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier)

    try:
        target_date = date.fromisoformat(data.date) if data.date else date.today()
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid date format; expected YYYY-MM-DD")

    uid = _require_firebase_uid(current_user)

    # One Firestore read, then partition in Python. Keeps query count bounded
    # even for users with many incomplete tasks.
    incomplete_tasks = await fetch_incomplete_tasks(uid)
    overdue_tasks = [t.to_briefing_dict() for t in filter_overdue_before(incomplete_tasks, target_date)]
    today_tasks = [t.to_briefing_dict() for t in filter_due_on(incomplete_tasks, target_date)]
    planned_tasks = [t.to_briefing_dict() for t in filter_planned_on(incomplete_tasks, target_date)]

    # Habits still live in Postgres — unchanged by this migration.
    habits_query = select(Habit).where(
        Habit.user_id == current_user.id,
        Habit.is_active.is_(True),
    )
    habits_result = await db.execute(habits_query)
    habits = [{"name": h.name, "frequency": h.frequency.value} for h in habits_result.scalars().all()]

    # Recently completed (last 24h) comes from Firestore too now.
    yesterday = datetime.now(timezone.utc) - timedelta(hours=24)
    completed_dtos = await fetch_recently_completed_tasks(uid, yesterday)
    completed_tasks = [{"task_id": t.task_id, "title": t.title} for t in completed_dtos]

    all_tasks = overdue_tasks + today_tasks + planned_tasks
    if not all_tasks and not habits:
        _log_empty_short_circuit(current_user, "daily-briefing")
        return DailyBriefingResponse(
            greeting="Good morning! You have a clear day ahead.",
            top_priorities=[],
            heads_up=[],
            suggested_order=[],
            habit_reminders=[],
            day_type="light",
        )

    try:
        from app.services.ai_productivity import generate_daily_briefing as ai_briefing

        result = ai_briefing(
            today=target_date,
            overdue_tasks=overdue_tasks,
            today_tasks=today_tasks,
            planned_tasks=planned_tasks,
            habits=habits,
            completed_tasks=completed_tasks,
            tier=tier,
        )
    except RuntimeError:
        raise HTTPException(status_code=503, detail="AI service temporarily unavailable")
    except ValueError:
        raise HTTPException(status_code=500, detail="AI returned an invalid response")

    return DailyBriefingResponse(**result)


@router.post("/weekly-plan", response_model=WeeklyPlanResponse)
async def weekly_plan(
    data: WeeklyPlanRequest,
    request: Request,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    weekly_plan_rate_limiter.check(request)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier)

    if data.week_start:
        try:
            week_start = date.fromisoformat(data.week_start)
        except ValueError:
            raise HTTPException(status_code=400, detail="Invalid week_start format; expected YYYY-MM-DD")
    else:
        # Default to next Monday
        today = date.today()
        days_until_monday = (7 - today.weekday()) % 7
        if days_until_monday == 0:
            days_until_monday = 7
        week_start = today + timedelta(days=days_until_monday)

    week_end = week_start + timedelta(days=6)

    incomplete = await _get_incomplete_tasks(current_user)
    all_tasks = [t.to_briefing_dict() for t in incomplete]
    recurring_tasks = [t.to_briefing_dict() for t in filter_recurring(incomplete)]

    if not all_tasks:
        _log_empty_short_circuit(current_user, "weekly-plan")
        return WeeklyPlanResponse(
            plan={},
            unscheduled=[],
            week_summary="No tasks to plan for this week.",
            tips=[],
        )

    try:
        from app.services.ai_productivity import generate_weekly_plan as ai_plan

        result = ai_plan(
            week_start=week_start,
            week_end=week_end,
            work_days=data.preferences.work_days,
            focus_hours_per_day=data.preferences.focus_hours_per_day,
            prefer_front_loading=data.preferences.prefer_front_loading,
            tasks=all_tasks,
            recurring_tasks=recurring_tasks,
            tier=tier,
        )
    except RuntimeError:
        raise HTTPException(status_code=503, detail="AI service temporarily unavailable")
    except ValueError:
        raise HTTPException(status_code=500, detail="AI returned an invalid response")

    return WeeklyPlanResponse(**result)


@router.post("/time-block", response_model=TimeBlockResponse)
async def time_block(
    data: TimeBlockRequest,
    request: Request,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    time_block_rate_limiter.check(request)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier)

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


# ---------------------------------------------------------------------------
# v1.4.0 V6 — AI weekly review
# ---------------------------------------------------------------------------


_WEEKLY_REVIEW_OPEN_TASK_CAP = 20


def _rank_open_tasks_for_review(tasks: list[TaskDTO]) -> list[TaskDTO]:
    """Rank + cap open tasks for the weekly-review prompt.

    Ordering: priority DESC, then due_date ASC with nulls last, then
    sort_order ASC as a stable tiebreaker. Capped at 20 items to keep
    Sonnet token usage bounded. If there are <=20 open tasks, we skip the
    ranking and return them all (still applying the stable sort so the
    prompt is deterministic).
    """
    # date.max keeps null due dates at the end of the ASC sort.
    far_future = date.max

    def sort_key(t: TaskDTO):
        return (
            -int(t.priority or 0),
            t.due_date_obj or far_future,
            int(t.sort_order or 0),
        )

    ranked = sorted(tasks, key=sort_key)
    return ranked[:_WEEKLY_REVIEW_OPEN_TASK_CAP]


@router.post("/weekly-review", response_model=WeeklyReviewResponse)
async def weekly_review(
    data: WeeklyReviewRequest,
    request: Request,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    """
    Generate an ADHD-friendly weekly review narrative using a hybrid
    input pattern:
      * The client sends per-task summaries for completed and slipped tasks
        plus optional opaque habit/pomodoro aggregates and free-form notes.
      * The backend enriches with the user's current open tasks from
        Firestore so the "going forward" section of the review is grounded
        in live data.

    Schema v2 — breaking change from the aggregate-counts v1 schema. Old
    clients posting the v1 body shape will get 422 until their prompts
    land. See WeeklyReviewRequest in schemas/ai.py for the v2 contract.
    """
    weekly_review_rate_limiter.check(request)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier)

    uid = _require_firebase_uid(current_user)
    open_dtos = await fetch_incomplete_tasks(uid)
    top_open = _rank_open_tasks_for_review(open_dtos)

    logger.info(
        "AI weekly_review: user_id=%s endpoint=weekly_review "
        "completed=%d slipped=%d open_total=%d open_included=%d",
        current_user.id,
        len(data.completed_tasks),
        len(data.slipped_tasks),
        len(open_dtos),
        len(top_open),
    )

    completed_dicts = [t.model_dump() for t in data.completed_tasks]
    slipped_dicts = [t.model_dump() for t in data.slipped_tasks]
    open_dicts = [t.to_briefing_dict() for t in top_open]

    try:
        from app.services.ai_productivity import generate_weekly_review as ai_review
        result = ai_review(
            week_start=data.week_start.isoformat(),
            week_end=data.week_end.isoformat(),
            completed_tasks=completed_dicts,
            slipped_tasks=slipped_dicts,
            open_tasks=open_dicts,
            habit_summary=data.habit_summary,
            pomodoro_summary=data.pomodoro_summary,
            notes=data.notes,
            tier=tier,
        )
    except RuntimeError:
        raise HTTPException(status_code=503, detail="AI service temporarily unavailable")
    except ValueError:
        raise HTTPException(status_code=500, detail="AI returned an invalid response")

    return WeeklyReviewResponse(
        week_start=data.week_start,
        week_end=data.week_end,
        wins=result.get("wins", []),
        slips=result.get("slips", []),
        patterns=result.get("patterns", []),
        next_week_focus=result.get("next_week_focus", []),
        narrative=result.get("narrative", ""),
    )


# ---------------------------------------------------------------------------
# v1.4.0 V9 — paste-to-tasks extraction
# ---------------------------------------------------------------------------


@router.post("/tasks/extract-from-text", response_model=ExtractFromTextResponse)
async def extract_from_text(
    data: ExtractFromTextRequest,
    request: Request,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    """
    Extract structured task candidates from pasted conversation text via
    Claude Haiku. The Android client (ConversationTaskExtractor) falls
    back to regex-based extraction when this endpoint is unavailable.

    Input is capped at 10,000 chars by the schema. The Android paste
    screen enforces the same cap client-side.
    """
    extract_rate_limiter.check(request)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier)

    try:
        from app.services.ai_productivity import extract_tasks_from_text as ai_extract
        raw_tasks = ai_extract(data.text, data.source, tier=tier)
    except RuntimeError:
        raise HTTPException(status_code=503, detail="AI service temporarily unavailable")
    except ValueError:
        raise HTTPException(status_code=500, detail="AI returned an invalid response")

    candidates = []
    for t in raw_tasks:
        candidates.append(
            ExtractedTaskCandidate(
                title=str(t.get("title", "")).strip(),
                suggested_due_date=t.get("suggested_due_date"),
                suggested_priority=int(t.get("suggested_priority") or 0),
                suggested_project=t.get("suggested_project"),
                confidence=float(t.get("confidence") or 0.5),
            )
        )
    # Drop anything with an empty title — defensive.
    candidates = [c for c in candidates if c.title]
    return ExtractFromTextResponse(tasks=candidates)


# ---------------------------------------------------------------------------
# A2 — NLP batch schedule operations (pulled from Phase H)
# ---------------------------------------------------------------------------


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
    batch_parse_rate_limiter.check(request)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier)

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


# ---------------------------------------------------------------------------
# Conversational AI Coach (chat)
# ---------------------------------------------------------------------------


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
    chat_rate_limiter.check(request)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier)

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
        )
    except RuntimeError:
        raise HTTPException(status_code=503, detail="AI service temporarily unavailable")
    except ValueError:
        raise HTTPException(status_code=500, detail="AI returned an invalid response")

    # Validate each AI-proposed action against ChatActionPayload and drop
    # any that don't conform. The model occasionally invents new action
    # `type` values or omits required fields — silently filtering keeps
    # the response usable rather than 500-ing the whole turn.
    validated_actions: list[ChatActionPayload] = []
    for raw in result.get("actions", []) or []:
        if not isinstance(raw, dict):
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

    return ChatResponse(
        message=result["message"],
        actions=validated_actions,
        conversation_id=data.conversation_id,
        tokens_used=tokens_used,
        user_message_id=user_msg_id,
        assistant_message_id=assistant_msg_id,
    )


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
    chat_rate_limiter.check(request)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier)

    # D12 Gate (a): pre-allocate the IDs we'll use for the persisted rows
    # so we can surface them in the SSE done payload without a second
    # round-trip. Mirrors the single-shot /chat handler's persistence
    # shape at ai.py:974-1015 — both endpoints now write user+assistant
    # rows to chat_messages so cross-device GET /chat/history is
    # consistent regardless of which endpoint produced the turn.
    user_msg_id = uuid.uuid4().hex
    assistant_msg_id = uuid.uuid4().hex

    async def event_generator():
        persisted = False
        try:
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
            )
            for event in stream_iter:
                if event.get("type") == "done":
                    # Validate each AI-proposed action against
                    # ChatActionPayload and drop any that don't conform.
                    # Keeps the streaming path's action grammar identical
                    # to the single-shot endpoint at ai.py:944-960.
                    validated: list[dict] = []
                    for raw in event.get("actions", []) or []:
                        if not isinstance(raw, dict):
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


# ---------------------------------------------------------------------------
# A7 — Automation action AI (ai.complete / ai.summarize)
# ---------------------------------------------------------------------------
#
# Two endpoints invoked by the on-device automation engine. They live
# under the existing ``/ai/`` router prefix so they automatically inherit:
#   * the PII-egress AI gate (``require_ai_features_enabled``)
#   * the Pro-tier daily AI rate limiter (per-user budget)
#   * the auth dependency
#
# The on-device handlers (``AiCompleteActionHandler`` /
# ``AiSummarizeActionHandler``) own the master-AI-toggle short-circuit and
# the result-mapping (HTTP 451 -> ActionResult.Skipped, others ->
# ActionResult.Error). The router stays uniform with its siblings.


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
    automation_action_rate_limiter.check(request)
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
    automation_action_rate_limiter.check(request)
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
