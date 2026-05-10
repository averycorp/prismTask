import enum
import secrets
from datetime import datetime, timezone

from sqlalchemy import (
    JSON,
    BigInteger,
    Boolean,
    CheckConstraint,
    Column,
    Date,
    DateTime,
    Enum,
    Float,
    ForeignKey,
    Integer,
    SmallInteger,
    String,
    Text,
    Time,
    UniqueConstraint,
    func,
)
from sqlalchemy.orm import DeclarativeBase, relationship


class Base(DeclarativeBase):
    pass


# --- Enums ---


class GoalStatus(str, enum.Enum):
    ACTIVE = "active"
    ACHIEVED = "achieved"
    ARCHIVED = "archived"


class ProjectStatus(str, enum.Enum):
    ACTIVE = "active"
    COMPLETED = "completed"
    ON_HOLD = "on_hold"
    ARCHIVED = "archived"


class TaskStatus(str, enum.Enum):
    TODO = "todo"
    IN_PROGRESS = "in_progress"
    DONE = "done"
    CANCELLED = "cancelled"


class TaskPriority(int, enum.Enum):
    URGENT = 1
    HIGH = 2
    MEDIUM = 3
    LOW = 4


class HabitFrequency(str, enum.Enum):
    DAILY = "daily"
    WEEKLY = "weekly"


class IntegrationSource(str, enum.Enum):
    GMAIL = "gmail"
    SLACK = "slack"
    CALENDAR = "calendar"
    WEBHOOK = "webhook"


class SuggestionStatus(str, enum.Enum):
    PENDING = "pending"
    ACCEPTED = "accepted"
    REJECTED = "rejected"
    IGNORED = "ignored"


class RtdnEventType(str, enum.Enum):
    SUBSCRIPTION_RECOVERED = "subscription_recovered"
    SUBSCRIPTION_RENEWED = "subscription_renewed"
    SUBSCRIPTION_CANCELED = "subscription_canceled"
    SUBSCRIPTION_PURCHASED = "subscription_purchased"
    SUBSCRIPTION_ON_HOLD = "subscription_on_hold"
    SUBSCRIPTION_IN_GRACE_PERIOD = "subscription_in_grace_period"
    SUBSCRIPTION_RESTARTED = "subscription_restarted"
    SUBSCRIPTION_PRICE_CHANGE_CONFIRMED = "subscription_price_change_confirmed"
    SUBSCRIPTION_DEFERRED = "subscription_deferred"
    SUBSCRIPTION_PAUSED = "subscription_paused"
    SUBSCRIPTION_PAUSE_SCHEDULE_CHANGED = "subscription_pause_schedule_changed"
    SUBSCRIPTION_REVOKED = "subscription_revoked"
    SUBSCRIPTION_EXPIRED = "subscription_expired"
    SUBSCRIPTION_PENDING_PURCHASE_CANCELED = "subscription_pending_purchase_canceled"
    VOIDED_PURCHASE = "voided_purchase"
    TEST = "test"
    UNKNOWN = "unknown"


# --- Models ---


class User(Base):
    __tablename__ = "users"

    id = Column(Integer, primary_key=True)
    email = Column(String(255), unique=True, nullable=False, index=True)
    hashed_password = Column(String(255), nullable=False)
    name = Column(String(255), nullable=False)
    display_name = Column(String(255), nullable=True)
    avatar_url = Column(String(500), nullable=True)
    firebase_uid = Column(String(255), unique=True, nullable=True)
    tier = Column(String(20), nullable=False, server_default="FREE")
    is_admin = Column(Boolean, nullable=False, default=False, server_default="0")
    deletion_pending_at = Column(DateTime(timezone=True), nullable=True)
    deletion_scheduled_for = Column(DateTime(timezone=True), nullable=True, index=True)
    deletion_initiated_from = Column(String(20), nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=lambda: datetime.now(timezone.utc))

    @property
    def effective_tier(self) -> str:
        """Return PRO for admins, otherwise the stored tier.

        Beta-code Pro entitlement is NOT computed here — that requires a
        DB lookup against ``beta_code_redemptions`` and is resolved in
        the ``/auth/me`` handler via
        ``app.services.beta_codes.has_active_beta_pro``. Keeping this
        property sync + DB-free preserves its use in eager serialization
        contexts (e.g. test fixtures that read ``user.effective_tier``
        without a session).
        """
        if self.is_admin:
            return "PRO"
        return self.tier or "FREE"

    goals = relationship("Goal", back_populates="user", cascade="all, delete-orphan")
    tags = relationship("Tag", back_populates="user", cascade="all, delete-orphan")
    habits = relationship("Habit", back_populates="user", cascade="all, delete-orphan")
    templates = relationship("TaskTemplate", back_populates="user", cascade="all, delete-orphan")
    project_memberships = relationship("ProjectMember", back_populates="user")


