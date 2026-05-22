import logging
from datetime import date as date_cls, datetime, timezone
from typing import Any

from fastapi import APIRouter, Depends, Query
from prometheus_client import Counter
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.middleware.auth import get_current_user
from app.models import (
    DailyEssentialSlotCompletion,
    Goal,
    GoalStatus,
    Habit,
    HabitCompletion,
    HabitFrequency,
    Medication,
    MedicationLogEvent,
    MedicationSlot,
    MedicationTierState,
    Project,
    ProjectStatus,
    Tag,
    Task,
    TaskStatus,
    TaskTemplate,
    User,
)
from app.schemas.sync import (
    SyncChange,
    SyncOperation,
    SyncPullResponse,
    SyncPushRequest,
    SyncPushResponse,
)

logger = logging.getLogger(__name__)

# Process-level audit-emit failure counter, labelled by entity_type. Used by
# the medication migration safety net to surface silent loss of audit events.
# Exposed via /metrics for Grafana Cloud scraping; the structured WARN log
# below carries the same per-entity count for grep-friendly Cloud Logging
# filters. Reset on process restart — appropriate for the always-on FastAPI
# deployment model.
audit_emit_failures_total = Counter(
    "audit_emit_failures_total",
    "Number of best-effort audit-emit savepoint failures since process start",
    labelnames=["entity_type"],
)

router = APIRouter(prefix="/sync", tags=["sync"])

ENTITY_MAP = {
    "goal": Goal,
    "project": Project,
    "task": Task,
    "tag": Tag,
    "habit": Habit,
    "habit_completion": HabitCompletion,
    "template": TaskTemplate,
    "daily_essential_slot_completion": DailyEssentialSlotCompletion,
    "medication": Medication,
    "medication_slot": MedicationSlot,
    "medication_tier_state": MedicationTierState,
}

STATUS_ENUM_MAP = {
    "goal": GoalStatus,
    "project": ProjectStatus,
    "task": TaskStatus,
    "habit": HabitFrequency,
}

# Per-entity allowlists of fields a client may write via /sync. Anything
# outside these lists is stripped before the ORM instance is constructed
# or updated — this protects columns like `user_id`, `id`, `is_admin`,
# `tier`, and `created_at` from client-controlled assignment.
#
# Relationship FKs that reference other user-owned entities (e.g.
# `project_id` on a task) are additionally validated for ownership in
# ``_validate_foreign_keys`` below.
#
# `cloud_id` is allowed for medication entities because cross-device
# Firestore-driven sync identifies rows by client-generated cloud IDs;
# without this, two devices creating the "same" row produce duplicates.
WRITABLE_FIELDS: dict[str, frozenset[str]] = {
    "goal": frozenset({
        "title", "description", "status", "target_date", "color", "sort_order",
    }),
    "project": frozenset({
        "goal_id", "title", "description", "status", "due_date", "sort_order",
    }),
    "task": frozenset({
        "project_id", "parent_id", "title", "description", "notes", "status",
        "priority", "due_date", "due_time", "planned_date", "completed_at",
        "urgency_score", "recurrence_json", "eisenhower_quadrant",
        "eisenhower_updated_at", "estimated_duration", "actual_duration",
        "sort_order", "depth",
    }),
    "tag": frozenset({"name", "color"}),
    "habit": frozenset({
        "name", "description", "icon", "color", "category", "frequency",
        "target_count", "active_days_json", "is_active",
    }),
    "habit_completion": frozenset({"habit_id", "date", "count"}),
    "daily_essential_slot_completion": frozenset({
        "date", "slot_key", "med_ids_json", "taken_at",
    }),
    "template": frozenset({
        "name", "description", "icon", "category",
        "template_title", "template_description", "template_priority",
        "template_project_id", "template_tags_json",
        "template_recurrence_json", "template_duration", "template_subtasks_json",
    }),
    "medication": frozenset({
        "cloud_id", "name", "dosage", "notes", "is_active",
    }),
    "medication_slot": frozenset({
        "cloud_id", "slot_key", "ideal_time", "drift_minutes", "is_active",
    }),
    "medication_tier_state": frozenset({
        # Cross-system FK references go by cloud_id — Android/web local
        # integer ids don't agree with backend integer ids, so the only
        # safe identifier across systems is the user-generated cloud_id.
        # Real DB columns (`medication_id`, `slot_id`) are filled by
        # `_resolve_cloud_fk_for_medication` below.
        "cloud_id", "medication_cloud_id", "slot_cloud_id",
        "log_date", "tier", "tier_source", "intended_time", "logged_at",
    }),
}

