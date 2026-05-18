"""Add per-habit streak-forgiveness override columns

Four nullable Integer columns on ``habits`` that let users override the
three global streak-forgiveness knobs on a per-habit basis. ``NULL``
means "inherit the user-level global"; the Android client maps that to
its on-device ``-1`` sentinel both ways. ``forgiveness_enabled`` carries
a tri-state (``NULL`` inherit, ``0`` force-off, ``1`` force-on) because
the global forgiveness toggle is independent of the slider.

No backfill is required — every existing row remains ``NULL`` and
therefore inherits the global defaults, preserving prior behavior.

Revision ID: 029
Revises: 028
Create Date: 2026-05-18
"""

from alembic import op
import sqlalchemy as sa


revision = "029"
down_revision = "028"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "habits",
        sa.Column("streak_max_missed_days", sa.Integer, nullable=True),
    )
    op.add_column(
        "habits",
        sa.Column("forgiveness_enabled", sa.Integer, nullable=True),
    )
    op.add_column(
        "habits",
        sa.Column("forgiveness_allowed_misses", sa.Integer, nullable=True),
    )
    op.add_column(
        "habits",
        sa.Column("forgiveness_grace_period_days", sa.Integer, nullable=True),
    )


def downgrade() -> None:
    op.drop_column("habits", "forgiveness_grace_period_days")
    op.drop_column("habits", "forgiveness_allowed_misses")
    op.drop_column("habits", "forgiveness_enabled")
    op.drop_column("habits", "streak_max_missed_days")
