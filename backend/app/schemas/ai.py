from datetime import date
from typing import Any, Optional

from pydantic import BaseModel, Field, model_validator


# --- Eisenhower ---


class EisenhowerRequest(BaseModel):
    task_ids: Optional[list[str]] = None


class EisenhowerCategorization(BaseModel):
    task_id: str
    quadrant: str
    reason: str


class EisenhowerSummary(BaseModel):
    Q1: int = 0
    Q2: int = 0
    Q3: int = 0
    Q4: int = 0


class EisenhowerResponse(BaseModel):
    categorizations: list[EisenhowerCategorization]
    summary: EisenhowerSummary


class EisenhowerClassifyTextRequest(BaseModel):
    """Single-task text-based Eisenhower classification.

    Accepts raw task fields so the client can classify a freshly-created
    task before the local row has been synced to the backend. Mirrors the
    client's `EisenhowerClassifier.classify(task)` call shape.
    """

    title: str = Field(min_length=1, max_length=500)
    description: Optional[str] = Field(default=None, max_length=4000)
    due_date: Optional[str] = None  # ISO date (YYYY-MM-DD); null means no due date
    priority: int = Field(default=0, ge=0, le=4)


class EisenhowerClassifyTextResponse(BaseModel):
    quadrant: str  # "Q1".."Q4"
    reason: str


class CognitiveLoadClassifyTextRequest(BaseModel):
    """Single-task text-based cognitive-load classification.

    Accepts raw task fields so the client can classify a freshly-created
    task before the local row has been synced to the backend. Mirrors the
    on-device ``CognitiveLoadClassifier.classify(task)`` shape — the AI
    path is the optional, AI-Features-gated upgrade over the keyword
    classifier. See ``docs/COGNITIVE_LOAD.md``.
    """

    title: str = Field(min_length=1, max_length=500)
    description: Optional[str] = Field(default=None, max_length=4000)


class CognitiveLoadClassifyTextResponse(BaseModel):
    load: str  # "EASY" | "MEDIUM" | "HARD"
    reason: str


# --- Pomodoro ---


class PomodoroRequest(BaseModel):
    available_minutes: int = Field(default=120, ge=15, le=480)
    session_length: int = Field(default=25, ge=5, le=60)
    break_length: int = Field(default=5, ge=1, le=30)
    long_break_length: int = Field(default=15, ge=5, le=60)
    focus_preference: str = Field(default="balanced")


class SessionTask(BaseModel):
    task_id: str
    title: str
    allocated_minutes: int


class PomodoroSession(BaseModel):
    session_number: int
    tasks: list[SessionTask]
    rationale: str


class SkippedTask(BaseModel):
    task_id: str
    reason: str


class PomodoroResponse(BaseModel):
    sessions: list[PomodoroSession]
    total_sessions: int
    total_work_minutes: int
    total_break_minutes: int
    skipped_tasks: list[SkippedTask] = []


# --- Daily Briefing ---


class DailyBriefingRequest(BaseModel):
    date: Optional[str] = None  # ISO date string, defaults to today


class BriefingPriority(BaseModel):
    task_id: str
    title: str
    reason: str


class SuggestedTask(BaseModel):
    task_id: str
    title: str
    suggested_time: str
    reason: str


class DailyBriefingResponse(BaseModel):
    greeting: str
    top_priorities: list[BriefingPriority]
    heads_up: list[str] = []
    suggested_order: list[SuggestedTask]
    habit_reminders: list[str] = []
    day_type: str  # "light", "moderate", "heavy"


# --- Weekly Plan ---


class WeeklyPlanPreferences(BaseModel):
    work_days: list[str] = Field(default=["MO", "TU", "WE", "TH", "FR"])
    focus_hours_per_day: int = Field(default=6, ge=1, le=12)
    prefer_front_loading: bool = True


class WeeklyPlanRequest(BaseModel):
    week_start: Optional[str] = None  # Monday of target week, defaults to next Monday
    preferences: WeeklyPlanPreferences = WeeklyPlanPreferences()