class Goal(Base):
    __tablename__ = "goals"

    id = Column(Integer, primary_key=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    title = Column(String(255), nullable=False)
    description = Column(Text, nullable=True)
    status = Column(Enum(GoalStatus, values_callable=lambda x: [e.value for e in x]), default=GoalStatus.ACTIVE, nullable=False)
    target_date = Column(Date, nullable=True)
    color = Column(String(7), nullable=True)
    sort_order = Column(Integer, default=0)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=lambda: datetime.now(timezone.utc))

    user = relationship("User", back_populates="goals")
    projects = relationship("Project", back_populates="goal", cascade="all, delete-orphan")


class Project(Base):
    __tablename__ = "projects"

    id = Column(Integer, primary_key=True)
    goal_id = Column(Integer, ForeignKey("goals.id", ondelete="CASCADE"), nullable=False, index=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    title = Column(String(255), nullable=False)
    description = Column(Text, nullable=True)
    status = Column(Enum(ProjectStatus, values_callable=lambda x: [e.value for e in x]), default=ProjectStatus.ACTIVE, nullable=False)
    due_date = Column(Date, nullable=True)
    sort_order = Column(Integer, default=0)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=lambda: datetime.now(timezone.utc))

    goal = relationship("Goal", back_populates="projects")
    user = relationship("User")
    tasks = relationship("Task", back_populates="project", cascade="all, delete-orphan")
    members = relationship("ProjectMember", back_populates="project", cascade="all, delete-orphan")


