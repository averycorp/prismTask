import json
import logging
import os
from datetime import date, datetime, timedelta, timezone

from fastapi import APIRouter, Body, Depends, HTTPException, Request, status
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.config import settings
from app.database import get_db
from app.middleware.ai_gate import require_ai_features_enabled
from app.middleware.auth import get_current_user
from app.middleware.rate_limit import RateLimiter, import_parse_rate_limiter
from app.models import Project, Task, TaskStatus, User
from app.schemas.import_parse import (
    ParseChecklistRequest,
    ParseChecklistResponse,
    ParseImportRequest,
    ParseImportResponse,
)
from app.schemas.nlp import ParseRequest, ParseResponse
from app.schemas.task import SubtaskCreate, TaskCreate, TaskResponse, TaskUpdate

# IP-keyed rate limiter for the unauthenticated /tasks/parse endpoint.
# Keeps the endpoint usable for the web landing demo without exposing the
# Anthropic API budget to unbounded calls.
parse_rate_limiter = RateLimiter(max_requests=20, window_seconds=60)

logger = logging.getLogger(__name__)

router = APIRouter(tags=["tasks"])

MAX_SUBTASK_DEPTH = 1


def _task_to_response(task: Task, subtasks: list | None = None) -> TaskResponse:
    child_responses = []
    if subtasks is not None:
        child_responses = [_task_to_response(s, []) for s in subtasks]
    return TaskResponse(
        id=task.id,
        project_id=task.project_id,
        user_id=task.user_id,
        parent_id=task.parent_id,
        title=task.title,
        description=task.description,
        status=task.status.value if hasattr(task.status, "value") else task.status,
        priority=task.priority,
        due_date=task.due_date,
        completed_at=task.completed_at,
        eisenhower_quadrant=task.eisenhower_quadrant,
        eisenhower_updated_at=task.eisenhower_updated_at,
        sort_order=task.sort_order,
        depth=task.depth,
        progress_percent=task.progress_percent,
        created_at=task.created_at,
        updated_at=task.updated_at,
        subtasks=child_responses,
    )


async def _verify_project_ownership(project_id: int, user: User, db: AsyncSession) -> Project:
    result = await db.execute(
        select(Project).where(Project.id == project_id, Project.user_id == user.id)
    )
    project = result.scalar_one_or_none()
    if not project:
        raise HTTPException(status_code=404, detail="Project not found")
    return project


async def _get_task_for_user(task_id: int, user: User, db: AsyncSession) -> Task:
    result = await db.execute(
        select(Task)
        .options(selectinload(Task.subtasks))
        .where(Task.id == task_id, Task.user_id == user.id)
    )
    task = result.scalar_one_or_none()
    if not task:
        raise HTTPException(status_code=404, detail="Task not found")
    return task