class PlannedTask(BaseModel):
    task_id: str
    title: str
    suggested_time: str
    duration_minutes: int
    reason: str


class DayPlan(BaseModel):
    date: str
    tasks: list[PlannedTask]
    total_hours: float
    calendar_events: list[str] = []
    habits: list[str] = []


class UnscheduledTask(BaseModel):
    task_id: str
    title: str
    reason: str


class WeeklyPlanResponse(BaseModel):
    plan: dict[str, DayPlan]  # day name -> plan
    unscheduled: list[UnscheduledTask] = []
    week_summary: str
    tips: list[str] = []


# --- Time Block ---


class ExistingBlock(BaseModel):
    """A pre-existing block the AI planner must treat as a hard constraint.

    Used for both PrismTask-scheduled tasks (``source="task"``) and
    Pomodoro sessions (``source="pomodoro"``) already on the user's calendar
    in the horizon window. Google Calendar events are NOT sent here — they
    still flow through the legacy ``calendar_events`` path.
    """

    date: str  # ISO date (YYYY-MM-DD) of the block's local day
    start: str  # HH:MM local
    end: str  # HH:MM local
    title: str
    source: str = Field(pattern="^(task|pomodoro)$")
    task_id: Optional[str] = None


class TimeBlockTaskSignal(BaseModel):
    """Optional rich per-task signals the client can attach to a time-block request.

    All fields are optional — when absent the planner falls back to the
    shape it used before v1.4.40. The client matches entries by ``task_id``
    to the tasks it has already surfaced via Firestore.
    """

    task_id: str
    eisenhower_quadrant: Optional[str] = Field(default=None, pattern="^(Q1|Q2|Q3|Q4)$")
    estimated_pomodoro_sessions: Optional[int] = Field(default=None, ge=0, le=32)
    estimated_duration_minutes: Optional[int] = Field(default=None, ge=0, le=1440)
    pomodoro_source: Optional[str] = Field(
        default=None,
        pattern="^(recorded|estimated_from_duration)$",
    )


class TimeBlockRequest(BaseModel):
    date: Optional[str] = None  # defaults to today (horizon anchor)
    day_start: str = Field(default="09:00")
    day_end: str = Field(default="18:00")
    block_size_minutes: int = Field(default=30, ge=15, le=120)
    include_breaks: bool = True
    break_frequency_minutes: int = Field(default=90, ge=30, le=180)
    break_duration_minutes: int = Field(default=15, ge=5, le=30)
    # v1.4.40: horizon selector. 1 = just today (legacy), 2 = today+tomorrow,
    # 7 = rolling week. Defaults to 1 so pre-v1.4.40 clients keep their
    # single-day behavior without touching the request body.
    horizon_days: int = Field(default=1, ge=1, le=7)
    # v1.4.40: per-task signals the AI should use to rank and size blocks.
    # Keyed by task_id on the client — backend merges into the prompt.
    task_signals: list[TimeBlockTaskSignal] = Field(default_factory=list)
    # v1.4.40: pre-existing PrismTask blocks + Pomodoro sessions in the
    # horizon. Hard constraints — the planner must not schedule over them.
    existing_blocks: list[ExistingBlock] = Field(default_factory=list)


class ScheduleBlock(BaseModel):
    start: str
    end: str
    type: str  # "task", "event", "break"
    task_id: Optional[str] = None
    title: str
    reason: str
    # v1.4.40: for multi-day horizons, every block carries the ISO date it
    # belongs to. Absent/null on legacy single-day responses — clients
    # should default to the request's ``date`` field.
    date: Optional[str] = None


class TimeBlockStats(BaseModel):
    total_work_minutes: int
    total_break_minutes: int
    total_free_minutes: int
    tasks_scheduled: int
    tasks_deferred: int