class Tag(Base):
    __tablename__ = "tags"

    id = Column(Integer, primary_key=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    name = Column(String(100), nullable=False)
    color = Column(String(7), nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())

    user = relationship("User", back_populates="tags")
    task_tags = relationship("TaskTag", back_populates="tag", cascade="all, delete-orphan")


class Task(Base):
    __tablename__ = "tasks"
    __table_args__ = (
        CheckConstraint("depth >= 0 AND depth <= 1", name="check_depth_range"),
    )

    id = Column(Integer, primary_key=True)
    project_id = Column(Integer, ForeignKey("projects.id", ondelete="CASCADE"), nullable=False, index=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    parent_id = Column(Integer, ForeignKey("tasks.id", ondelete="CASCADE"), nullable=True, index=True)
    title = Column(String(500), nullable=False)
    description = Column(Text, nullable=True)
    notes = Column(Text, nullable=True)
    status = Column(Enum(TaskStatus, values_callable=lambda x: [e.value for e in x]), default=TaskStatus.TODO, nullable=False)
    priority = Column(Integer, default=TaskPriority.MEDIUM)
    due_date = Column(Date, nullable=True)
    due_time = Column(Time, nullable=True)
    planned_date = Column(Date, nullable=True)
    completed_at = Column(DateTime(timezone=True), nullable=True)
    urgency_score = Column(Float, default=0.0)
    recurrence_json = Column(Text, nullable=True)
    eisenhower_quadrant = Column(String(2), nullable=True)  # Q1, Q2, Q3, Q4
    eisenhower_updated_at = Column(DateTime(timezone=True), nullable=True)
    estimated_duration = Column(Integer, nullable=True)  # minutes
    actual_duration = Column(Integer, nullable=True)  # minutes
    sort_order = Column(Integer, default=0)
    depth = Column(Integer, default=0)
    # Fractional progress in 0..100. NULL means "binary" — the burndown
    # treats this task as 100 if status == DONE else 0. PrismTask-timeline-
    # class scope, PR-4 (audit P9 option a).
    progress_percent = Column(Integer, nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=lambda: datetime.now(timezone.utc))

    project = relationship("Project", back_populates="tasks")
    user = relationship("User")
    parent = relationship("Task", remote_side=[id], back_populates="subtasks")
    subtasks = relationship("Task", back_populates="parent", cascade="all, delete-orphan")
    task_tags = relationship("TaskTag", back_populates="task", cascade="all, delete-orphan")
    attachments = relationship("Attachment", back_populates="task", cascade="all, delete-orphan")
    comments = relationship("TaskComment", back_populates="task", cascade="all, delete-orphan")


class TaskTag(Base):
    __tablename__ = "task_tags"
    __table_args__ = (
        UniqueConstraint("task_id", "tag_id", name="uq_task_tag"),
    )

    id = Column(Integer, primary_key=True)
    task_id = Column(Integer, ForeignKey("tasks.id", ondelete="CASCADE"), nullable=False, index=True)
    tag_id = Column(Integer, ForeignKey("tags.id", ondelete="CASCADE"), nullable=False, index=True)

    task = relationship("Task", back_populates="task_tags")
    tag = relationship("Tag", back_populates="task_tags")


class Attachment(Base):
    __tablename__ = "attachments"

    id = Column(Integer, primary_key=True)
    task_id = Column(Integer, ForeignKey("tasks.id", ondelete="CASCADE"), nullable=False, index=True)
    name = Column(String(255), nullable=False)
    uri = Column(Text, nullable=False)
    type = Column(String(50), nullable=False)
    created_at = Column(DateTime(timezone=True), server_default=func.now())

    task = relationship("Task", back_populates="attachments")


class Habit(Base):
    __tablename__ = "habits"

    id = Column(Integer, primary_key=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    name = Column(String(255), nullable=False)
    description = Column(Text, nullable=True)
    icon = Column(String(10), nullable=True)
    color = Column(String(7), nullable=True)
    category = Column(String(100), nullable=True)
    frequency = Column(Enum(HabitFrequency, values_callable=lambda x: [e.value for e in x]), default=HabitFrequency.DAILY, nullable=False)
    target_count = Column(Integer, default=1)
    active_days_json = Column(Text, nullable=True)
    is_active = Column(Boolean, default=True)
    nag_suppression_override_enabled = Column(Boolean, default=False)
    nag_suppression_days_override = Column(Integer, default=-1)
    today_skip_after_complete_days = Column(Integer, default=-1)
    today_skip_before_schedule_days = Column(Integer, default=-1)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=lambda: datetime.now(timezone.utc))

    user = relationship("User", back_populates="habits")
    completions = relationship("HabitCompletion", back_populates="habit", cascade="all, delete-orphan")


class HabitCompletion(Base):
    __tablename__ = "habit_completions"
    __table_args__ = (
        UniqueConstraint("habit_id", "date", name="uq_habit_date"),
    )

    id = Column(Integer, primary_key=True)
    habit_id = Column(Integer, ForeignKey("habits.id", ondelete="CASCADE"), nullable=False, index=True)
    date = Column(Date, nullable=False)
    count = Column(Integer, default=1)
    created_at = Column(DateTime(timezone=True), server_default=func.now())

    habit = relationship("Habit", back_populates="completions")


class TaskTemplate(Base):
    __tablename__ = "task_templates"

    id = Column(Integer, primary_key=True, index=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    name = Column(String(255), nullable=False)
    description = Column(Text, nullable=True)
    icon = Column(String(10), nullable=True)
    category = Column(String(100), nullable=True)

    # Template field values (what gets pre-filled when using the template)
    template_title = Column(String(255), nullable=True)
    template_description = Column(Text, nullable=True)
    template_priority = Column(Integer, nullable=True)
    template_project_id = Column(Integer, ForeignKey("projects.id", ondelete="SET NULL"), nullable=True)
    template_tags_json = Column(Text, nullable=True)
    template_recurrence_json = Column(Text, nullable=True)
    template_duration = Column(Integer, nullable=True)
    template_subtasks_json = Column(Text, nullable=True)

    is_built_in = Column(Boolean, default=False)
    usage_count = Column(Integer, default=0)
    last_used_at = Column(DateTime(timezone=True), nullable=True)

    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=lambda: datetime.now(timezone.utc))

    user = relationship("User", back_populates="templates")
    project = relationship("Project", foreign_keys=[template_project_id])


class ProjectMember(Base):
    __tablename__ = "project_members"
    __table_args__ = (
        UniqueConstraint("project_id", "user_id", name="uq_project_user"),
    )

    id = Column(Integer, primary_key=True, index=True)
    project_id = Column(Integer, ForeignKey("projects.id", ondelete="CASCADE"), nullable=False, index=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    role = Column(String(20), nullable=False, default="editor")  # "owner", "editor", "viewer"
    joined_at = Column(DateTime(timezone=True), server_default=func.now())

    project = relationship("Project", back_populates="members")
    user = relationship("User", back_populates="project_memberships")


class ProjectInvite(Base):
    __tablename__ = "project_invites"

    id = Column(Integer, primary_key=True, index=True)
    project_id = Column(Integer, ForeignKey("projects.id", ondelete="CASCADE"), nullable=False, index=True)
    inviter_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    invitee_email = Column(String(255), nullable=False)
    role = Column(String(20), nullable=False, default="editor")
    token = Column(String(64), unique=True, nullable=False, index=True)
    status = Column(String(20), nullable=False, default="pending")  # pending, accepted, declined, expired
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    expires_at = Column(DateTime(timezone=True), nullable=False)

    project = relationship("Project")
    inviter = relationship("User", foreign_keys=[inviter_id])

    @staticmethod
    def generate_token() -> str:
        return secrets.token_urlsafe(48)


class ActivityLog(Base):
    __tablename__ = "activity_logs"

    id = Column(Integer, primary_key=True, index=True)
    project_id = Column(Integer, ForeignKey("projects.id", ondelete="CASCADE"), nullable=False, index=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    action = Column(String(50), nullable=False)
    entity_type = Column(String(20), nullable=True)  # "task", "member", "comment"
    entity_id = Column(Integer, nullable=True)
    entity_title = Column(String(255), nullable=True)
    metadata_json = Column(Text, nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now(), index=True)

    project = relationship("Project")
    user = relationship("User")


class TaskComment(Base):
    __tablename__ = "task_comments"

    id = Column(Integer, primary_key=True, index=True)
    task_id = Column(Integer, ForeignKey("tasks.id", ondelete="CASCADE"), nullable=False, index=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    content = Column(Text, nullable=False)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=lambda: datetime.now(timezone.utc))

    task = relationship("Task", back_populates="comments")
    user = relationship("User")


class AppRelease(Base):
    __tablename__ = "app_releases"

    id = Column(Integer, primary_key=True)
    version_code = Column(Integer, nullable=False, unique=True)
    version_name = Column(String(50), nullable=False)
    release_notes = Column(Text, nullable=True)
    apk_url = Column(Text, nullable=False)
    apk_size_bytes = Column(Integer, nullable=True)
    sha256 = Column(String(64), nullable=True)
    min_sdk = Column(Integer, default=26)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    is_mandatory = Column(Boolean, default=False)


class SuggestedTask(Base):
    __tablename__ = "suggested_tasks"
    __table_args__ = (
        UniqueConstraint("user_id", "source", "source_id", name="uq_user_source_source_id"),
    )

    id = Column(Integer, primary_key=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    source = Column(
        Enum(IntegrationSource, values_callable=lambda x: [e.value for e in x]),
        nullable=False,
    )
    source_id = Column(String(255), nullable=False)
    source_title = Column(String(500), nullable=False)
    source_url = Column(Text, nullable=True)
    suggested_title = Column(String(500), nullable=False)
    suggested_description = Column(Text, nullable=True)
    suggested_due_date = Column(Date, nullable=True)
    suggested_priority = Column(Integer, nullable=True)
    suggested_project = Column(String(255), nullable=True)
    suggested_tags_json = Column(Text, nullable=True)
    confidence = Column(Float, nullable=False, default=0.0)
    status = Column(
        Enum(SuggestionStatus, values_callable=lambda x: [e.value for e in x]),
        default=SuggestionStatus.PENDING,
        nullable=False,
    )
    extracted_at = Column(DateTime(timezone=True), server_default=func.now())
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=lambda: datetime.now(timezone.utc))

    user = relationship("User")


class IntegrationConfig(Base):
    __tablename__ = "integration_configs"
    __table_args__ = (
        UniqueConstraint("user_id", "source", name="uq_user_integration_source"),
    )

    id = Column(Integer, primary_key=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    source = Column(String(20), nullable=False)
    is_enabled = Column(Boolean, default=False)
    config_json = Column(Text, nullable=True)
    last_scan_at = Column(DateTime(timezone=True), nullable=True)
    scan_frequency_minutes = Column(Integer, default=120)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=lambda: datetime.now(timezone.utc))

    user = relationship("User")


class CalendarSyncSettings(Base):
    """Per-user Google Calendar sync preferences. Mirrors the Android
    `CalendarSyncPreferences` DataStore keys plus server-only fields like
    the per-calendar `syncToken` dict and the last-successful-sync
    timestamp. The backend is the source of truth; Android mirrors on
    settings load and writes on every toggle change.
    """

    __tablename__ = "calendar_sync_settings"

    user_id = Column(
        Integer,
        ForeignKey("users.id", ondelete="CASCADE"),
        primary_key=True,
    )
    enabled = Column(Boolean, nullable=False, default=False)
    direction = Column(String(16), nullable=False, default="both")  # push / pull / both
    frequency = Column(String(16), nullable=False, default="15min")  # realtime / 15min / hourly / manual
    target_calendar_id = Column(String(255), nullable=False, default="primary")
    display_calendar_ids_json = Column(Text, nullable=False, default="[]")
    show_events = Column(Boolean, nullable=False, default=True)
    sync_completed_tasks = Column(Boolean, nullable=False, default=False)
    last_sync_at = Column(DateTime(timezone=True), nullable=True)
    last_sync_token_per_calendar_json = Column(Text, nullable=False, default="{}")
    timezone = Column(String(64), nullable=False, default="UTC")
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=lambda: datetime.now(timezone.utc))

    user = relationship("User")


class BugReportStatus(str, enum.Enum):
    SUBMITTED = "SUBMITTED"
    ACKNOWLEDGED = "ACKNOWLEDGED"
    FIXED = "FIXED"
    WONT_FIX = "WONT_FIX"


class BugReportModel(Base):
    __tablename__ = "bug_reports"

    id = Column(Integer, primary_key=True)
    report_id = Column(String(64), unique=True, nullable=False, index=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="SET NULL"), nullable=True, index=True)
    category = Column(String(50), nullable=False)
    description = Column(Text, nullable=False)
    severity = Column(String(20), nullable=False, default="MINOR")
    steps = Column(Text, nullable=True)
    screenshot_uris = Column(Text, nullable=True)
    device_model = Column(String(255), nullable=True)
    device_manufacturer = Column(String(255), nullable=True)
    android_version = Column(Integer, nullable=True)
    app_version = Column(String(50), nullable=True)
    app_version_code = Column(Integer, nullable=True)
    build_type = Column(String(20), nullable=True)
    user_tier = Column(String(20), nullable=True)
    current_screen = Column(String(255), nullable=True)
    task_count = Column(Integer, nullable=True)
    habit_count = Column(Integer, nullable=True)
    available_ram_mb = Column(Integer, nullable=True)
    free_storage_mb = Column(Integer, nullable=True)
    network_type = Column(String(20), nullable=True)
    battery_percent = Column(Integer, nullable=True)
    is_charging = Column(Boolean, nullable=True)
    status = Column(String(20), nullable=False, default="SUBMITTED")
    admin_notes = Column(Text, nullable=True)
    diagnostic_log = Column(Text, nullable=True)
    submitted_via = Column(String(20), nullable=True, default="backend")
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=lambda: datetime.now(timezone.utc))

    user = relationship("User", foreign_keys=[user_id])


class NdPreferencesModel(Base):
    __tablename__ = "nd_preferences"

    id = Column(Integer, primary_key=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, unique=True, index=True)

    # Top-level mode toggles
    adhd_mode_enabled = Column(Boolean, nullable=False, default=False)
    calm_mode_enabled = Column(Boolean, nullable=False, default=False)

    # Calm Mode sub-settings
    reduce_animations = Column(Boolean, nullable=False, default=False)
    muted_color_palette = Column(Boolean, nullable=False, default=False)
    quiet_mode = Column(Boolean, nullable=False, default=False)
    reduce_haptics = Column(Boolean, nullable=False, default=False)
    soft_contrast = Column(Boolean, nullable=False, default=False)

    # ADHD Mode sub-settings
    task_decomposition_enabled = Column(Boolean, nullable=False, default=False)
    focus_guard_enabled = Column(Boolean, nullable=False, default=False)
    body_doubling_enabled = Column(Boolean, nullable=False, default=False)
    check_in_interval_minutes = Column(Integer, nullable=False, default=25)
    completion_animations = Column(Boolean, nullable=False, default=False)
    streak_celebrations = Column(Boolean, nullable=False, default=False)
    show_progress_bars = Column(Boolean, nullable=False, default=False)
    forgiveness_streaks = Column(Boolean, nullable=False, default=False)

    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=lambda: datetime.now(timezone.utc))

    user = relationship("User")


