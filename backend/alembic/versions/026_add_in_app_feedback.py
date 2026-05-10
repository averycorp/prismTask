"""Add ``in_app_feedback`` for E2 in-app ratings bundle.

Per ``docs/audits/E2_IN_APP_RATINGS_AUDIT.md`` § Item 5. Greenfield
table; written by ``POST /api/v1/feedback/in-app``. ``user_id`` cascades
on user delete so account deletion sweeps feedback.

Revision ID: 026
Revises: 025
Create Date: 2026-05-10
"""

from alembic import op
import sqlalchemy as sa


revision = "026"
down_revision = "025"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "in_app_feedback",
        sa.Column("id", sa.Integer, primary_key=True, autoincrement=True),
        sa.Column(
            "user_id", sa.Integer,
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("sentiment", sa.String(32), nullable=False),
        sa.Column("rating", sa.SmallInteger, nullable=True),
        sa.Column("free_text", sa.Text, nullable=True),
        sa.Column("client_timestamp", sa.BigInteger, nullable=True),
        sa.Column(
            "created_at", sa.DateTime(timezone=True),
            server_default=sa.func.now(), nullable=False,
        ),
    )
    op.create_index(
        "ix_in_app_feedback_user_created",
        "in_app_feedback",
        ["user_id", "created_at"],
    )


def downgrade() -> None:
    op.drop_index("ix_in_app_feedback_user_created", table_name="in_app_feedback")
    op.drop_table("in_app_feedback")
