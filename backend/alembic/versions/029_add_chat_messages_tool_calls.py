"""Add ``chat_messages.tool_calls`` for AI Assistant Phase 1 tool-use loop.

Stores a list of ``[{name, input, result_summary}, ...]`` on assistant
rows whenever the Phase 1 read-tool loop fires. Nullable so existing rows
and write-only / no-tool turns remain valid. Per
``docs/superpowers/specs/2026-05-18-ai-personal-assistant-design.md``.

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
        "chat_messages",
        sa.Column("tool_calls", sa.JSON, nullable=True),
    )


def downgrade() -> None:
    op.drop_column("chat_messages", "tool_calls")
