"""Leisure Budget v2.0 router tests.

Covers:
* ``/leisure/activities`` CRUD + tenant isolation.
* ``/leisure/sessions`` insert + last_completed_at denormalization +
  ``activity_id`` cross-user rejection.
* ``/leisure/settings`` get-or-create + deferred enforcement-mode +
  tier-gated MEDIUM/HARD escalation (Q1 lock — refresh limit, target
  minutes, enabled_categories are free for all tiers).
"""

from datetime import datetime, timezone

import pytest
from httpx import AsyncClient


@pytest.mark.asyncio
async def test_create_activity_then_list(client: AsyncClient, auth_headers: dict):
    create = await client.post(
        "/api/v1/leisure/activities",
        json={
            "id": "act_walk_001",
            "name": "Walk",
            "category": "PHYSICAL",
            "default_duration_minutes": 30,
            "enabled": True,
        },
        headers=auth_headers,
    )
    assert create.status_code == 201, create.text
    body = create.json()
    assert body["id"] == "act_walk_001"
    assert body["name"] == "Walk"
    assert body["category"] == "PHYSICAL"
    assert body["enabled"] is True

    listing = await client.get(
        "/api/v1/leisure/activities", headers=auth_headers
    )
    assert listing.status_code == 200
    rows = listing.json()
    assert len(rows) == 1
    assert rows[0]["id"] == "act_walk_001"


@pytest.mark.asyncio
async def test_create_activity_rejects_invalid_category(
    client: AsyncClient, auth_headers: dict
):
    resp = await client.post(
        "/api/v1/leisure/activities",
        json={
            "id": "act_invalid_001",
            "name": "Bogus",
            "category": "WORK",  # Not in the spec-locked four.
        },
        headers=auth_headers,
    )
    assert resp.status_code == 422


@pytest.mark.asyncio
async def test_update_activity_changes_fields(
    client: AsyncClient, auth_headers: dict
):
    await client.post(
        "/api/v1/leisure/activities",
        json={
            "id": "act_piano_001",
            "name": "Piano",
            "category": "CREATIVE",
        },
        headers=auth_headers,
    )
    patch = await client.patch(
        "/api/v1/leisure/activities/act_piano_001",
        json={"name": "Piano Practice", "enabled": False},
        headers=auth_headers,
    )
    assert patch.status_code == 200, patch.text
    body = patch.json()
    assert body["name"] == "Piano Practice"
    assert body["enabled"] is False


@pytest.mark.asyncio
async def test_delete_activity_removes_row(
    client: AsyncClient, auth_headers: dict
):
    await client.post(
        "/api/v1/leisure/activities",
        json={"id": "act_doomed_001", "name": "Doomed", "category": "PASSIVE"},
        headers=auth_headers,
    )
    delete = await client.delete(
        "/api/v1/leisure/activities/act_doomed_001", headers=auth_headers
    )
    assert delete.status_code == 204

    listing = await client.get(
        "/api/v1/leisure/activities", headers=auth_headers
    )
    assert listing.status_code == 200
    assert listing.json() == []


@pytest.mark.asyncio
async def test_create_session_updates_activity_last_completed_at(
    client: AsyncClient, auth_headers: dict
):
    await client.post(
        "/api/v1/leisure/activities",
        json={
            "id": "act_yoga_001",
            "name": "Yoga",
            "category": "PHYSICAL",
            "default_duration_minutes": 20,
        },
        headers=auth_headers,
    )
    logged_at = datetime(2026, 5, 12, 10, 30, tzinfo=timezone.utc).isoformat()
    session = await client.post(
        "/api/v1/leisure/sessions",
        json={
            "id": "ses_yoga_001",
            "activity_id": "act_yoga_001",
            "category": "PHYSICAL",
            "duration_minutes": 20,
            "logged_at": logged_at,
            "source": "TIMER",
        },
        headers=auth_headers,
    )
    assert session.status_code == 201, session.text

    listing = await client.get(
        "/api/v1/leisure/activities", headers=auth_headers
    )
    rows = listing.json()
    assert len(rows) == 1
    # The denormalized last_completed_at should equal the session's logged_at.
    assert rows[0]["last_completed_at"] is not None


@pytest.mark.asyncio
async def test_create_session_rejects_cross_user_activity(
    client: AsyncClient, auth_headers: dict
):
    # The activity belongs to a different (non-existent) ID — should 400.
    logged_at = datetime(2026, 5, 12, 10, 30, tzinfo=timezone.utc).isoformat()
    session = await client.post(
        "/api/v1/leisure/sessions",
        json={
            "id": "ses_orphan_001",
            "activity_id": "act_nope_001",
            "category": "PHYSICAL",
            "duration_minutes": 15,
            "logged_at": logged_at,
            "source": "MANUAL",
        },
        headers=auth_headers,
    )
    assert session.status_code == 400