class TimeBlockResponse(BaseModel):
    schedule: list[ScheduleBlock]
    unscheduled_tasks: list[UnscheduledTask] = []
    stats: TimeBlockStats
    # v1.4.40: explicit "proposed, not committed" flag. The client MUST NOT
    # write this schedule without user approval.
    proposed: bool = True
    horizon_days: int = 1


# --- Weekly Review (v2 hybrid schema) ---
#
# Schema v2. Breaking change from v1 (which sent aggregate counts only): the
# client now sends per-task summaries for completed and slipped items, and
# the backend enriches the prompt with a live Firestore "open tasks" list.
# Old clients sending the v1 shape will get 422 until their prompts land.


class WeeklyTaskSummary(BaseModel):
    task_id: str
    title: str
    completed_at: Optional[str] = None  # ISO datetime; None for slipped tasks
    priority: int = Field(ge=0, le=4)
    eisenhower_quadrant: Optional[str] = None
    life_category: Optional[str] = None
    project_id: Optional[str] = None


class WeeklyReviewRequest(BaseModel):
    week_start: date
    week_end: date
    completed_tasks: list[WeeklyTaskSummary] = Field(default_factory=list)
    slipped_tasks: list[WeeklyTaskSummary] = Field(default_factory=list)
    # Opaque pass-through. The backend forwards these to the prompt verbatim
    # so the client controls the shape (streak counts, session totals, etc.).
    habit_summary: Optional[dict[str, Any]] = None
    pomodoro_summary: Optional[dict[str, Any]] = None
    notes: Optional[str] = Field(default=None, max_length=2000)

    @model_validator(mode="after")
    def _check_week_span(self):
        if self.week_end < self.week_start:
            raise ValueError("week_end must be on or after week_start")
        if (self.week_end - self.week_start).days > 14:
            raise ValueError("week span must be 14 days or fewer")
        return self


class WeeklyReviewResponse(BaseModel):
    week_start: date
    week_end: date
    wins: list[str]
    slips: list[str]
    patterns: list[str]
    next_week_focus: list[str]
    narrative: str


# --- Task Extraction (v1.4.0 V9) ---


class ExtractFromTextRequest(BaseModel):
    text: str = Field(min_length=1, max_length=10_000)
    source: Optional[str] = None


class ExtractedTaskCandidate(BaseModel):
    title: str
    suggested_due_date: Optional[str] = None
    suggested_priority: int = 0
    suggested_project: Optional[str] = None
    confidence: float = Field(ge=0.0, le=1.0)


class ExtractFromTextResponse(BaseModel):
    tasks: list[ExtractedTaskCandidate]


# --- Pomodoro AI Coaching (pre-session / break / recap) ---


class PomodoroCoachingTask(BaseModel):
    task_id: Optional[str] = None
    title: str
    allocated_minutes: Optional[int] = None


class PomodoroCoachingRequest(BaseModel):
    """Trigger-based coaching prompt for the three Pomodoro+ surfaces.

    ``trigger`` is one of: ``"pre_session"``, ``"break_activity"``, ``"session_recap"``.
    Only the fields relevant to the trigger are consulted; the rest are ignored.
    """

    trigger: str = Field(pattern="^(pre_session|break_activity|session_recap)$")
    # Pre-session
    upcoming_tasks: Optional[list[PomodoroCoachingTask]] = None
    session_length_minutes: Optional[int] = None
    # Break
    elapsed_minutes: Optional[int] = Field(default=None, ge=0, le=600)
    break_type: Optional[str] = Field(default=None, pattern="^(short|long)$")
    recent_suggestions: Optional[list[str]] = None
    # Recap
    completed_tasks: Optional[list[PomodoroCoachingTask]] = None
    started_tasks: Optional[list[PomodoroCoachingTask]] = None
    session_duration_minutes: Optional[int] = Field(default=None, ge=0, le=600)


class PomodoroCoachingResponse(BaseModel):
    message: str


