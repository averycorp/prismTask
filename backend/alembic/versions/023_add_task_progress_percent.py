"""Add ``progress_percent`` to tasks for fractional progress.

PR-4 of the PrismTask-timeline-class scope.

Pure-additive ``INTEGER NULL`` column. NULL means "binary" — the
existing ``status`` column is the source of truth and the burndown
treats the task as 100% if status == DONE else 0%. Non-NULL values are
``0..100`` and only authored on tasks under a project (the smallest
blast radius option).

Revision ID: 023
Revises: 022
Create Date: 2026-05-03
"""

from alembic import op
import sqlalchemy as sa


revision = "023"
down_revision = "022"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "tasks",
        sa.Column("progress_percent", sa.Integer, nullable=True),
    )


def downgrade() -> None:
    op.drop_column("tasks", "progress_percent")
