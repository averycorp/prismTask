"""Add ``rtdn_events`` append-only audit table for Google Play
Real-Time Developer Notifications.

This is option B of the billing-schema audit (the smallest delta that
earns its keep): a single append-only table that captures every Pub/Sub
Push payload from Google's RTDN feed, keyed by the Pub/Sub message id
for idempotency on redelivery.

The existing ``users.tier`` column + ``services/billing.py`` validator
remain the entitlement source of truth — this migration does not
restructure them. The RTDN events table is here to give us replay,
audit, and Pub/Sub redelivery idempotency without re-architecting the
buy-and-elevate flow that already works.

Revision ID: 022
Revises: 021
Create Date: 2026-04-28
"""

from alembic import op
import sqlalchemy as sa


revision = "022"
down_revision = "021"
branch_labels = None
depends_on = None


_AWARE = sa.DateTime(timezone=True)

# Matches Google Play's published RTDN notification types. Lowercase
# snake_case to match the codebase's existing enum convention. The PG
# enum type name (``rtdn_event_type``) is referenced by the model in
# ``app/models.py``; if either side changes, both must change.
_RTDN_EVENT_TYPES = (
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
)


def upgrade() -> None:
    # Let ``op.create_table`` auto-emit ``CREATE TYPE`` for the inline
    # Enum (matches the convention used by migrations 001/002). The
    # earlier "manual create + create_type=False on the column" pattern
    # tripped over a duplicate ``CREATE TYPE`` because the column-level
    # Enum's ``_on_table_create`` event fires regardless of
    # ``create_type=False`` once the type's name has been bound — see
    # the asyncpg DuplicateObjectError on the prior CI run.
    op.create_table(
        "rtdn_events",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column(
            "pubsub_message_id", sa.String(128),
            nullable=False, unique=True,
        ),
        sa.Column(
            "event_type",
            sa.Enum(*_RTDN_EVENT_TYPES, name="rtdn_event_type"),
            nullable=False,
        ),
        sa.Column("purchase_token", sa.String(512), nullable=True, index=True),
        sa.Column("product_id", sa.String(128), nullable=True),
        sa.Column("raw_payload", sa.Text, nullable=False),
        sa.Column("processed", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.Column("processing_error", sa.Text, nullable=True),
        sa.Column(
            "received_at", _AWARE,
            server_default=sa.func.now(), nullable=False, index=True,
        ),
    )
    op.create_index(
        "ix_rtdn_events_token_received",
        "rtdn_events", ["purchase_token", "received_at"],
    )


def downgrade() -> None:
    op.drop_index("ix_rtdn_events_token_received", table_name="rtdn_events")
    op.drop_table("rtdn_events")

    # ``op.drop_table`` does not drop the PG enum type; do it explicitly
    # with ``checkfirst=True`` so re-running downgrade is idempotent.
    sa.Enum(*_RTDN_EVENT_TYPES, name="rtdn_event_type").drop(
        op.get_bind(), checkfirst=True
    )
