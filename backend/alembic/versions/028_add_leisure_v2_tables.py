"""Add Leisure Budget v2.0 tables.

Three new tables that power the Leisure Budget feature:

* ``leisure_activities`` — user-owned pool of activities, one row per
  (user, activity-name). Categories restricted to
  PHYSICAL/SOCIAL/CREATIVE/PASSIVE. ``last_completed_at`` is
  denormalized for the recency-weighted random-pull algorithm so the
  pull query doesn't have to join through ``leisure_sessions``.
* ``leisure_sessions`` — completed leisure sessions. Each row carries
  category + duration + source (TIMER/MANUAL). ``activity_id`` is
  nullable for free-text entries that haven't been promoted to the
  pool yet (in practice the client auto-adds, so this is mostly for
  legacy / orphan rows).
* ``leisure_settings`` — singleton per user. ``pending_enforcement_mode``
  + ``pending_enforcement_effective_date`` implement the
  "enforcement-mode changes take effect next day" pattern (mirrors the
  medication reminder-profile deferred-setting shape).

Revision ID: 028
Revises: 027
Create Date: 2026-05-12
"""

from alembic import op
import sqlalchemy as sa


revision = "028"
down_revision = "027"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "leisure_activities",
        sa.Column("id", sa.String(64), primary_key=True),
        sa.Column(
            "user_id", sa.Integer,
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("name", sa.String(120), nullable=False),
        sa.Column("category", sa.String(16), nullable=False),
        sa.Column("default_duration_minutes", sa.Integer, nullable=True),
        sa.Column("enabled", sa.Boolean, nullable=False, server_default=sa.text("true")),
        sa.Column(
            "created_at", sa.DateTime(timezone=True),
            server_default=sa.func.now(), nullable=False,
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True),
            server_default=sa.func.now(), nullable=False,
        ),
        sa.Column("last_completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint(
            "category IN ('PHYSICAL','SOCIAL','CREATIVE','PASSIVE')",
            name="ck_leisure_activity_category",
        ),
    )
    op.create_index(
        "ix_leisure_activities_user_enabled",
        "leisure_activities",
        ["user_id", "enabled"],
    )
    op.create_index(
        "ix_leisure_activities_user_category",
        "leisure_activities",
        ["user_id", "category"],
    )

    op.create_table(
        "leisure_sessions",
        sa.Column("id", sa.String(64), primary_key=True),
        sa.Column(
            "user_id", sa.Integer,
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "activity_id", sa.String(64),
            sa.ForeignKey("leisure_activities.id", ondelete="SET NULL"),
            nullable=True,
        ),
        sa.Column("category", sa.String(16), nullable=False),
        sa.Column("duration_minutes", sa.Integer, nullable=False),
        sa.Column("logged_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("source", sa.String(8), nullable=False),
        sa.Column(
            "created_at", sa.DateTime(timezone=True),
            server_default=sa.func.now(), nullable=False,
        ),
        sa.CheckConstraint(
            "category IN ('PHYSICAL','SOCIAL','CREATIVE','PASSIVE')",
            name="ck_leisure_session_category",
        ),
        sa.CheckConstraint(
            "source IN ('TIMER','MANUAL')",
            name="ck_leisure_session_source",
        ),
        sa.CheckConstraint(
            "duration_minutes > 0",
            name="ck_leisure_session_duration_positive",
        ),
    )
    op.create_index(
        "ix_leisure_sessions_user_logged",
        "leisure_sessions",
        ["user_id", "logged_at"],
    )
    op.create_index(
        "ix_leisure_sessions_activity",
        "leisure_sessions",
        ["activity_id"],
    )

    op.create_table(
        "leisure_settings",
        sa.Column(
            "user_id", sa.Integer,
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            primary_key=True,
        ),
        sa.Column(
            "daily_target_minutes", sa.Integer,
            nullable=False, server_default=sa.text("60"),
        ),
        sa.Column("weekend_target_minutes", sa.Integer, nullable=True),
        sa.Column(
            "enforcement_mode", sa.String(8),
            nullable=False, server_default=sa.text("'SOFT'"),
        ),
        sa.Column(
            "refresh_limit", sa.Integer,
            nullable=False, server_default=sa.text("3"),
        ),
        sa.Column(
            "enabled_categories", sa.Text,
            nullable=False,
            server_default=sa.text("'[\"PHYSICAL\",\"SOCIAL\",\"CREATIVE\",\"PASSIVE\"]'"),
        ),
        sa.Column("pending_enforcement_mode", sa.String(8), nullable=True),
        sa.Column("pending_enforcement_effective_date", sa.Date, nullable=True),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True),
            server_default=sa.func.now(), nullable=False,
        ),
        sa.CheckConstraint(
            "enforcement_mode IN ('SOFT','MEDIUM','HARD')",
            name="ck_leisure_settings_enforcement",
        ),
        sa.CheckConstraint(
            "pending_enforcement_mode IS NULL OR "
            "pending_enforcement_mode IN ('SOFT','MEDIUM','HARD')",
            name="ck_leisure_settings_pending_enforcement",
        ),
        sa.CheckConstraint(
            "refresh_limit BETWEEN 0 AND 10",
            name="ck_leisure_settings_refresh_limit_range",
        ),
        sa.CheckConstraint(
            "daily_target_minutes BETWEEN 0 AND 1440",
            name="ck_leisure_settings_daily_target_range",
        ),
    )


def downgrade() -> None:
    op.drop_table("leisure_settings")
    op.drop_index(
        "ix_leisure_sessions_activity",
        table_name="leisure_sessions",
    )
    op.drop_index(
        "ix_leisure_sessions_user_logged",
        table_name="leisure_sessions",
    )
    op.drop_table("leisure_sessions")
    op.drop_index(
        "ix_leisure_activities_user_category",
        table_name="leisure_activities",
    )
    op.drop_index(
        "ix_leisure_activities_user_enabled",
        table_name="leisure_activities",
    )
    op.drop_table("leisure_activities")
