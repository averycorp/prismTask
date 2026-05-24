"""Admin bypass on rate limiters.

Admins (User.is_admin=True) should not be subject to any rate limit. The
three limiter classes — RateLimiter (IP-based), UserRateLimiter
(per-user window), and DailyAIRateLimiter (per-user daily tier cap) —
all accept an ``is_admin`` kwarg that short-circuits the limit when True.

Coverage:
  - Unit tests on each limiter class in isolation (bypass + budget-isolation).
  - One end-to-end test through ``/api/v1/ai/daily-briefing`` (1/hour IP cap)
    that confirms an admin can call it repeatedly within the window — this is
    the bug report that motivated the change.
"""

from unittest.mock import AsyncMock, patch

import pytest
from fastapi import HTTPException
from httpx import AsyncClient
from sqlalchemy import update
from starlette.requests import Request

from app.middleware.rate_limit import (
    DailyAIRateLimiter,
    RateLimiter,
    UserRateLimiter,
)
from app.models import User
from tests.conftest import TEST_FIREBASE_UID, TestSessionLocal


def _make_request(ip: str = "1.2.3.4") -> Request:
    scope = {
        "type": "http",
        "client": (ip, 0),
        "headers": [],
    }
    return Request(scope)


# ---------------------------------------------------------------------------
# RateLimiter (IP-based)
# ---------------------------------------------------------------------------


def test_ip_limiter_blocks_non_admin_after_cap():
    limiter = RateLimiter(max_requests=1, window_seconds=60)
    req = _make_request()
    limiter.check(req)
    with pytest.raises(HTTPException) as exc:
        limiter.check(req)
    assert exc.value.status_code == 429


def test_ip_limiter_bypasses_admin_indefinitely():
    limiter = RateLimiter(max_requests=1, window_seconds=60)
    req = _make_request()
    for _ in range(50):
        limiter.check(req, is_admin=True)


def test_ip_limiter_admin_does_not_consume_budget():
    """An admin's calls must not be recorded against the IP bucket — a
    subsequent non-admin call from the same IP must still get the full
    allowance."""
    limiter = RateLimiter(max_requests=1, window_seconds=60)
    req = _make_request()
    for _ in range(10):
        limiter.check(req, is_admin=True)
    limiter.check(req)


# ---------------------------------------------------------------------------
# UserRateLimiter (per-user window)
# ---------------------------------------------------------------------------


def test_user_limiter_blocks_non_admin_after_cap():
    limiter = UserRateLimiter(max_requests=1, window_seconds=3600)
    limiter.check(user_id=42)
    with pytest.raises(HTTPException) as exc:
        limiter.check(user_id=42)
    assert exc.value.status_code == 429


def test_user_limiter_bypasses_admin_indefinitely():
    limiter = UserRateLimiter(max_requests=1, window_seconds=3600)
    for _ in range(50):
        limiter.check(user_id=42, is_admin=True)


def test_user_limiter_admin_does_not_consume_budget():
    limiter = UserRateLimiter(max_requests=1, window_seconds=3600)
    for _ in range(10):
        limiter.check(user_id=42, is_admin=True)
    limiter.check(user_id=42)


# ---------------------------------------------------------------------------
# DailyAIRateLimiter (per-user daily tier cap)
# ---------------------------------------------------------------------------


def test_daily_ai_limiter_blocks_free_tier():
    limiter = DailyAIRateLimiter()
    with pytest.raises(HTTPException) as exc:
        limiter.check(user_id=1, tier="FREE")
    assert exc.value.status_code == 403


def test_daily_ai_limiter_blocks_pro_after_cap():
    limiter = DailyAIRateLimiter()
    for _ in range(DailyAIRateLimiter.TIER_LIMITS["PRO"]):
        limiter.check(user_id=2, tier="PRO")
    with pytest.raises(HTTPException) as exc:
        limiter.check(user_id=2, tier="PRO")
    assert exc.value.status_code == 429


def test_daily_ai_limiter_bypasses_admin_indefinitely():
    limiter = DailyAIRateLimiter()
    # Admin bypass must work even when tier resolves to FREE (the 403 path).
    for _ in range(DailyAIRateLimiter.TIER_LIMITS["PRO"] * 2):
        limiter.check(user_id=3, tier="FREE", is_admin=True)


def test_daily_ai_limiter_admin_does_not_consume_budget():
    limiter = DailyAIRateLimiter()
    for _ in range(500):
        limiter.check(user_id=4, tier="PRO", is_admin=True)
    # Non-admin call from the same user_id still gets the full daily allowance.
    for _ in range(DailyAIRateLimiter.TIER_LIMITS["PRO"]):
        limiter.check(user_id=4, tier="PRO")


# ---------------------------------------------------------------------------
# End-to-end: /api/v1/ai/daily-briefing with an admin caller
# ---------------------------------------------------------------------------


async def _promote_to_admin(email: str = "test@example.com") -> None:
    async with TestSessionLocal() as session:
        await session.execute(
            update(User)
            .where(User.email == email)
            .values(is_admin=True, firebase_uid=TEST_FIREBASE_UID),
        )
        await session.commit()


@pytest.mark.asyncio
async def test_daily_briefing_admin_bypasses_ip_rate_limit(
    client: AsyncClient, auth_headers: dict
):
    """Reproduces the bug report: admin hitting /daily-briefing repeatedly
    must not be 429'd by the 1-hour IP cap."""
    from app.routers.ai import briefing_rate_limiter
    briefing_rate_limiter._requests.clear()

    await _promote_to_admin()

    with patch(
        "app.routers.ai.fetch_incomplete_tasks",
        new=AsyncMock(return_value=[]),
    ), patch(
        "app.routers.ai.fetch_recently_completed_tasks",
        new=AsyncMock(return_value=[]),
    ), patch(
        # Habits also live in Firestore now; the endpoint calls
        # fetch_active_habits before the empty short-circuit, so it must be
        # stubbed too or the call hits real Firestore (no creds in CI → 500).
        "app.services.firestore_state.fetch_active_habits",
        new=AsyncMock(return_value=[]),
    ):
        for _ in range(5):
            resp = await client.post(
                "/api/v1/ai/daily-briefing",
                json={},
                headers=auth_headers,
            )
            assert resp.status_code == 200, resp.text