# Foreign keys that reference user-scoped entities. Before assigning one of
# these keys, the server must confirm the referenced row belongs to the
# authenticated user.
#
# Medication tier_state is NOT listed here — it references its parents
# by `*_cloud_id` (cross-system safe) instead of local integer FKs,
# and the cloud-id resolution + ownership check happen inline in
# `_resolve_cloud_fk_for_medication`.
USER_SCOPED_FKS: dict[str, dict[str, type]] = {
    "project": {"goal_id": Goal},
    "task": {"project_id": Project, "parent_id": Task},
    "habit_completion": {"habit_id": Habit},
    "template": {"template_project_id": Project},
}

# Per-entity mapping of `*_cloud_id` payload keys -> (model, target FK
# column). The resolver pops the cloud_id key, looks up the integer id
# on the named model (scoped to the user), and writes the integer to
# the FK column. Used only for medication tier_state today; every other
# entity still uses local integer FKs.
_MEDICATION_CLOUD_FK_MAP: dict[str, dict[str, tuple[type, str]]] = {
    "medication_tier_state": {
        "medication_cloud_id": (Medication, "medication_id"),
        "slot_cloud_id": (MedicationSlot, "slot_id"),
    },
}

# Medication entity types that emit audit rows on every /sync/push write.
# Maps the sync entity_type to the short label stored in
# ``medication_log_events.entity_type``.
AUDIT_ENTITY_TYPES: dict[str, str] = {
    "medication_tier_state": "tier_state",
}


def _filter_writable(entity_type: str, data: dict) -> dict:
    """Strip any key not in the per-entity allowlist."""
    allowed = WRITABLE_FIELDS.get(entity_type, frozenset())
    filtered: dict = {}
    for key, value in data.items():
        if key in allowed:
            filtered[key] = value
        else:
            logger.info(
                "sync: dropping disallowed field %s on %s", key, entity_type
            )
    return filtered


async def _validate_foreign_keys(
    entity_type: str, data: dict, user: User, db: AsyncSession
) -> str | None:
    """Ensure any user-scoped FK in ``data`` points to a row owned by ``user``.

    Returns an error string on failure, None on success.
    """
    fks = USER_SCOPED_FKS.get(entity_type)
    if not fks:
        return None
    for column, model in fks.items():
        value = data.get(column)
        if value is None:
            continue
        query = select(model.id).where(model.id == value)
        if hasattr(model, "user_id"):
            query = query.where(model.user_id == user.id)
        result = await db.execute(query)
        if result.scalar_one_or_none() is None:
            return f"Invalid {column} on {entity_type}: {value} not found"
    return None


async def _resolve_cloud_fk_for_medication(
    entity_type: str, data: dict, user: User, db: AsyncSession
) -> str | None:
    """Resolve every `*_cloud_id` reference in `data` for medication
    tier_state / mark into the corresponding integer FK column.
    Mutates `data`: pops the cloud_id keys, adds the integer FK keys.

    Returns an error string if any cloud_id is missing or doesn't
    resolve to a row owned by the caller. None on success or when the
    entity type doesn't use cloud-id FKs.
    """
    cloud_fks = _MEDICATION_CLOUD_FK_MAP.get(entity_type)
    if not cloud_fks:
        return None
    for cloud_key, (model, fk_column) in cloud_fks.items():
        cloud_id = data.pop(cloud_key, None)
        if not cloud_id:
            return f"{entity_type}.{cloud_key} is required"
        query = select(model.id).where(model.cloud_id == cloud_id)
        if hasattr(model, "user_id"):
            query = query.where(model.user_id == user.id)
        result = await db.execute(query)
        resolved = result.scalar_one_or_none()
        if resolved is None:
            return (
                f"{entity_type}.{cloud_key}={cloud_id} did not resolve "
                "to a row owned by this user"
            )
        data[fk_column] = resolved
    return None


def _parse_dt(value: Any) -> datetime | None:
    """Tolerant ISO-8601 parser — accepts strings, datetimes, or None."""
    if value is None:
        return None
    if isinstance(value, datetime):
        return value
    if isinstance(value, str):
        try:
            # Python's fromisoformat handles "+00:00" but not "Z" until 3.11
            return datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            return None
    return None