@router.get("/projects/{project_id}/tasks", response_model=list[TaskResponse])
async def list_tasks(
    project_id: int,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    await _verify_project_ownership(project_id, current_user, db)
    result = await db.execute(
        select(Task)
        .options(selectinload(Task.subtasks))
        .where(Task.project_id == project_id, Task.parent_id.is_(None))
        .order_by(Task.sort_order, Task.created_at)
    )
    tasks = result.scalars().all()
    return [_task_to_response(t, t.subtasks) for t in tasks]


@router.post(
    "/projects/{project_id}/tasks",
    response_model=TaskResponse,
    status_code=status.HTTP_201_CREATED,
)
async def create_task(
    project_id: int,
    data: TaskCreate,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    await _verify_project_ownership(project_id, current_user, db)
    task_data = data.model_dump(exclude_unset=True)
    if "status" in task_data:
        task_data["status"] = TaskStatus(task_data["status"])
    task = Task(project_id=project_id, user_id=current_user.id, depth=0, **task_data)
    db.add(task)
    await db.flush()
    await db.refresh(task)
    return _task_to_response(task, [])


@router.get("/tasks/parse-debug")
async def parse_debug(current_user: User = Depends(get_current_user)):
    """Diagnostic endpoint for the NLP parser configuration.

    Restricted to admin users outside of non-production environments so
    operators don't leak infrastructure details to callers.

    Defined before `/tasks/{task_id}` so FastAPI matches the literal
    path first; otherwise `parse-debug` is treated as a `task_id` value
    and rejected with 422.
    """
    if settings.is_production and not current_user.is_admin:
        raise HTTPException(status_code=404, detail="Not found")

    api_key = os.environ.get("ANTHROPIC_API_KEY") or settings.ANTHROPIC_API_KEY or ""
    try:
        import anthropic  # noqa: F401
        anthropic_installed = True
    except ImportError:
        anthropic_installed = False

    return {
        "api_key_configured": bool(api_key),
        "model": "claude-haiku-4-5-20251001",
        "anthropic_installed": anthropic_installed,
    }


@router.get("/tasks/{task_id}", response_model=TaskResponse)
async def get_task(
    task_id: int,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    task = await _get_task_for_user(task_id, current_user, db)
    return _task_to_response(task, task.subtasks)


@router.patch("/tasks/{task_id}", response_model=TaskResponse)
async def update_task(
    task_id: int,
    data: TaskUpdate,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    task = await _get_task_for_user(task_id, current_user, db)
    update_data = data.model_dump(exclude_unset=True)

    if "status" in update_data:
        new_status = TaskStatus(update_data["status"])
        update_data["status"] = new_status
        if new_status == TaskStatus.DONE and task.status != TaskStatus.DONE:
            update_data["completed_at"] = datetime.now(timezone.utc)
        elif new_status != TaskStatus.DONE:
            update_data["completed_at"] = None

    for key, value in update_data.items():
        setattr(task, key, value)

    await db.flush()
    await db.refresh(task)
    # Re-fetch to get updated subtasks
    result = await db.execute(
        select(Task)
        .options(selectinload(Task.subtasks))
        .where(Task.id == task.id)
    )
    task = result.scalar_one()
    return _task_to_response(task, task.subtasks)


@router.delete("/tasks/{task_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_task(
    task_id: int,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    task = await _get_task_for_user(task_id, current_user, db)
    await db.delete(task)


@router.post("/tasks/{task_id}/subtasks", response_model=TaskResponse, status_code=status.HTTP_201_CREATED)
async def create_subtask(
    task_id: int,
    data: SubtaskCreate,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    parent = await _get_task_for_user(task_id, current_user, db)

    if parent.depth >= MAX_SUBTASK_DEPTH:
        raise HTTPException(
            status_code=400,
            detail=f"Maximum subtask depth of {MAX_SUBTASK_DEPTH} exceeded",
        )

    task_data = data.model_dump(exclude_unset=True)
    if "status" in task_data:
        task_data["status"] = TaskStatus(task_data["status"])

    subtask = Task(
        project_id=parent.project_id,
        user_id=current_user.id,
        parent_id=parent.id,
        depth=parent.depth + 1,
        **task_data,
    )
    db.add(subtask)
    await db.flush()
    await db.refresh(subtask)
    return _task_to_response(subtask, [])


def _logical_today(
    now: datetime,
    sod_hour: int | None,
    sod_minute: int | None,
) -> date:
    """Return the user's *logical* calendar date for [now], honoring
    Start-of-Day when the client supplied it.

    Server is intentionally TZ-naive (no auth context, no per-user TZ —
    ``date.today()`` already runs in the server's local zone), so we
    honor SoD by comparing wall-clock minutes. With SoD = 4 AM and the
    server's local time = 02:00 the logical day is "yesterday", so the
    parser receives yesterday's date — matching the on-device behavior.

    When ``sod_hour`` is None (older clients), falls back to the
    calendar date for [now] — exactly what ``date.today()`` returns.
    """
    today = now.date()
    if sod_hour is None:
        return today
    minutes_now = now.hour * 60 + now.minute
    minutes_sod = sod_hour * 60 + (sod_minute or 0)
    if minutes_now < minutes_sod:
        return today - timedelta(days=1)
    return today


@router.post(
    "/tasks/parse",
    response_model=ParseResponse,
    dependencies=[Depends(require_ai_features_enabled)],
)
async def parse_task(data: ParseRequest, request: Request):
    """
    Parse free-text task input into structured fields.

    This is a utility endpoint and does not require authentication — it has no
    user context and simply runs the NLP parser against the supplied text.
    Because there is no user, project-name suggestions are not available.

    Rate-limited per IP to keep the Anthropic API budget bounded.
    """
    parse_rate_limiter.check(request)
    try:
        from app.services.nlp_parser import parse_task_input
        today = _logical_today(
            datetime.now(),
            sod_hour=data.start_of_day_hour,
            sod_minute=data.start_of_day_minute,
        )
        parsed = parse_task_input(data.text, [], today)
    except (ValueError, RuntimeError) as e:
        raise HTTPException(status_code=422, detail=str(e))

    return ParseResponse(**parsed.model_dump(), needs_confirmation=True)


def _call_haiku(api_key: str, system_prompt: str, user_content: str, max_tokens: int) -> str:
    """Call Claude Haiku and return the raw text response. Raises on error."""
    try:
        import anthropic
    except ImportError as exc:
        raise RuntimeError("anthropic package is not installed") from exc

    client = anthropic.Anthropic(api_key=api_key)
    message = client.messages.create(
        model="claude-haiku-4-5-20251001",
        max_tokens=max_tokens,
        system=system_prompt,
        messages=[{"role": "user", "content": user_content}],
    )
    text = message.content[0].text.strip()
    # Strip markdown code fences if present
    if text.startswith("```"):
        text = text.split("\n", 1)[1] if "\n" in text else text[3:]
    if text.endswith("```"):
        text = text[:-3]
    return text.strip()


@router.post(
    "/tasks/parse-import",
    response_model=ParseImportResponse,
    dependencies=[Depends(require_ai_features_enabled)],
)
async def parse_import_list(
    data: ParseImportRequest,
    current_user: User = Depends(get_current_user),
) -> ParseImportResponse:
    """Parse a todo list / JSX schedule from raw text using Claude Haiku.

    Returns a flat name + items structure for import into PrismTask.
    Requires JWT auth. Rate limited to 10 calls per user per hour.
    Returns 503 if ANTHROPIC_API_KEY is not configured server-side.
    Returns 502 if the Claude call fails.
    """
    import_parse_rate_limiter.check(current_user.id, is_admin=current_user.is_admin)

    api_key = os.environ.get("ANTHROPIC_API_KEY") or settings.ANTHROPIC_API_KEY
    if not api_key:
        raise HTTPException(
            status_code=503,
            detail="AI import parsing is unavailable — server ANTHROPIC_API_KEY is not configured",
        )

    year = datetime.now(timezone.utc).year
    system_prompt = f"""You are a structured data extractor. The user will give you the contents of a JSX/TSX file or a text list that contains a to-do list, schedule, or checklist.

Extract all actionable items and return ONLY a JSON object with this exact schema (no other text):

{{
  "name": "string or null \u2014 the list/schedule title if apparent",
  "items": [
    {{
      "title": "string \u2014 the task/item description",
      "description": "string or null \u2014 extra details, duration, notes",
      "dueDate": "string or null \u2014 date in YYYY-MM-DD format, use year {year} if not specified",
      "priority": 0,
      "completed": false,
      "subtasks": []
    }}
  ]
}}

Rules:
- Skip items that are days off, holidays, rest days, or breaks
- For exam/test items, set priority to 4 (urgent) and prefix title with "EXAM: "
- For other items, keep priority at 0
- Extract dates and convert to YYYY-MM-DD
- Include duration/time info in the description field
- The completed field should reflect the done/checked state from the source
- Subtasks should use the same object schema
- Return ONLY valid JSON, no explanation"""

    try:
        text = _call_haiku(api_key, system_prompt, data.content, max_tokens=4096)
        parsed = json.loads(text)
        return ParseImportResponse(**parsed)
    except HTTPException:
        raise
    except Exception as exc:
        logger.error("parse-import failed: %s", exc, exc_info=True)
        raise HTTPException(
            status_code=502,
            detail="AI parsing temporarily unavailable",
        ) from exc


@router.post(
    "/tasks/parse-checklist",
    response_model=ParseChecklistResponse,
    dependencies=[Depends(require_ai_features_enabled)],
)
async def parse_checklist(
    data: ParseChecklistRequest,
    current_user: User = Depends(get_current_user),
) -> ParseChecklistResponse:
    """Parse a course syllabus / comprehensive schedule from raw text using Claude Haiku.

    Returns a structured result (course / project / tags / tasks) for import
    into PrismTask schoolwork mode.
    Requires JWT auth. Rate limited to 10 calls per user per hour.
    Returns 503 if ANTHROPIC_API_KEY is not configured server-side.
    Returns 502 if the Claude call fails.
    """
    import_parse_rate_limiter.check(current_user.id, is_admin=current_user.is_admin)

    api_key = os.environ.get("ANTHROPIC_API_KEY") or settings.ANTHROPIC_API_KEY
    if not api_key:
        raise HTTPException(
            status_code=503,
            detail="AI import parsing is unavailable — server ANTHROPIC_API_KEY is not configured",
        )

    year = datetime.now(timezone.utc).year
    system_prompt = f"""You are a structured data extractor for a task management app. The user will give you the contents of a JSX/TSX file, a text list, schedule, syllabus, or other content.

Your job is to extract EVERY actionable item, preserving ALL detail from the original. Do not summarize or skip anything. Replicate every aspect of the source material.

Return ONLY a JSON object with this exact schema (no other text):

{{
  "course": {{
    "code": "string \u2014 course code like CSCA 5454, or a short identifier if not a course",
    "name": "string \u2014 full name or title"
  }},
  "project": {{
    "name": "string \u2014 project display name (e.g. 'CSCA 5454 \u2014 Data Structures')",
    "color": "string \u2014 hex color like #4A90D9",
    "icon": "string \u2014 single emoji for the project"
  }},
  "tags": [
    {{
      "name": "string \u2014 tag name (e.g. 'exam', 'video', 'reading', 'code', 'assignment')",
      "color": "string \u2014 hex color"
    }}
  ],
  "tasks": [
    {{
      "title": "string \u2014 the task description, faithfully preserved from source",
      "description": "string or null \u2014 extra details, notes, context, URLs, instructions from the source",
      "dueDate": "string or null \u2014 YYYY-MM-DD, use year {year} if not specified",
      "priority": 0,
      "completed": false,
      "tags": ["string \u2014 tag names that apply to this task"],
      "estimatedMinutes": null,
      "subtasks": [
        {{
          "title": "string",
          "description": "string or null",
          "dueDate": "string or null",
          "priority": 0,
          "completed": false,
          "tags": [],
          "estimatedMinutes": null,
          "subtasks": []
        }}
      ]
    }}
  ]
}}

Rules:
- Extract EVERY item from the source. Do not summarize, merge, or skip items.
- Preserve exact titles, descriptions, notes, and any metadata from the original.
- If the source has sections/phases/weeks, group items by due date rather than flattening structure \u2014 use subtasks if an item clearly has sub-items.
- Skip only true off-days, holidays, rest days, or explicit breaks. Keep buffer/review days as tasks.
- For exams/tests/quizzes: set priority to 4 (urgent) and prefix title with "EXAM: "
- For assignments/homework: set priority to 2 (medium)
- For videos/lectures: set priority to 0 (none)
- For readings: set priority to 1 (low)
- Extract all dates and convert to YYYY-MM-DD format
- If content has duration/time estimates (e.g. "30 min", "1.5 hrs"), set estimatedMinutes to the number of minutes
- The completed field should reflect any done/checked/completed state from the source
- Assign appropriate tags to each task based on its type (video, assignment, exam, reading, code, etc.)
- Create tag entries for all unique task types you encounter \u2014 pick semantically meaningful colors
- For the project, pick an appropriate emoji icon and color based on the subject matter
- When the source clearly groups items under a section / week / sprint / phase heading, set each task's `phaseName` to that heading (must match a `phases[].name` below). Otherwise omit `phaseName`.
- Return ONLY valid JSON, no explanation or markdown

Optional project-structure fields (include only when the source clearly expresses them; otherwise return empty arrays):

  "phases": [
    {{"name": "string", "description": "string or null", "startDate": "YYYY-MM-DD or null", "endDate": "YYYY-MM-DD or null", "orderIndex": 0}}
  ],
  "risks": [
    {{"title": "string", "description": "string or null", "level": "LOW|MEDIUM|HIGH"}}
  ],
  "externalAnchors": [
    {{"title": "string", "type": "calendar_deadline|numeric_threshold|boolean_gate", "phaseName": "string or null", "targetDate": "YYYY-MM-DD or null"}}
  ],
  "taskDependencies": [
    {{"blockerTitle": "string (must match a tasks[].title)", "blockedTitle": "string (must match a tasks[].title)"}}
  ]

When extracting these:
- Phases: only emit when the source explicitly groups items by week / phase / sprint / unit / module. Use the section heading as `name`. orderIndex is 0-based per source order.
- Risks: only emit when the source explicitly calls out risks, blockers, dependencies on external factors, or open questions.
- externalAnchors: only emit for items that are date-pinned events the project must align to (deliverable deadlines, exam dates, demo dates) — phaseName references a phases[].name when applicable.
- taskDependencies: only emit when the source explicitly uses words like "blocks", "depends on", "after", "requires" linking two task items by title."""

    try:
        text = _call_haiku(api_key, system_prompt, data.content, max_tokens=8192)
        parsed = json.loads(text)
        return ParseChecklistResponse(**parsed)
    except HTTPException:
        raise
    except Exception as exc:
        logger.error("parse-checklist failed: %s", exc, exc_info=True)
        raise HTTPException(
            status_code=502,
            detail="AI parsing temporarily unavailable",
        ) from exc


class ReorderItem(BaseModel):
    id: int
    sort_order: int


@router.patch("/tasks/reorder", status_code=status.HTTP_200_OK)
async def reorder_tasks(
    items: list[ReorderItem] = Body(..., max_length=500),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    for item in items:
        result = await db.execute(
            select(Task).where(Task.id == item.id, Task.user_id == current_user.id)
        )
        task = result.scalar_one_or_none()
        if not task:
            raise HTTPException(status_code=404, detail=f"Task {item.id} not found")
        task.sort_order = item.sort_order
    await db.flush()
    return {"detail": "Tasks reordered"}