class Medication(Base):
    """Top-level medication a user takes. Cross-device sync via ``cloud_id``."""

    __tablename__ = "medications"

    id = Column(Integer, primary_key=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    cloud_id = Column(String(64), unique=True, nullable=True, index=True)
    name = Column(String(255), nullable=False)
    dosage = Column(String(255), nullable=True)
    notes = Column(Text, nullable=True)
    is_active = Column(Boolean, nullable=False, default=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=lambda: datetime.now(timezone.utc))

    user = relationship("User")


class MedicationSlot(Base):
    """Time slot definition (morning/noon/etc.) shared across medications."""

    __tablename__ = "medication_slots"

    id = Column(Integer, primary_key=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    cloud_id = Column(String(64), unique=True, nullable=True, index=True)
    slot_key = Column(String(32), nullable=False)
    ideal_time = Column(String(5), nullable=True)  # "HH:mm"
    drift_minutes = Column(Integer, nullable=False, default=30)
    is_active = Column(Boolean, nullable=False, default=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=lambda: datetime.now(timezone.utc))

    user = relationship("User")


class MedicationTierState(Base):
    """Per-(medication, slot, date) achieved tier with intended/logged-at times."""

    __tablename__ = "medication_tier_states"
    __table_args__ = (
        UniqueConstraint(
            "user_id", "medication_id", "log_date", "slot_id",
            name="uq_med_tier_state",
        ),
    )

    id = Column(Integer, primary_key=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    cloud_id = Column(String(64), unique=True, nullable=True, index=True)
    medication_id = Column(Integer, ForeignKey("medications.id", ondelete="CASCADE"), nullable=False, index=True)
    slot_id = Column(Integer, ForeignKey("medication_slots.id", ondelete="CASCADE"), nullable=False, index=True)
    log_date = Column(Date, nullable=False, index=True)
    tier = Column(String(20), nullable=False)
    tier_source = Column(String(20), nullable=False, default="computed")
    intended_time = Column(DateTime(timezone=True), nullable=True)
    logged_at = Column(DateTime(timezone=True), nullable=False)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=lambda: datetime.now(timezone.utc))

    user = relationship("User")
    medication = relationship("Medication")
    slot = relationship("MedicationSlot")


# MedicationMark — removed in chore/drop-orphan-medication-marks.
# The per-medication mark concept ended up living on
# `medication_tier_states.intended_time` (slot-granularity), so the
# `medication_marks` table was never populated by any production write
# path. The table was dropped in Alembic 021_drop_medication_marks.


class MedicationLogEvent(Base):
    """Append-only audit log for medication time-logging events.

    Written fire-and-forget on every /sync/push touching ``medication_tier_state``.
    Records both the client-claimed times (``intended_time``,
    ``logged_at``) and the server-observed ``sync_received_at`` so
    post-hoc debugging can spot client/server clock skew.

    The ``entity_type`` column historically also held ``"mark"`` for the
    `medication_mark` family; that family was removed in
    chore/drop-orphan-medication-marks. Existing audit rows with
    ``entity_type = "mark"`` are preserved (audit log is append-only).
    """

    __tablename__ = "medication_log_events"
    __table_args__ = (
        # Composite index on (user_id, logged_at) for time-ordered queries
        # (the GET endpoint paginates by logged_at desc).
        {"sqlite_autoincrement": True},
    )

    id = Column(Integer, primary_key=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    entity_type = Column(String(20), nullable=False)  # "tier_state"
    entity_cloud_id = Column(String(64), nullable=True, index=True)
    intended_time = Column(DateTime(timezone=True), nullable=True)
    logged_at = Column(DateTime(timezone=True), nullable=False, index=True)
    sync_received_at = Column(DateTime(timezone=True), server_default=func.now(), nullable=False)
    operation = Column(String(20), nullable=False)  # "create" | "update" | "delete"

    user = relationship("User")


class DailyEssentialSlotCompletion(Base):
    """Materialized completion record for a Daily Essentials medication time slot.

    A slot is identified by (user, date, slot_key) where ``slot_key`` is either
    a ``"HH:mm"`` wall-clock time or the string ``"anytime"`` for interval-based
    doses without a fixed clock time. ``taken_at`` is ``NULL`` when the user has
    un-checked a previously-materialized slot; the row is retained so the
    ``med_ids_json`` snapshot stays stable across derivation changes.
    """

    __tablename__ = "daily_essential_slot_completions"
    __table_args__ = (
        UniqueConstraint("user_id", "date", "slot_key", name="uq_user_date_slot"),
    )

    id = Column(Integer, primary_key=True)
    user_id = Column(
        Integer,
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    date = Column(Date, nullable=False)
    slot_key = Column(String(16), nullable=False)
    med_ids_json = Column(Text, nullable=True)
    taken_at = Column(DateTime(timezone=True), nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=lambda: datetime.now(timezone.utc))

    user = relationship("User")


class RtdnEvent(Base):
    """Append-only audit log for Google Play Real-Time Developer
    Notifications.

    Every Pub/Sub Push payload from Google's RTDN feed is captured here
    keyed by ``pubsub_message_id`` for redelivery idempotency. Per
    ``docs/audits/BILLING_SCHEMA_AUDIT.md`` (option B), this table
    stands alone: ``users.tier`` + ``services/billing.py`` remain the
    entitlement source of truth, and event-to-user correlation happens
    at processing time via the Play Developer API rather than via a FK.

    The table is append-only at the application layer — there is no
    UPDATE or DELETE code path. ``processed`` and ``processing_error``
    are filled in-place by the handler after the event has been acted
    on, but rows are never deleted.
    """

    __tablename__ = "rtdn_events"

    id = Column(Integer, primary_key=True)
    pubsub_message_id = Column(String(128), nullable=False, unique=True)
    event_type = Column(
        Enum(RtdnEventType, values_callable=lambda x: [e.value for e in x],
             name="rtdn_event_type"),
        nullable=False,
    )
    purchase_token = Column(String(512), nullable=True, index=True)
    product_id = Column(String(128), nullable=True)
    raw_payload = Column(Text, nullable=False)
    processed = Column(Boolean, nullable=False, server_default="0", default=False)
    processing_error = Column(Text, nullable=True)
    received_at = Column(
        DateTime(timezone=True),
        server_default=func.now(),
        nullable=False,
        index=True,
    )


class BetaCode(Base):
    """Admin-issued unlock code that grants Pro tier on redemption.

    Per ``docs/audits/D_SERIES_BETA_TESTER_UNLOCK_CODES_AUDIT.md``.
    Codes are account-bound: a single account cannot redeem the same
    code twice, but ``max_redemptions`` distinct accounts can each
    redeem it once. ``revoked_at`` marks the code unusable for *future*
    redemptions; existing redemptions stay valid until their snapshot
    ``grants_pro_until`` expires.
    """

    __tablename__ = "beta_codes"

    code = Column(String(64), primary_key=True)
    description = Column(String(500), nullable=True)
    valid_from = Column(DateTime(timezone=True), server_default=func.now(), nullable=False)
    valid_until = Column(DateTime(timezone=True), nullable=True)
    grants_pro_until = Column(DateTime(timezone=True), nullable=True)
    max_redemptions = Column(Integer, nullable=True)
    redemption_count = Column(Integer, server_default="0", default=0, nullable=False)
    revoked_at = Column(DateTime(timezone=True), nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now(), nullable=False)

    redemptions = relationship(
        "BetaCodeRedemption", back_populates="code_row", cascade="all, delete-orphan"
    )


class BetaCodeRedemption(Base):
    """Per-(code, user) redemption row. ``grants_pro_until`` snapshots
    the code's value at redemption time so a later code revoke does not
    retroactively expire active redemptions."""

    __tablename__ = "beta_code_redemptions"
    __table_args__ = (
        UniqueConstraint("code", "user_id", name="uq_beta_redeem_code_user"),
    )

    id = Column(Integer, primary_key=True)
    code = Column(String(64), ForeignKey("beta_codes.code"), nullable=False)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    redeemed_at = Column(DateTime(timezone=True), server_default=func.now(), nullable=False)
    grants_pro_until = Column(DateTime(timezone=True), nullable=True)

    code_row = relationship("BetaCode", back_populates="redemptions")
    user = relationship("User")


class ChatMessage(Base):
    """Persisted conversational-coach chat turn.

    Per ``docs/audits/D11_E3_CHAT_PERSISTENCE_AUDIT.md`` (Item 1 = Shape A).
    Both user and assistant turns land in this table with role
    discrimination; ``conversation_id`` is the day-keyed grouping the
    Android client already mints (``chat_{ISO_DATE}_{UUID8}``). Server
    is the sole writer — clients call ``/chat`` and the handler appends
    rows; clients read via ``GET /chat/history``.
    """

    __tablename__ = "chat_messages"

    id = Column(String(64), primary_key=True)
    user_id = Column(
        Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    conversation_id = Column(String(128), nullable=False)
    role = Column(String(16), nullable=False)
    content = Column(Text, nullable=False)
    actions = Column(JSON, nullable=True)
    task_context_snapshot = Column(JSON, nullable=True)
    tokens_input = Column(Integer, nullable=True)
    tokens_output = Column(Integer, nullable=True)
    created_at = Column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )

    user = relationship("User")


class InAppFeedback(Base):
    """In-app rating / sentiment feedback (E2 in-app ratings).

    Per ``docs/audits/E2_IN_APP_RATINGS_AUDIT.md`` § Item 5. Greenfield
    table; written by ``POST /api/v1/feedback/in-app`` after the user
    submits the custom "how's it going?" prompt. ``sentiment`` is the
    discriminator (``thumb_up`` / ``thumb_down`` / ``rating``); ``rating``
    is set only when sentiment == ``rating`` (currently UI submits
    thumbs only, but the column accommodates a future 1-5 surface
    without a migration).
    """

    __tablename__ = "in_app_feedback"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(
        Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    sentiment = Column(String(32), nullable=False)
    rating = Column(SmallInteger, nullable=True)
    free_text = Column(Text, nullable=True)
    client_timestamp = Column(BigInteger, nullable=True)
    created_at = Column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )

    user = relationship("User")
