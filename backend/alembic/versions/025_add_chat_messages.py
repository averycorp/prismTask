"""Add ``chat_messages`` for D11 E.3 chat conversation persistence.

Per ``docs/audits/D11_E3_CHAT_PERSISTENCE_AUDIT.md`` (Item 1 = Shape A,
Item 2). One table, server-authored writes from inside the existing
``/api/v1/ai/chat`` handler. ``user_id`` cascades on user delete so
account deletion sweeps chat history.

Reframed mid-audit from the original D11 filing: dropped the proposed
Firestore tri-layer because BackendSyncService is now the entity-data
sync path; chat persists Postgres + Room only.

Revision ID: 025
Revises: 024
Create Date: 2026-05-08
"""

from alembic import op
import sqlalchemy as sa


revision = "025"
down_revision = "024"
branch_labels = None
depends_on = None


_AWARE = sa.DateTime(timezone=True)


def upgrade() -> None:
    op.create_table(
        "chat_messages",
        sa.Column("id", sa.String(64), primary_key=True),
        sa.Column(
            "user_id", sa.Integer,
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("conversation_id", sa.String(128), nullable=False),
        sa.Column("role", sa.String(16), nullable=False),
        sa.Column("content", sa.Text, nullable=False),
        sa.Column("actions", sa.JSON, nullable=True),
        sa.Column("task_context_snapshot", sa.JSON, nullable=True),
        sa.Column("tokens_input", sa.Integer, nullable=True),
        sa.Column("tokens_output", sa.Integer, nullable=True),
        sa.Column(
            "created_at", _AWARE,
            server_default=sa.func.now(), nullable=False,
        ),
    )
    op.create_index(
        "ix_chat_messages_user_conv_created",
        "chat_messages",
        ["user_id", "conversation_id", "created_at"],
    )
    op.create_index(
        "ix_chat_messages_user_created",
        "chat_messages",
        ["user_id", "created_at"],
    )


def downgrade() -> None:
    op.drop_index("ix_chat_messages_user_created", table_name="chat_messages")
    op.drop_index("ix_chat_messages_user_conv_created", table_name="chat_messages")
    op.drop_table("chat_messages")
