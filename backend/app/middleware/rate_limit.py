import time
from collections import defaultdict
from datetime import datetime, timezone
from threading import Lock

from fastapi import HTTPException, Request


class RateLimiter:
    """Simple in-memory rate limiter keyed by client IP."""

    def __init__(self, max_requests: int = 10, window_seconds: int = 60):
        self.max_requests = max_requests
        self.window_seconds = window_seconds
        self._requests: dict[str, list[float]] = defaultdict(list)
        self._lock = Lock()

    def _client_ip(self, request: Request) -> str:
        forwarded = request.headers.get("x-forwarded-for")
        if forwarded:
            return forwarded.split(",")[0].strip()
        return request.client.host if request.client else "unknown"

    def _cleanup(self, key: str, now: float) -> None:
        cutoff = now - self.window_seconds
        self._requests[key] = [t for t in self._requests[key] if t > cutoff]

    def _evict_stale(self, now: float) -> None:
        """Drop keys whose entries are all outside the window. Prevents the
        defaultdict from growing unboundedly as unique IPs come and go."""
        cutoff = now - self.window_seconds
        stale = [k for k, timestamps in self._requests.items() if not timestamps or timestamps[-1] <= cutoff]
        for k in stale:
            del self._requests[k]

    def check(self, request: Request, is_admin: bool = False) -> None:
        if is_admin:
            return
        now = time.monotonic()
        key = self._client_ip(request)
        with self._lock:
            # Periodic sweep keeps the dict bounded under churn.
            if len(self._requests) > 1024:
                self._evict_stale(now)
            self._cleanup(key, now)
            if len(self._requests[key]) >= self.max_requests:
                raise HTTPException(
                    status_code=429,
                    detail="Too many requests. Please try again later.",
                )
            self._requests[key].append(now)


class DailyAIRateLimiter:
    """Daily AI call rate limiter keyed by user ID.

    Tier-based limits:
    - Pro: 100 calls/day (covers both monthly and annual billing)
    - Free: 0 (AI features are gated, but safety net)

    Resets at midnight UTC. Counts reset on server restart (in-memory).
    """

    TIER_LIMITS = {
        "PRO": 100,
        "FREE": 0,
    }

    def __init__(self) -> None:
        # {user_id: (utc_date_str, count)}
        self._counts: dict[int, tuple[str, int]] = {}
        self._lock = Lock()

    def _today_key(self) -> str:
        return datetime.now(timezone.utc).strftime("%Y-%m-%d")

    def check(self, user_id: int, tier: str, is_admin: bool = False) -> None:
        if is_admin:
            return
        limit = self.TIER_LIMITS.get(tier, 0)
        if limit == 0:
            # Defense in depth: the client-side feature gate should keep
            # Free users off these endpoints, but if a request gets through
            # (e.g. direct API call bypassing the app), deny it here too.
            raise HTTPException(
                status_code=403,
                detail="AI features require a Pro subscription",
            )

        today = self._today_key()
        with self._lock:
            entry = self._counts.get(user_id)
            if entry is None or entry[0] != today:
                # New day or first call — reset
                self._counts[user_id] = (today, 1)
                return

            current_count = entry[1]
            if current_count >= limit:
                raise HTTPException(
                    status_code=429,
                    detail="Daily AI limit reached \u2014 resets at midnight UTC",
                )
            self._counts[user_id] = (today, current_count + 1)


# Auth endpoints: 10 requests per 60 seconds per IP
auth_rate_limiter = RateLimiter(max_requests=10, window_seconds=60)

# Daily AI call rate limiter (tier-based)
daily_ai_rate_limiter = DailyAIRateLimiter()


class UserRateLimiter:
    """Window-based rate limiter keyed by user ID (not IP).

    Used for authenticated endpoints where per-user limits are more
    appropriate than per-IP limits (e.g. AI import parsing).
    """

    def __init__(self, max_requests: int = 10, window_seconds: int = 3600):
        self.max_requests = max_requests
        self.window_seconds = window_seconds
        self._requests: dict[int, list[float]] = defaultdict(list)
        self._lock = Lock()

    def _cleanup(self, user_id: int, now: float) -> None:
        cutoff = now - self.window_seconds
        self._requests[user_id] = [t for t in self._requests[user_id] if t > cutoff]

    def _evict_stale(self, now: float) -> None:
        cutoff = now - self.window_seconds
        stale = [k for k, timestamps in self._requests.items() if not timestamps or timestamps[-1] <= cutoff]
        for k in stale:
            del self._requests[k]

    def check(self, user_id: int, is_admin: bool = False) -> None:
        if is_admin:
            return
        now = time.monotonic()
        with self._lock:
            if len(self._requests) > 1024:
                self._evict_stale(now)
            self._cleanup(user_id, now)
            if len(self._requests[user_id]) >= self.max_requests:
                raise HTTPException(
                    status_code=429,
                    detail="Rate limit exceeded. Max 10 import parse requests per hour.",
                )
            self._requests[user_id].append(now)


# Import parse: 10 requests per user per hour
import_parse_rate_limiter = UserRateLimiter(max_requests=10, window_seconds=3600)