def _parse_date(value: Any) -> date_cls | None:
    """Tolerant ISO-date parser. SQLite's Date type only accepts Python
    ``date`` objects, so any "YYYY-MM-DD" string from a sync payload must
    be coerced before the ORM sees it."""
    if value is None:
        return None
    if isinstance(value, date_cls) and not isinstance(value, datetime):
        return value
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, str):
        try:
            return date_cls.fromisoformat(value)
        except ValueError:
            return None
    return None


def _build_audit_record(
    op: SyncOperation, user: User
) -> dict[str, Any] | None:
    """If the op targets an audited entity, return a kwargs dict for
    ``MedicationLogEvent(**...)``. Otherwise return None.
    """
    label = AUDIT_ENTITY_TYPES.get(op.entity_type)
    if label is None:
        return None
    data = op.data or {}
    intended = _parse_dt(data.get("intended_time"))
    logged = _parse_dt(data.get("logged_at")) or datetime.now(timezone.utc)
    return {
        "user_id": user.id,
        "entity_type": label,
        "entity_cloud_id": data.get("cloud_id"),
        "intended_time": intended,
        "logged_at": logged,
        "operation": op.operation,
    }


async def _emit_audit_events(db: AsyncSession, records: list[dict[str, Any]]) -> None:
    """Per-record best-effort audit writer.

    Each insert runs inside a SAVEPOINT (``begin_nested``) so that an
    audit-only failure rolls back just that one record rather than the
    whole sync transaction. Sync is authoritative; audit is best-effort.
    """
    for r in records:
        try:
            async with db.begin_nested():
                db.add(MedicationLogEvent(**r))
        except Exception as exc:  # noqa: BLE001
            entity_type = r.get("entity_type", "unknown")
            child = audit_emit_failures_total.labels(entity_type=entity_type)
            child.inc()
            # Structured fields land in Cloud Logging's jsonPayload so a
            # log-based metric `audit_emit_failed=true` counts these without
            # re-querying message text. The medication migration safety net
            # uses this metric: silent audit loss is the moral equivalent
            # of silent migration failure on the client side, and the
            # closed-beta dashboard plots both side-by-side.
            logger.warning(
                "medication audit emit failed for %s: %s",
                r.get("entity_cloud_id"), exc,
                extra={
                    "audit_emit_failed": True,
                    "entity_type": entity_type,
                    "entity_cloud_id": r.get("entity_cloud_id"),
                    "exception_class": type(exc).__name__,
                    "audit_emit_failures_total": int(child._value.get()),
                },
            )


async def _process_operation(
    op: SyncOperation, user: User, db: AsyncSession
) -> str | None:
    model = ENTITY_MAP.get(op.entity_type)
    if not model:
        return f"Unknown entity type: {op.entity_type}"

    if op.operation == "create":
        if not op.data:
            return "Create operation requires data"
        data = _filter_writable(op.entity_type, dict(op.data))
        fk_error = await _validate_foreign_keys(op.entity_type, data, user, db)
        if fk_error:
            return fk_error
        cloud_fk_error = await _resolve_cloud_fk_for_medication(
            op.entity_type, data, user, db
        )
        if cloud_fk_error:
            return cloud_fk_error
        # Force user_id server-side — never trust the client for ownership.
        if hasattr(model, "user_id"):
            data["user_id"] = user.id
        # Default status for new tasks (PostgreSQL enum values are lowercase)
        if op.entity_type == "task" and "status" not in data:
            data["status"] = "todo"
        # Convert status enums to their lowercase values
        if "status" in data and op.entity_type in STATUS_ENUM_MAP:
            data["status"] = STATUS_ENUM_MAP[op.entity_type](data["status"]).value
        if "frequency" in data and op.entity_type == "habit":
            data["frequency"] = HabitFrequency(data["frequency"]).value
        # Tolerant ISO parsing for datetime + date fields on medication
        # entities. SQLAlchemy's TIMESTAMPTZ wants datetimes; SQLite's
        # Date type insists on Python ``date`` objects.
        for key in ("intended_time", "logged_at"):
            if key in data:
                data[key] = _parse_dt(data[key])
        if "log_date" in data:
            data["log_date"] = _parse_date(data["log_date"])
        entity = model(**data)
        db.add(entity)

    elif op.operation == "update":
        if not op.entity_id or not op.data:
            return "Update requires entity_id and data"
        query = select(model).where(model.id == op.entity_id)
        if hasattr(model, "user_id"):
            query = query.where(model.user_id == user.id)
        result = await db.execute(query)
        entity = result.scalar_one_or_none()
        if not entity:
            return f"{op.entity_type} {op.entity_id} not found"
        data = _filter_writable(op.entity_type, dict(op.data))
        fk_error = await _validate_foreign_keys(op.entity_type, data, user, db)
        if fk_error:
            return fk_error
        cloud_fk_error = await _resolve_cloud_fk_for_medication(
            op.entity_type, data, user, db
        )
        if cloud_fk_error:
            return cloud_fk_error
        for key, value in data.items():
            if key == "status" and op.entity_type in STATUS_ENUM_MAP:
                value = STATUS_ENUM_MAP[op.entity_type](value).value
            if key == "frequency" and op.entity_type == "habit":
                value = HabitFrequency(value).value
            if key in ("intended_time", "logged_at"):
                value = _parse_dt(value)
            if key == "log_date":
                value = _parse_date(value)
            setattr(entity, key, value)

    elif op.operation == "delete":
        if not op.entity_id:
            return "Delete requires entity_id"
        query = select(model).where(model.id == op.entity_id)
        if hasattr(model, "user_id"):
            query = query.where(model.user_id == user.id)
        result = await db.execute(query)
        entity = result.scalar_one_or_none()
        if not entity:
            return f"{op.entity_type} {op.entity_id} not found"
        await db.delete(entity)

    else:
        return f"Unknown operation: {op.operation}"

    return None


