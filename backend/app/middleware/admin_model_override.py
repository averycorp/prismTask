"""Admin-only AI model override.

When an admin client sends ``X-PrismTask-Admin-Model-Override: sonnet`` on
an AI request, every ``get_model()`` call inside that request returns
Sonnet instead of Haiku. The header is silently ignored for non-admin
users so it can't be used as an authorization bypass.

Wire as a router-level dependency on any router whose handlers call
``app.services.ai_productivity.get_model``. The contextvar is set before
the route runs and reset when the request scope unwinds.
"""

from fastapi import Depends, Request

from app.middleware.auth import get_current_user
from app.models import User
from app.services import ai_productivity

HEADER_NAME = "X-PrismTask-Admin-Model-Override"
ALLOWED_VALUES = {"sonnet"}


async def apply_admin_model_override(
    request: Request,
    current_user: User = Depends(get_current_user),
):
    requested = request.headers.get(HEADER_NAME, "").strip().lower()
    if requested in ALLOWED_VALUES and current_user.is_admin:
        token = ai_productivity.admin_model_override.set(requested)
    else:
        token = None
    try:
        yield
    finally:
        if token is not None:
            ai_productivity.admin_model_override.reset(token)
