"""Leisure Budget v2.0 tier gate.

Per the v2.0 audit (Q1 lock): the refresh limit is NOT a tier-gate
— it's the feature itself. The tier gate sits on the
*enforcement-mode choice*: only Pro users can opt into MEDIUM or HARD
enforcement. SOFT is universal and is the implicit default.

This dependency is request-body-conditional: ``PATCH /leisure/settings``
only triggers the gate when the request body sets
``enforcement_mode != SOFT``. Free users patching other fields (target
minutes, refresh limit, enabled categories) pass through.

The dependency expects ``current_user`` to be resolved upstream and the
parsed request body to be passed in via the ``body`` Depends. Keeping
the gate as a separate dependency (rather than inlining the check
inside the router handler) means future routers can re-use the same
"only-Pro-can-choose" pattern without re-implementing the tier check.
"""

from fastapi import Depends, HTTPException, status

from app.middleware.auth import get_current_user
from app.models import User
from app.schemas.leisure import LeisureSettingsUpdate


async def require_leisure_enforcement_choice(
    body: LeisureSettingsUpdate,
    current_user: User = Depends(get_current_user),
) -> LeisureSettingsUpdate:
    """Reject MEDIUM/HARD enforcement choices from non-Pro users.

    Returns the parsed body so the route handler can re-use it without
    re-parsing. The handler signature should look like:

        async def update_settings(
            body: LeisureSettingsUpdate = Depends(
                require_leisure_enforcement_choice
            ),
            ...
        ):
    """
    if body.enforcement_mode is None or body.enforcement_mode == "SOFT":
        return body
    if current_user.effective_tier == "PRO":
        return body
    raise HTTPException(
        status_code=status.HTTP_402_PAYMENT_REQUIRED,
        detail=(
            "Choosing MEDIUM or HARD enforcement requires PrismTask Pro. "
            "SOFT enforcement and all other leisure features remain free."
        ),
    )