@router.post("/push", response_model=SyncPushResponse)
async def sync_push(
    data: SyncPushRequest,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    errors = []
    processed = 0
    audit_records: list[dict[str, Any]] = []

    for op in data.operations:
        error = await _process_operation(op, current_user, db)
        if error:
            errors.append(error)
            continue
        processed += 1
        record = _build_audit_record(op, current_user)
        if record is not None:
            audit_records.append(record)

    await _emit_audit_events(db, audit_records)
    await db.flush()

    return SyncPushResponse(
        processed=processed,
        errors=errors,
        server_timestamp=datetime.now(timezone.utc),
    )


@router.get("/pull", response_model=SyncPullResponse)
async def sync_pull(
    since: datetime | None = Query(default=None),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    changes: list[SyncChange] = []

    for entity_type, model in ENTITY_MAP.items():
        if entity_type == "habit_completion":
            # HabitCompletion doesn't have user_id directly
            continue

        table = model.__table__
        query = select(table)

        if hasattr(model, "user_id"):
            query = query.where(table.c.user_id == current_user.id)

        if since and hasattr(model, "updated_at"):
            query = query.where(table.c.updated_at > since)
        elif since and hasattr(model, "created_at"):
            query = query.where(table.c.created_at > since)

        result = await db.execute(query)
        for row in result.mappings().all():
            data = {}
            for col_name, val in row.items():
                if hasattr(val, "value"):
                    val = val.value
                if hasattr(val, "isoformat"):
                    val = val.isoformat()
                data[col_name] = val

            timestamp = row.get("updated_at") or row.get("created_at")
            changes.append(
                SyncChange(
                    entity_type=entity_type,
                    operation="upsert",
                    entity_id=row["id"],
                    data=data,
                    timestamp=timestamp or datetime.now(timezone.utc),
                )
            )

    # Also pull habit completions via user's habits
    if since:
        habit_result = await db.execute(
            select(Habit.id).where(Habit.user_id == current_user.id)
        )
        habit_ids = [r[0] for r in habit_result.all()]
        if habit_ids:
            comp_result = await db.execute(
                select(HabitCompletion)
                .where(
                    HabitCompletion.habit_id.in_(habit_ids),
                    HabitCompletion.created_at > since,
                )
            )
            for c in comp_result.scalars().all():
                changes.append(
                    SyncChange(
                        entity_type="habit_completion",
                        operation="upsert",
                        entity_id=c.id,
                        data={
                            "id": c.id,
                            "habit_id": c.habit_id,
                            "date": c.date.isoformat(),
                            "count": c.count,
                        },
                        timestamp=c.created_at,
                    )
                )

    return SyncPullResponse(
        changes=changes,
        server_timestamp=datetime.now(timezone.utc),
    )
