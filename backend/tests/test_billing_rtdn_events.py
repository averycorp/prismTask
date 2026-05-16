"""Tests for the ``rtdn_events`` audit table introduced by alembic
migration 022.

Scope (billing-schema option B):
- Schema round-trip via ``Base.metadata.create_all`` (the conftest
  fixture already does this; we just verify the new table is in the
  metadata and that all expected columns exist).
- ``pubsub_message_id`` UNIQUE constraint rejects duplicates — this is
  the load-bearing idempotency rule for Pub/Sub redelivery.
- Enum values round-trip through SQLAlchemy as the lowercase string
  values, not the Python attribute names (memory #13 — values_callable
  pattern).
"""
from __future__ import annotations

import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import RtdnEvent, RtdnEventType
from tests.conftest import TestSessionLocal


@pytest.mark.asyncio
async def test_rtdn_events_table_is_in_metadata():
    from app.models import Base

    assert "rtdn_events" in Base.metadata.tables
    cols = Base.metadata.tables["rtdn_events"].columns
    assert "pubsub_message_id" in cols
    assert "event_type" in cols
    assert "purchase_token" in cols
    assert "product_id" in cols
    assert "raw_payload" in cols
    assert "processed" in cols
    assert "processing_error" in cols
    assert "received_at" in cols


@pytest.mark.asyncio
async def test_pubsub_message_id_is_unique():
    """Pub/Sub may redeliver the same message; the second insert must
    conflict on the UNIQUE constraint, not silently insert a duplicate.
    """
    session: AsyncSession
    async with TestSessionLocal() as session:
        first = RtdnEvent(
            pubsub_message_id="msg-abc-123",
            event_type=RtdnEventType.SUBSCRIPTION_RENEWED,
            purchase_token="tok-xyz",
            product_id="prismtask_pro_monthly",
            raw_payload='{"version":"1.0","subscriptionNotification":{}}',
        )
        session.add(first)
        await session.commit()

    async with TestSessionLocal() as session:
        dup = RtdnEvent(
            pubsub_message_id="msg-abc-123",
            event_type=RtdnEventType.SUBSCRIPTION_RENEWED,
            raw_payload="{}",
        )
        session.add(dup)
        with pytest.raises(IntegrityError):
            await session.commit()


@pytest.mark.asyncio
async def test_enum_round_trip_uses_lowercase_value():
    """``values_callable`` must expose the lowercase string values to
    the DB layer; reading back the row should yield the matching
    ``RtdnEventType`` member.
    """
    async with TestSessionLocal() as session:
        ev = RtdnEvent(
            pubsub_message_id="msg-enum-check",
            event_type=RtdnEventType.SUBSCRIPTION_PURCHASED,
            raw_payload="{}",
        )
        session.add(ev)
        await session.commit()
        ev_id = ev.id

    async with TestSessionLocal() as session:
        result = await session.execute(
            select(RtdnEvent).where(RtdnEvent.id == ev_id)
        )
        row = result.scalar_one()
        assert row.event_type is RtdnEventType.SUBSCRIPTION_PURCHASED
        assert row.event_type.value == "subscription_purchased"


@pytest.mark.asyncio
async def test_processed_defaults_false_and_nullable_fields_optional():
    """``processed`` defaults to False; ``purchase_token`` and
    ``product_id`` may be omitted (test events from Google's console
    arrive without them).
    """
    async with TestSessionLocal() as session:
        ev = RtdnEvent(
            pubsub_message_id="msg-test-event",
            event_type=RtdnEventType.TEST,
            raw_payload='{"testNotification":{"version":"1.0"}}',
        )
        session.add(ev)
        await session.commit()

        result = await session.execute(
            select(RtdnEvent).where(RtdnEvent.pubsub_message_id == "msg-test-event")
        )
        row = result.scalar_one()
        assert row.processed is False
        assert row.purchase_token is None
        assert row.product_id is None
        assert row.processing_error is None
        assert row.received_at is not None


@pytest.mark.asyncio
async def test_rtdn_event_type_covers_googles_published_set():
    """Sanity check: the enum lists every event type Google currently
    publishes (per the audit doc). If Google adds a new type, this
    list must be updated alongside the alembic enum in migration 022.
    """
    expected = {
        "subscription_recovered",
        "subscription_renewed",
        "subscription_canceled",
        "subscription_purchased",
        "subscription_on_hold",
        "subscription_in_grace_period",
        "subscription_restarted",
        "subscription_price_change_confirmed",
        "subscription_deferred",
        "subscription_paused",
        "subscription_pause_schedule_changed",
        "subscription_revoked",
        "subscription_expired",
        "subscription_pending_purchase_canceled",
        "voided_purchase",
        "test",
        "unknown",
    }
    actual = {member.value for member in RtdnEventType}
    assert actual == expected
