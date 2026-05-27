"""Add Dormancy Re-Entry columns to ``tasks``.

Three nullable columns mirroring the Android/Firestore task shape so the
backend stays at parity:

- ``last_engagement_at`` (timestamptz): when the user last engaged via a
  session end. NULL = never engaged (not dormant).
- ``re_entry_context`` (varchar(280)): optional "where you stopped" note.
- ``dormancy_threshold_days_override`` (int): per-task override of the global
  threshold. NULL = inherit global default.

Canonical task state is Firestore/Android; these columns exist for backend
parity only. No backfill — NULL defaults are correct.

Revision ID: 031
Revises: 030
Create Date: 2026-05-27
"""

from alembic import op
import sqlalchemy as sa


revision = "031"
down_revision = "030"
branch_labels = None
depends_on = None


def upgrade() -> None:
    conn = op.get_bind()
    inspector = sa.inspect(conn)
    columns = [col["name"] for col in inspector.get_columns("tasks")]
    if "last_engagement_at" not in columns:
        op.add_column(
            "tasks",
            sa.Column("last_engagement_at", sa.DateTime(timezone=True), nullable=True),
        )
    if "re_entry_context" not in columns:
        op.add_column(
            "tasks",
            sa.Column("re_entry_context", sa.String(280), nullable=True),
        )
    if "dormancy_threshold_days_override" not in columns:
        op.add_column(
            "tasks",
            sa.Column("dormancy_threshold_days_override", sa.Integer(), nullable=True),
        )


def downgrade() -> None:
    conn = op.get_bind()
    inspector = sa.inspect(conn)
    columns = [col["name"] for col in inspector.get_columns("tasks")]
    if "dormancy_threshold_days_override" in columns:
        op.drop_column("tasks", "dormancy_threshold_days_override")
    if "re_entry_context" in columns:
        op.drop_column("tasks", "re_entry_context")
    if "last_engagement_at" in columns:
        op.drop_column("tasks", "last_engagement_at")