# --- Batch NLP Operations (A2 — pulled from Phase H) ---
#
# Single Haiku-backed endpoint that turns a natural-language command
# ("Cancel everything Friday", "Move all tasks tagged work to Monday") into
# a structured list of proposed mutations across Tasks/Habits/Projects/
# Medications. The client renders a diff-preview screen and only commits
# after user approval; nothing here writes to Firestore.
#
# Client supplies the entity context inline (task summaries, habit names,
# project list) instead of the backend pulling from Firestore — keeps the
# endpoint stateless, lets the client filter to what's actually loaded
# (e.g. archived projects excluded), and matches the WeeklyReviewRequest
# pattern.


_BATCH_ENTITY_TYPE_PATTERN = "^(TASK|HABIT|PROJECT|MEDICATION)$"
_BATCH_MUTATION_TYPE_PATTERN = (
    "^(RESCHEDULE|DELETE|COMPLETE|SKIP|"
    "PRIORITY_CHANGE|TAG_CHANGE|PROJECT_MOVE|ARCHIVE|STATE_CHANGE)$"
)


class BatchTaskContext(BaseModel):
    """One task as the AI sees it. The client passes whatever set of
    incomplete + recently-completed tasks the user could plausibly have
    meant; the prompt instructs the model to only reference IDs from this
    list (never fabricate)."""

    id: str
    title: str
    due_date: Optional[str] = None  # ISO YYYY-MM-DD
    scheduled_start_time: Optional[str] = None  # ISO datetime
    priority: int = Field(default=0, ge=0, le=4)
    project_id: Optional[str] = None
    project_name: Optional[str] = None
    tags: list[str] = Field(default_factory=list)
    life_category: Optional[str] = None
    is_completed: bool = False


class BatchHabitContext(BaseModel):
    id: str
    name: str
    is_archived: bool = False


class BatchProjectContext(BaseModel):
    id: str
    name: str
    status: Optional[str] = None  # "active" | "archived" | etc.


class BatchMedicationContext(BaseModel):
    id: str
    name: str
    display_label: Optional[str] = None


class ForcedAmbiguousPhrase(BaseModel):
    """Phrase the client's local matcher already determined is ambiguous
    (matches >=2 candidate medications). The backend appends these to its
    `ambiguous_entities` response so the picker always surfaces them, even
    if Haiku decided the phrase was clear. See ``MedicationNameMatcher``
    on Android / web / backend for the matching contract."""

    phrase: str
    candidate_entity_type: str = Field(
        default="MEDICATION", pattern=_BATCH_ENTITY_TYPE_PATTERN
    )
    candidate_entity_ids: list[str] = Field(default_factory=list)


class BatchUserContext(BaseModel):
    today: str  # ISO YYYY-MM-DD in user's local timezone
    timezone: str = Field(default="UTC", max_length=64)
    tasks: list[BatchTaskContext] = Field(default_factory=list)
    habits: list[BatchHabitContext] = Field(default_factory=list)
    projects: list[BatchProjectContext] = Field(default_factory=list)
    medications: list[BatchMedicationContext] = Field(default_factory=list)
    # Phrase -> medication_id pairs the client deterministically resolved
    # via its local MedicationNameMatcher. Treated as authoritative: any
    # mutation that targets one of these phrases must reuse the committed
    # id, and the phrase must NOT appear in `ambiguous_entities`.
    committed_medication_matches: dict[str, str] = Field(default_factory=dict)
    # Phrases the client classified as ambiguous (>=2 candidate meds).
    # The service unconditionally appends these to the response's
    # `ambiguous_entities` so the user sees the disambiguation picker.
    forced_ambiguous_phrases: list[ForcedAmbiguousPhrase] = Field(default_factory=list)


class BatchParseRequest(BaseModel):
    command_text: str = Field(min_length=1, max_length=500)
    user_context: BatchUserContext