@pytest.mark.asyncio
async def test_create_session_allows_null_activity_for_free_text(
    client: AsyncClient, auth_headers: dict
):
    logged_at = datetime(2026, 5, 12, 10, 30, tzinfo=timezone.utc).isoformat()
    session = await client.post(
        "/api/v1/leisure/sessions",
        json={
            "id": "ses_freetext_001",
            "activity_id": None,
            "category": "PASSIVE",
            "duration_minutes": 45,
            "logged_at": logged_at,
            "source": "MANUAL",
        },
        headers=auth_headers,
    )
    assert session.status_code == 201, session.text
    body = session.json()
    assert body["activity_id"] is None


@pytest.mark.asyncio
async def test_get_settings_creates_default_singleton(
    client: AsyncClient, auth_headers: dict
):
    resp = await client.get(
        "/api/v1/leisure/settings", headers=auth_headers
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    # Q1 lock: refresh_limit IS the feature, default 3 for all tiers.
    assert body["refresh_limit"] == 3
    # Q5 lock: all four categories enabled by default.
    assert set(body["enabled_categories"]) == {
        "PHYSICAL",
        "SOCIAL",
        "CREATIVE",
        "PASSIVE",
    }
    assert body["daily_target_minutes"] == 60
    assert body["enforcement_mode"] == "SOFT"
    assert body["pending_enforcement_mode"] is None


@pytest.mark.asyncio
async def test_patch_settings_target_minutes_is_free(
    client: AsyncClient, auth_headers: dict
):
    resp = await client.patch(
        "/api/v1/leisure/settings",
        json={"daily_target_minutes": 90, "weekend_target_minutes": 120},
        headers=auth_headers,
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["daily_target_minutes"] == 90
    assert body["weekend_target_minutes"] == 120


@pytest.mark.asyncio
async def test_patch_settings_refresh_limit_is_free(
    client: AsyncClient, auth_headers: dict
):
    """Q1 lock — refresh limit is NOT tier-gated."""
    resp = await client.patch(
        "/api/v1/leisure/settings",
        json={"refresh_limit": 5},
        headers=auth_headers,
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["refresh_limit"] == 5


@pytest.mark.asyncio
async def test_patch_settings_medium_enforcement_rejected_for_free(
    client: AsyncClient, auth_headers: dict
):
    resp = await client.patch(
        "/api/v1/leisure/settings",
        json={"enforcement_mode": "MEDIUM"},
        headers=auth_headers,
    )
    assert resp.status_code == 402, resp.text


@pytest.mark.asyncio
async def test_patch_settings_hard_enforcement_rejected_for_free(
    client: AsyncClient, auth_headers: dict
):
    resp = await client.patch(
        "/api/v1/leisure/settings",
        json={"enforcement_mode": "HARD"},
        headers=auth_headers,
    )
    assert resp.status_code == 402


@pytest.mark.asyncio
async def test_patch_settings_medium_enforcement_accepted_for_pro(
    client: AsyncClient, pro_auth_headers: dict
):
    resp = await client.patch(
        "/api/v1/leisure/settings",
        json={"enforcement_mode": "MEDIUM"},
        headers=pro_auth_headers,
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    # Deferred-setting pattern: change goes to pending, NOT active.
    assert body["enforcement_mode"] == "SOFT"
    assert body["pending_enforcement_mode"] == "MEDIUM"
    assert body["pending_enforcement_effective_date"] is not None


@pytest.mark.asyncio
async def test_patch_settings_promote_pending_enforcement(
    client: AsyncClient, pro_auth_headers: dict
):
    # First, set the pending mode.
    await client.patch(
        "/api/v1/leisure/settings",
        json={"enforcement_mode": "HARD"},
        headers=pro_auth_headers,
    )
    # Then promote (simulating the day-reset worker firing).
    resp = await client.patch(
        "/api/v1/leisure/settings",
        json={"promote_pending_enforcement": True},
        headers=pro_auth_headers,
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["enforcement_mode"] == "HARD"
    assert body["pending_enforcement_mode"] is None
    assert body["pending_enforcement_effective_date"] is None


@pytest.mark.asyncio
async def test_patch_settings_empty_enabled_categories_rejected(
    client: AsyncClient, auth_headers: dict
):
    """Random-pull would dead-state with no enabled categories."""
    resp = await client.patch(
        "/api/v1/leisure/settings",
        json={"enabled_categories": []},
        headers=auth_headers,
    )
    assert resp.status_code == 422
