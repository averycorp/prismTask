"""Shared helpers + rate limiters for the AI sub-routers.

This module exists so every feature file (``eisenhower.py``,
``pomodoro.py``, ``chat.py``, …) can share the same set of:

* rate-limiter singletons (we keep ONE instance per limiter — splitting
  per-file would silently widen the effective per-IP budget because each
  module's import would mint a fresh counter),
* lightweight helpers (``_require_firebase_uid``, ``_get_incomplete_tasks``,
  ``_log_empty_short_circuit``).

Public names re-exported by the package's ``__init__.py`` so existing
test imports (``from app.routers.ai import chat_rate_limiter`` etc.)
keep working byte-for-byte. See ``app/routers/ai/__init__.py``.
"""

import logging

from fastapi import HTTPException, status

from app.middleware.rate_limit import RateLimiter
from app.models import User
from app.services.firestore_tasks import TaskDTO  # noqa: F401  (re-exported)

logger = logging.getLogger("app.routers.ai")

# ---------------------------------------------------------------------------
# Rate limiters — single instance per limiter, shared across feature files.
# Per-feature comments preserved from the original ai.py so the budget
# rationale is still discoverable next to the value.
# ---------------------------------------------------------------------------

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

# AI urgency scoring (Pro). Fired when a Pro user with the AI-urgency
# toggle on chooses the URGENCY sort on the Tasks screen. The client
# debounces and batches up to 50 tasks per call, so 10/min per IP
# tolerates the user toggling sort modes back and forth without burning
# Claude budget. The server-side daily AI rate limiter is the real
# ceiling.
urgency_score_rate_limiter = RateLimiter(max_requests=10, window_seconds=60)

# Per-task duration estimation — Pro-only Haiku call fired fire-and-forget
# from AddEditTaskViewModel save when the user leaves estimatedDuration blank.
# 20/min mirrors the other per-task text-classify limiters (one call per
# saved task, with headroom for voice-add bursts).
duration_estimate_rate_limiter = RateLimiter(max_requests=20, window_seconds=60)

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
# v1.7 file-extract — heavier per-call (multimodal + DOCX/XLSX parse + Claude),
# so cap tighter than paste-to-extract. 5/min still covers a user iterating on
# the same upload (re-pick → re-extract) without burning Claude budget.
file_extract_rate_limiter = RateLimiter(max_requests=5, window_seconds=60)
# Hard cap on uploaded bytes — mirrors MAX_FILE_BYTES in
# app/services/file_extraction.py. Keep both in lockstep.
FILE_EXTRACT_MAX_BYTES = 10 * 1024 * 1024
# G — screenshot vision extract. Vision tokens cost ~10x text, so a tighter
# 5/min cap protects the per-tier daily budget while still allowing a user
# to retry on a poor-quality screenshot. The daily AI rate limiter is the
# real budget ceiling.
vision_extract_rate_limiter = RateLimiter(max_requests=5, window_seconds=60)
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


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


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
    # Look up Firestore helpers via the package module so tests that
    # patch ``app.routers.ai.fetch_incomplete_tasks`` /
    # ``fetch_tasks_by_ids`` see their stubs take effect. A direct
    # ``from app.services.firestore_tasks import …`` would bind the
    # symbol at import time and bypass the patch.
    from app.routers import ai as _ai_pkg

    if task_ids:
        tasks = await _ai_pkg.fetch_tasks_by_ids(uid, task_ids)
        # Match the old Postgres filter: only incomplete tasks.
        # fetch_tasks_by_ids returns any doc that exists; drop completed
        # ones here.
        return [t for t in tasks if t.completed_at is None]
    return await _ai_pkg.fetch_incomplete_tasks(uid)


def _log_empty_short_circuit(user: User, endpoint: str) -> None:
    logger.info(
        "AI short-circuit: no_incomplete_tasks user_id=%s endpoint=%s",
        user.id,
        endpoint,
    )
