"""AI router package — aggregates feature sub-routers.

This package was split out of the original ``backend/app/routers/ai.py``
(1845 LOC, ~23 endpoints) to keep each AI feature surface under 300 LOC.
The split is purely organizational — URLs and response shapes are
byte-identical to the pre-split router; tests, the Android client, and
the streaming SSE contract are all untouched. See PR description and
``docs/audits/REFACTOR_TIERS_1_3_AUDIT.md`` (T2.1) for context.

Layout:

* ``_common.py`` — shared rate-limiter singletons + helper functions.
* ``eisenhower.py`` — Eisenhower / cognitive-load / life-category
  classification (4 endpoints).
* ``pomodoro.py`` — pomodoro plan + coaching (2 endpoints).
* ``daily_briefing.py`` — daily briefing (1 endpoint).
* ``weekly_plan.py`` — weekly plan (1 endpoint).
* ``time_block.py`` — calendar-aware time blocking (1 endpoint).
* ``weekly_review.py`` — AI weekly review (1 endpoint).
* ``extract.py`` — paste / file / vision extraction (3 endpoints).
* ``batch.py`` — NLP batch mutation parsing (1 endpoint).
* ``chat.py`` — AI Coach chat: single-shot + history (2 endpoints).
* ``chat_stream.py`` — AI Coach token-streaming variant (1 endpoint).
* ``memory.py`` — AI memory CRUD (4 endpoints) + helpers shared with
  the chat endpoints.
* ``automation.py`` — automation-engine AI actions (2 endpoints).

Backward-compatibility surface
------------------------------

Existing tests (``tests/test_ai_chat.py``, ``tests/test_ai_memory.py``,
``tests/test_ai_productivity.py``, ``tests/test_file_extraction.py``)
and ``app/middleware/ai_gate.py`` import rate-limiter and helper symbols
directly from ``app.routers.ai``. To keep those imports working
unchanged, every symbol previously defined at the module top level is
re-exported here.
"""

from fastapi import APIRouter, Depends

from app.middleware.ai_gate import require_ai_features_enabled

# ---------------------------------------------------------------------------
# Backward-compatible re-exports.
# Tests + middleware import these directly from ``app.routers.ai`` — keep the
# import path stable so the split is a pure refactor.
# ---------------------------------------------------------------------------
from ._common import (  # noqa: F401  (re-export)
    FILE_EXTRACT_MAX_BYTES,
    _get_incomplete_tasks,
    _log_empty_short_circuit,
    _require_firebase_uid,
    ai_rate_limiter,
    automation_action_rate_limiter,
    batch_parse_rate_limiter,
    briefing_rate_limiter,
    chat_rate_limiter,
    cognitive_load_classify_text_rate_limiter,
    duration_estimate_rate_limiter,
    eisenhower_classify_text_rate_limiter,
    extract_rate_limiter,
    file_extract_rate_limiter,
    life_category_classify_text_rate_limiter,
    pomodoro_coaching_rate_limiter,
    time_block_rate_limiter,
    vision_extract_rate_limiter,
    weekly_plan_rate_limiter,
    weekly_review_rate_limiter,
)

# Also re-export the Firestore helpers that tests patch via
# ``app.routers.ai.fetch_incomplete_tasks`` / ``fetch_recently_completed_tasks``.
# Importing them here means a ``patch("app.routers.ai.fetch_incomplete_tasks")``
# call still finds an attribute on this module.
from app.services.firestore_tasks import (  # noqa: F401  (re-export)
    fetch_incomplete_tasks,
    fetch_recently_completed_tasks,
    fetch_tasks_by_ids,
)

from . import (
    automation,
    batch,
    chat,
    chat_stream,
    daily_briefing,
    eisenhower,
    extract,
    memory,
    pomodoro,
    time_block,
    weekly_plan,
    weekly_review,
)

# ---------------------------------------------------------------------------
# Aggregate router — mounted at /api/v1/ai from ``app.main``.
# PII egress audit (2026-04-26): every endpoint on this router can transit
# user data to Anthropic. Reject requests where the client has signalled the
# user disabled AI features in PrismTask Settings.
# ---------------------------------------------------------------------------
router = APIRouter(
    prefix="/ai",
    tags=["ai"],
    dependencies=[Depends(require_ai_features_enabled)],
)

# Order intentionally matches the original ai.py file layout so the OpenAPI
# schema's endpoint order (and any client tooling that reads it) is stable.
router.include_router(eisenhower.router)
router.include_router(pomodoro.router)
router.include_router(daily_briefing.router)
router.include_router(weekly_plan.router)
router.include_router(time_block.router)
router.include_router(weekly_review.router)
router.include_router(extract.router)
router.include_router(batch.router)
router.include_router(chat.router)
router.include_router(chat_stream.router)
router.include_router(memory.router)
router.include_router(automation.router)
