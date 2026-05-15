"""APScheduler job that auto-generates the prior-week WeeklyReview row
for every user with a linked Firebase UID.

Runs once a week (Sunday end-of-day UTC) and aggregates each user's
last 7 days of tasks + habits into a snapshot stored at
``users/{uid}/weekly_reviews/{ISO_MONDAY_OF_WEEK}`` in Firestore.

The Firestore mapper schema is owned by ``web/src/api/firestore/weeklyReviews.ts``
(see PR #1369 / parity audit C.4a). This job writes the same shape so
Android pulls and the web subscriber both render the row.

Aggregation parity: this is a *count-only* aggregator that mirrors the
subset of ``WeeklyReviewAggregator.aggregate()`` on Android relevant to
the server-side cron. Narrative AI insights remain an explicit
client-triggered action (`POST /api/v1/ai/weekly-review`) — the cron
seeds the empty row + raw metrics so the screen shows last week's data
on first open even when neither client has run the aggregator locally.
"""

from __future__ import annotations

import asyncio
import json
import logging
from datetime import datetime, timedelta, timezone
from typing import Any

try:
    from apscheduler.schedulers.asyncio import AsyncIOScheduler
except ImportError:  # pragma: no cover - import guard for minimal envs
    AsyncIOScheduler = None  # type: ignore

from sqlalchemy import select

from app.database import async_session_factory
from app.models import User

logger = logging.getLogger(__name__)

_scheduler: AsyncIOScheduler | None = None

# Firestore subcollection path under ``users/{uid}``.
_COLLECTION = "weekly_reviews"

# Tracked life categories (mirrors LifeCategory.TRACKED on Android — the
# server only writes ratios for these four; "uncategorized" tasks are
# excluded from the by-category map so the web report doesn't render an
# Uncategorized slice).
_TRACKED_CATEGORIES = ("work", "personal", "self_care", "health")


def _last_week_window(reference: datetime | None = None) -> tuple[datetime, datetime, str]:
    """Compute the Monday 00:00 UTC / following-Monday 00:00 UTC window
    for the week the reference instant lives in (or just ended).

    The cron fires Sun 23:59 UTC and aggregates the week that's about to
    close: Mon 00:00 UTC through next Mon 00:00 UTC (exclusive). When
    called on Monday at 00:00 UTC exactly, that boundary itself is the
    end of the prior week — treat it as the *exclusive* end so the doc
    id stays anchored to last Monday and the cron doesn't double-write.

    Returns ``(week_start, week_end_exclusive, iso_date_for_doc_id)``.
    The doc id is the Monday-of-week ISO date so a re-run over the same
    week is naturally idempotent via Firestore's ``setDoc(merge=True)``
    (Pattern A canonical-row scheme, same as web's `upsertWeeklyReview`).
    """
    now = reference or datetime.now(timezone.utc)
    monday_at_or_before = (now - timedelta(days=now.weekday())).replace(
        hour=0, minute=0, second=0, microsecond=0
    )
    # If ``now`` is Monday (weekday == 0), the most recently-completed
    # week ended at ``monday_at_or_before`` and the prior Monday is the
    # week-start. Otherwise the just-completed week is bookended by
    # this week's Monday (start) and next Monday (exclusive end). The
    # cron runs Sun 23:59 UTC so the Sunday branch is the common path.
    if now.weekday() == 0:
        week_end = monday_at_or_before
    else:
        week_end = monday_at_or_before + timedelta(days=7)
    week_start = week_end - timedelta(days=7)
    return week_start, week_end, week_start.date().isoformat()


def _aggregate_user_week(
    firestore_client: Any,
    uid: str,
    week_start: datetime,
    week_end_exclusive: datetime,
) -> dict[str, Any]:
    """Compute the metrics blob for a single user over the given week.

    Pure-ish: takes an injected Firestore client so unit tests can pass
    a mock without spinning up firebase-admin.
    """
    week_start_ms = int(week_start.timestamp() * 1000)
    week_end_ms = int(week_end_exclusive.timestamp() * 1000)

    tasks_col = (
        firestore_client.collection("users").document(uid).collection("tasks")
    )
    completed_count = 0
    slipped_count = 0
    by_category: dict[str, int] = {c: 0 for c in _TRACKED_CATEGORIES}

    for doc in tasks_col.stream():
        data = doc.to_dict() or {}
        completed_at = data.get("completedAt")
        is_completed = bool(data.get("isCompleted"))
        archived_at = data.get("archivedAt")
        due_date = data.get("dueDate")

        if (
            is_completed
            and isinstance(completed_at, (int, float))
            and week_start_ms <= int(completed_at) < week_end_ms
        ):
            completed_count += 1
            cat = (data.get("lifeCategory") or "").lower().replace("-", "_")
            if cat in by_category:
                by_category[cat] += 1
            continue

        was_due_in_window = (
            isinstance(due_date, (int, float))
            and week_start_ms <= int(due_date) < week_end_ms
        )
        still_open = not is_completed and archived_at is None
        if was_due_in_window and still_open:
            slipped_count += 1

    # Habit completions: count rows whose ``completedAt`` falls in the
    # window. Schema in web/src/api/firestore/habits.ts; Android writes
    # the same shape via SyncService.
    habits_col = (
        firestore_client.collection("users")
        .document(uid)
        .collection("habit_completions")
    )
    habit_hits = 0
    for doc in habits_col.stream():
        data = doc.to_dict() or {}
        completed_at = data.get("completedAt")
        if (
            isinstance(completed_at, (int, float))
            and week_start_ms <= int(completed_at) < week_end_ms
        ):
            # Skip synthetic-skip rows the way habits.ts read() does.
            if not bool(data.get("isSkip")):
                habit_hits += 1

    total = completed_count + slipped_count
    completion_rate = (completed_count / total) if total > 0 else 0.0

    return {
        "week_start_ms": week_start_ms,
        "week_end_ms": week_end_ms,
        "completed_count": completed_count,
        "slipped_count": slipped_count,
        "habit_hits": habit_hits,
        "completion_rate": round(completion_rate, 4),
        "by_category": by_category,
        "generated_by": "cron",
        "generated_at_ms": int(datetime.now(timezone.utc).timestamp() * 1000),
    }