class ProposedMutation(BaseModel):
    """One row in the diff-preview list. ``proposed_new_values`` is a
    free-form dict whose keys depend on ``mutation_type`` — e.g.
    ``{"due_date": "2026-04-30"}`` for RESCHEDULE,
    ``{"tags_added": ["work"], "tags_removed": []}`` for TAG_CHANGE.
    The client knows the schema per mutation type."""

    entity_type: str = Field(pattern=_BATCH_ENTITY_TYPE_PATTERN)
    entity_id: str
    mutation_type: str = Field(pattern=_BATCH_MUTATION_TYPE_PATTERN)
    proposed_new_values: dict[str, Any] = Field(default_factory=dict)
    human_readable_description: str


class AmbiguousEntityHint(BaseModel):
    """An entity reference the model couldn't disambiguate — e.g. "work
    tasks" matched two projects named "Work — H1" and "Work — H2".
    Client surfaces a resolution dialog before letting the user Approve."""

    phrase: str
    candidate_entity_type: str = Field(pattern=_BATCH_ENTITY_TYPE_PATTERN)
    candidate_entity_ids: list[str] = Field(default_factory=list)
    note: Optional[str] = None


class BatchParseResponse(BaseModel):
    mutations: list[ProposedMutation] = Field(default_factory=list)
    confidence: float = Field(default=1.0, ge=0.0, le=1.0)
    ambiguous_entities: list[AmbiguousEntityHint] = Field(default_factory=list)
    # Hard contract: client MUST treat this as a preview and never auto-commit.
    proposed: bool = True


# --- Conversational AI Coach (chat) ---
#
# Free-form coaching chat backed by Haiku. Stateless from the backend's POV:
# the client owns the rolling conversation history (10 pairs) and only sends
# the latest user message plus the conversation_id used for client-side
# correlation. The backend echoes conversation_id back unchanged.
#
# Response shape mirrors ``data.remote.api.ChatResponse`` on the Android
# client. Action types are the closed set the client's
# ``ChatViewModel.executeAction`` knows how to apply: complete, reschedule,
# reschedule_batch, breakdown, archive, start_timer, create_task.


_CHAT_ACTION_TYPE_PATTERN = (
    "^(complete|reschedule|reschedule_batch|breakdown|archive|start_timer|create_task)$"
)


class ChatActionPayload(BaseModel):
    """One AI-proposed inline action button. Field set varies by ``type`` —
    Pydantic accepts any subset; the Android client picks the fields it
    needs per type. See ChatViewModel.executeAction for the per-type contract."""

    type: str = Field(pattern=_CHAT_ACTION_TYPE_PATTERN)
    task_id: Optional[str] = None
    task_ids: Optional[list[str]] = None
    to: Optional[str] = None  # "today" | "tomorrow" | "next_week" | ISO date
    subtasks: Optional[list[str]] = None
    minutes: Optional[int] = Field(default=None, ge=1, le=480)
    title: Optional[str] = Field(default=None, max_length=500)
    due: Optional[str] = None
    priority: Optional[str] = Field(default=None, pattern="^(low|medium|high|urgent)$")


class ChatTokensUsed(BaseModel):
    input: int = Field(ge=0)
    output: int = Field(ge=0)


class ChatHistoryEntry(BaseModel):
    """One prior turn forwarded by the client so the AI has multi-turn memory.

    The backend is stateless — it does not store conversation history. The
    Android client owns the rolling window (last N=6 user/assistant pairs)
    and re-sends it on every turn. Empty history is fine on the first turn.
    """

    role: str = Field(pattern="^(user|assistant)$")
    content: str = Field(min_length=1, max_length=4000)


