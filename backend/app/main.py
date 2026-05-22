from fastapi import Depends, FastAPI, HTTPException, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from prometheus_client import generate_latest
from prometheus_fastapi_instrumentator import Instrumentator

from app.config import settings
from app.routers import ai, analytics, app_update, auth, beta_codes, calendar, daily_essentials, dashboard, export, feedback, goals, habits, integrations, leisure, medications, nd_preferences, projects, search, syllabus, sync, tags, tasks, templates
from app.routers.admin import activity_logs as admin_activity_logs
from app.routers.admin import debug_logs as admin_debug_logs

# Single source of truth for the backend API version. Keep in sync with
# the version reported in /health_check and exposed via the OpenAPI schema.
API_VERSION = "0.2.0"

app = FastAPI(
    title="PrismTask API",
    description="Hierarchical task management API with AI-powered NLP",
    version=API_VERSION,
    debug=settings.debug,
)

_cors_origins = settings.effective_cors_origins
_has_wildcard = "*" in _cors_origins

app.add_middleware(
    CORSMiddleware,
    allow_origins=_cors_origins,
    allow_credentials=not _has_wildcard,  # credentials require explicit origins
    allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type"],
)

app.include_router(ai.router, prefix="/api/v1")
app.include_router(analytics.router, prefix="/api/v1")
app.include_router(app_update.router, prefix="/api/v1")
app.include_router(auth.router, prefix="/api/v1")
app.include_router(beta_codes.router, prefix="/api/v1")
app.include_router(goals.router, prefix="/api/v1")
app.include_router(projects.router, prefix="/api/v1")
app.include_router(dashboard.router, prefix="/api/v1")
app.include_router(tasks.router, prefix="/api/v1")
app.include_router(tags.router, prefix="/api/v1")
app.include_router(habits.router, prefix="/api/v1")
app.include_router(templates.router, prefix="/api/v1/templates", tags=["templates"])
app.include_router(search.router, prefix="/api/v1")
app.include_router(syllabus.router, prefix="/api/v1")
app.include_router(sync.router, prefix="/api/v1")
app.include_router(export.router, prefix="/api/v1")
app.include_router(feedback.router, prefix="/api/v1")
app.include_router(integrations.router, prefix="/api/v1")
app.include_router(calendar.router, prefix="/api/v1")
app.include_router(nd_preferences.router, prefix="/api/v1")
app.include_router(daily_essentials.router, prefix="/api/v1")
app.include_router(leisure.router, prefix="/api/v1")
app.include_router(medications.router, prefix="/api/v1")
app.include_router(admin_activity_logs.router, prefix="/api/v1")
app.include_router(admin_debug_logs.router, prefix="/api/v1")

# Wire the prometheus HTTP middleware so default request-count and
# request-duration histograms (used by the P1/P2 alert rules) flow into
# the same registry as the audit-emit counter.
Instrumentator(
    should_group_status_codes=True,
    should_ignore_untemplated=True,
    excluded_handlers=["/metrics"],
).instrument(app)


def _client_ip(request: Request) -> str:
    forwarded = request.headers.get("x-forwarded-for")
    if forwarded:
        # Proxies typically append to the header. To prevent trivial spoofing
        # where an attacker sends "X-Forwarded-For: 127.0.0.1", we take the
        # rightmost IP added by our reverse proxy rather than the leftmost.
        return forwarded.split(",")[-1].strip()
    return request.client.host if request.client else "unknown"


def _require_scrape_token(request: Request) -> None:
    """Bearer-token gate for /metrics. Empty token → 503 (endpoint inert).

    Grafana Cloud's hosted scraper presents the same token in
    ``Authorization: Bearer <token>`` on every scrape; mismatch returns
    401 to keep the endpoint useless to anonymous callers.
    """
    if not settings.METRICS_SCRAPE_TOKEN:
        raise HTTPException(status_code=503, detail="metrics endpoint not configured")
    auth = request.headers.get("authorization", "")
    if auth != f"Bearer {settings.METRICS_SCRAPE_TOKEN}":
        raise HTTPException(status_code=401, detail="invalid scrape token")

    if "*" not in settings.METRICS_ALLOWED_IPS:
        ip = _client_ip(request)
        if ip not in settings.METRICS_ALLOWED_IPS:
            raise HTTPException(status_code=403, detail="ip not allowed")


@app.get("/metrics")
async def metrics(_: None = Depends(_require_scrape_token)) -> Response:
    return Response(
        content=generate_latest(),
        media_type="text/plain; version=0.0.4; charset=utf-8",
    )


@app.on_event("startup")
async def _start_calendar_sync_scheduler() -> None:
    try:
        from app.services.calendar_periodic_sync import start_scheduler

        start_scheduler()
    except Exception as exc:  # noqa: BLE001
        import logging

        logging.getLogger(__name__).warning(
            "Failed to start calendar sync scheduler: %s", exc
        )


@app.on_event("shutdown")
async def _stop_calendar_sync_scheduler() -> None:
    try:
        from app.services.calendar_periodic_sync import stop_scheduler

        stop_scheduler()
    except Exception:  # noqa: BLE001
        pass


@app.on_event("startup")
async def _start_weekly_review_generator() -> None:
    """Cron that auto-generates the prior-week WeeklyReview row for every
    Firebase-linked user. Parity audit C.4b — see
    ``app/tasks/weekly_review_generator.py``."""
    try:
        from app.tasks.weekly_review_generator import start_scheduler

        start_scheduler()
    except Exception as exc:  # noqa: BLE001
        import logging

        logging.getLogger(__name__).warning(
            "Failed to start weekly_review_generator: %s", exc
        )


@app.on_event("shutdown")
async def _stop_weekly_review_generator() -> None:
    try:
        from app.tasks.weekly_review_generator import stop_scheduler

        stop_scheduler()
    except Exception:  # noqa: BLE001
        pass


@app.get("/")
async def health_check():
    return {"status": "healthy", "service": "PrismTask API", "version": API_VERSION}