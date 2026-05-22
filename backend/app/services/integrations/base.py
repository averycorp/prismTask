"""Integration framework base types and helpers."""

import json
import logging
from datetime import date, datetime, timezone

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import (
    IntegrationSource,
    Project,
    SuggestedTask,
    SuggestionStatus,
    Tag,
    Task,
    TaskStatus,
)

logger = logging.getLogger(__name__)


async def get_user_projects(db: AsyncSession, user_id: int) -> list[str]:
    """Return project titles for the given user."""
    result = await db.execute(
        select(Project.title).where(Project.user_id == user_id)
    )
    return [row[0] for row in result.all()]


async def get_user_tags(db: AsyncSession, user_id: int) -> list[str]:
    """Return tag names for the given user."""
    result = await db.execute(
        select(Tag.name).where(Tag.user_id == user_id)
    )
    return [row[0] for row in result.all()]


async def store_suggestions(
    db: AsyncSession,
    user_id: int,
    suggestions: list[dict],
    source: IntegrationSource,
) -> list[SuggestedTask]:
    """Persist a list of extracted suggestion dicts, skipping duplicates.

    Each dict should have keys matching the SuggestedTask columns
    (source_id, source_title, suggested_title, etc.).
    Returns the list of newly created SuggestedTask rows.
    """
    if not suggestions:
        return []

    # Fetch all existing suggestions for the given user, source, and source_ids in one query
    source_ids = [s["source_id"] for s in suggestions]
    existing_result = await db.execute(
        select(SuggestedTask.source_id).where(
            SuggestedTask.user_id == user_id,
            SuggestedTask.source == source,
            SuggestedTask.source_id.in_(source_ids),
        )
    )
    existing_source_ids = {row[0] for row in existing_result.all()}

    created: list[SuggestedTask] = []
    for s in suggestions:
        if s["source_id"] in existing_source_ids:
            continue

        tags_json = json.dumps(s.get("suggested_tags")) if s.get("suggested_tags") else None

        due_date = s.get("suggested_due_date")
        if isinstance(due_date, str):
            try:
                due_date = date.fromisoformat(due_date)
            except ValueError:
                due_date = None

        row = SuggestedTask(
            user_id=user_id,
            source=source,
            source_id=s["source_id"],
            source_title=s.get("source_title", ""),
            source_url=s.get("source_url"),
            suggested_title=s["suggested_title"],
            suggested_description=s.get("suggested_description"),
            suggested_due_date=due_date,
            suggested_priority=s.get("suggested_priority"),
            suggested_project=s.get("suggested_project"),
            suggested_tags_json=tags_json,
            confidence=s.get("confidence", 0.0),
            status=SuggestionStatus.PENDING,
            extracted_at=datetime.now(timezone.utc),
        )
        db.add(row)
        created.append(row)

    if created:
        await db.flush()
        for row in created:
            await db.refresh(row)

    return created


async def accept_suggestion(
    db: AsyncSession,
    suggestion: SuggestedTask,
    user_id: int,
    overrides: dict | None = None,
) -> Task:
    """Create a real Task from a SuggestedTask and mark it accepted.

    *overrides* can contain title, description, due_date, priority to
    override the suggestion's values.
    """
    overrides = overrides or {}

    title = overrides.get("title") or suggestion.suggested_title
    description = overrides.get("description") or suggestion.suggested_description
    due_date = overrides.get("due_date") or suggestion.suggested_due_date
    priority = overrides.get("priority") if overrides.get("priority") is not None else (suggestion.suggested_priority or 3)

    if isinstance(due_date, str):
        try:
            due_date = date.fromisoformat(due_date)
        except ValueError:
            due_date = None

    # Try to find a matching project by name
    project_id: int | None = None
    project_name = overrides.get("project") or suggestion.suggested_project
    if project_name:
        result = await db.execute(
            select(Project.id).where(
                Project.user_id == user_id,
                Project.title == project_name,
            )
        )
        row = result.first()
        if row:
            project_id = row[0]

    # Fall back to user's first project if no match
    if project_id is None:
        result = await db.execute(
            select(Project.id).where(Project.user_id == user_id).limit(1)
        )
        row = result.first()
        if row:
            project_id = row[0]

    if project_id is None:
        raise ValueError("User has no projects to add the task to")

    task = Task(
        project_id=project_id,
        user_id=user_id,
        title=title,
        description=description,
        due_date=due_date,
        priority=priority,
        status=TaskStatus.TODO,
        depth=0,
    )
    db.add(task)

    suggestion.status = SuggestionStatus.ACCEPTED
    await db.flush()
    await db.refresh(task)
    return task