class ChatTaskContext(BaseModel):
    """Snapshot of the task the user is talking about, sent by the client
    when chat is opened from a specific task.

    Sent alongside ``task_context_id`` so the AI has the actual title /
    description / due date to ground its reply, instead of a bare opaque
    integer it cannot dereference.
    """

    title: str = Field(min_length=1, max_length=500)
    description: Optional[str] = Field(default=None, max_length=4000)
    due_date: Optional[str] = Field(default=None, max_length=64)
    priority: Optional[int] = Field(default=None, ge=0, le=4)
    project_name: Optional[str] = Field(default=None, max_length=200)
    is_completed: Optional[bool] = None


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=2000)
    conversation_id: str = Field(min_length=1, max_length=128)
    # Optional Android Room task ID (Long, sent as int). The backend treats
    # it as opaque: it can't dereference the local row, but the AI can
    # echo it back inside actions so the client knows which task to act on.
    task_context_id: Optional[int] = None
    # When ``task_context_id`` is set, the client also sends a snapshot of
    # the task fields so the AI can reason about the task. Optional because
    # the chat surface also opens from a generic entry-point with no task.
    task_context: Optional[ChatTaskContext] = None
    # Rolling conversation history (last N=6 user/assistant pairs from the
    # client). The backend forwards these as proper Anthropic ``messages``
    # entries so the model has multi-turn memory. Capped at 12 entries to
    # bound input tokens; the client owns trimming.
    history: list[ChatHistoryEntry] = Field(default_factory=list, max_length=12)
    # Forwarded from the client for telemetry only — the server uses
    # ``resolve_effective_tier(current_user, db)`` for actual gating
    # (admin override + stored tier + active beta-code redemption).
    # Accepted for backward compat but ignored: chat always uses Haiku
    # regardless of tier (see ``ai_productivity.get_model``).
    tier: Optional[str] = None


class ChatResponse(BaseModel):
    message: str
    actions: list[ChatActionPayload] = Field(default_factory=list)
    conversation_id: str
    tokens_used: Optional[ChatTokensUsed] = None


# --- Automation Action AI (A7) ---
#
# Two endpoints invoked by the on-device automation engine when a rule's
# action chain includes ``ai.complete`` or ``ai.summarize``. Both are
# deliberately tiny — the rule author writes a free-form prompt (complete)
# or names a scope identifier (summarize), and the backend returns a
# single text blob the handler stores on the firing log.
#
# Routed under the existing ``/ai/`` prefix so they automatically inherit
# the PII-egress AI gate, the Pro-tier daily AI rate limiter, and the
# router-level tier+auth dependencies. Adding them does NOT require
# touching ``AiFeatureGateInterceptor.AI_PATH_PREFIXES`` on the client.


class AutomationCompleteRequest(BaseModel):
    """`ai.complete` action — free-form Anthropic completion.

    The on-device handler passes the rule author's prompt verbatim and
    optionally an opaque ``context`` dict carrying the trigger event
    (entity payload, fired-at timestamp, etc.). The backend forwards both
    to Haiku and returns the model's response as plain text.
    """

    prompt: str = Field(min_length=1, max_length=4000)
    # Free-form passthrough — keys depend on the trigger entity type
    # (task/habit/medication/etc). Validated only at the pydantic boundary
    # since Haiku is the consumer; the router never inspects keys.
    context: Optional[dict[str, Any]] = None


class AutomationCompleteResponse(BaseModel):
    text: str


# Closed set of summary scopes the client knows how to label. Keep this
# in sync with the Android handler's enum if a new scope is added.
_AUTOMATION_SUMMARIZE_SCOPE_PATTERN = (
    "^(today|tomorrow|yesterday|week|next_week|month|overdue|inbox|custom)$"
)


class AutomationSummarizeRequest(BaseModel):
    """`ai.summarize` action — scoped summary of recent activity.

    ``scope`` is one of a small closed set ("today" / "week" / "month" /
    etc.) the client passes literally; the backend uses it to phrase the
    Haiku prompt. ``max_items`` caps how many entities the prompt can
    consider — also enforced server-side as a sanity bound.
    """

    scope: str = Field(pattern=_AUTOMATION_SUMMARIZE_SCOPE_PATTERN)
    max_items: int = Field(default=50, ge=1, le=500)
    # Optional opaque passthrough — the engine forwards trigger context the
    # same way ai.complete does. The backend doesn't inspect keys.
    context: Optional[dict[str, Any]] = None


class AutomationSummarizeResponse(BaseModel):
    summary: str