def _write_review(
    firestore_client: Any,
    uid: str,
    week_start_iso: str,
    week_start_ms: int,
    metrics: dict[str, Any],
) -> None:
    """Upsert the weekly_reviews/{week_start_iso} doc.

    ``setDoc(merge=True)`` is the canonical-row write semantic from
    web's ``upsertWeeklyReview``; we mirror it here so re-runs of the
    cron over the same week are idempotent and never clobber an
    AI-narrative payload a client may have already attached.
    """
    now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    doc_ref = (
        firestore_client.collection("users")
        .document(uid)
        .collection(_COLLECTION)
        .document(week_start_iso)
    )
    existing = doc_ref.get()
    existing_data = existing.to_dict() if existing.exists else None
    created_at = (
        existing_data.get("created_at")
        if existing_data and isinstance(existing_data.get("created_at"), (int, float))
        else now_ms
    )
    payload = {
        "week_start_date": week_start_iso,
        "week_start_ms": week_start_ms,
        "metrics_json": json.dumps(metrics, separators=(",", ":")),
        # ``ai_insights_json`` deliberately omitted from the payload so
        # ``merge=True`` preserves whatever a client (Android weekly-review
        # screen, web aggregator) has already attached.
        "created_at": created_at,
        "updated_at": now_ms,
    }
    doc_ref.set(payload, merge=True)


def _is_active(user: User) -> bool:
    return (
        user.firebase_uid is not None
        and user.firebase_uid != ""
        and user.deletion_pending_at is None
    )


async def run_weekly_review_for_all_users(
    reference: datetime | None = None,
) -> None:
    """Aggregate the prior 7 days and upsert one ``weekly_reviews`` doc
    per linked-Firebase user. Safe to call ad-hoc (e.g. from a test or
    admin endpoint) by passing a ``reference`` datetime.
    """
    if async_session_factory is None:
        return

    week_start, week_end, week_start_iso = _last_week_window(reference)
    week_start_ms = int(week_start.timestamp() * 1000)

    # Defer the firebase-admin import + Firestore client lookup so test
    # environments without GOOGLE_APPLICATION_CREDENTIALS don't blow up
    # on module import.
    try:
        from app.services.firestore_tasks import _get_firestore_client
        firestore_client = _get_firestore_client()
    except Exception as exc:  # noqa: BLE001
        logger.warning(
            "weekly_review_generator: Firestore unavailable, skipping run: %s", exc
        )
        return

    async with async_session_factory() as db:
        result = await db.execute(select(User))
        users = [u for u in result.scalars().all() if _is_active(u)]

    logger.info(
        "weekly_review_generator: aggregating week=%s for %d users",
        week_start_iso,
        len(users),
    )

    for user in users:
        uid = user.firebase_uid
        if not uid:
            continue
        try:
            metrics = await asyncio.to_thread(
                _aggregate_user_week,
                firestore_client,
                uid,
                week_start,
                week_end,
            )
            await asyncio.to_thread(
                _write_review,
                firestore_client,
                uid,
                week_start_iso,
                week_start_ms,
                metrics,
            )
        except Exception as exc:  # noqa: BLE001
            logger.warning(
                "weekly_review_generator: user=%s failed: %s", user.id, exc
            )
            continue


def start_scheduler() -> None:
    """Start the weekly job. Idempotent — safe to call from the FastAPI
    startup hook alongside ``calendar_periodic_sync.start_scheduler``.

    Cron: every Sunday at 23:59 UTC. The job aggregates the *prior* 7
    days (last Monday 00:00 UTC through this Monday 00:00 UTC) so by
    the time it lands on Sunday night, the week being summarized is the
    one that just ended.
    """
    global _scheduler
    if AsyncIOScheduler is None:
        logger.warning(
            "APScheduler not installed; weekly_review_generator disabled"
        )
        return
    if _scheduler is not None and _scheduler.running:
        return
    _scheduler = AsyncIOScheduler(timezone="UTC")
    _scheduler.add_job(
        run_weekly_review_for_all_users,
        "cron",
        day_of_week="sun",
        hour=23,
        minute=59,
        id="weekly_review_generator",
        replace_existing=True,
        max_instances=1,
    )
    _scheduler.start()
    logger.info("weekly_review_generator started (Sun 23:59 UTC)")


def stop_scheduler() -> None:
    global _scheduler
    if _scheduler is not None and _scheduler.running:
        _scheduler.shutdown(wait=False)
    _scheduler = None
