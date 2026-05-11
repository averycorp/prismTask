"""Add ``user_ai_preferences`` for AI-memory bundle.

The AI Coach emits ``remember_preference`` / ``forget_preference`` tool
calls during chat; the chat handler persists each user's stored
preferences here (max 15 per user, enforced in the handler). Surfaced
to the Settings UI via ``/api/v1/ai/memory`` CRUD endpoints so users
can audit and edit what the AI has learned. ``user_id`` cascades on
user delete so account deletion sweeps the memory.

Revision ID: 027
Revises: 026
Create Date: 2026-05-11
"""

from alembic import op
import sqlalchemy as sa


revision = "027"
down_revision = "026"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "user_ai_preferences",
        sa.Column("id", sa.String(64), primary_key=True),
        sa.Column(
            "user_id", sa.Integer,
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("preference_text", sa.Text, nullable=False),
        sa.Column("source_message_id", sa.String(64), nullable=True),
        sa.Column(
            "created_at", sa.DateTime(timezone=True),
            server_default=sa.func.now(), nullable=False,
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True),
            server_default=sa.func.now(), nullable=False,
        ),
    )
    op.create_index(
        "ix_user_ai_preferences_user_updated",
        "user_ai_preferences",
        ["user_id", "updated_at"],
    )


def downgrade() -> None:
    op.drop_index(
        "ix_user_ai_preferences_user_updated",
        table_name="user_ai_preferences",
    )
    op.drop_table("user_ai_preferences")
