import csv
import io
import json
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, UploadFile
from fastapi.responses import StreamingResponse
from sqlalchemy import select, delete
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.database import get_db
from app.middleware.auth import get_current_user
from app.models import (
    Goal,
    GoalStatus,
    Habit,
    HabitCompletion,
    HabitFrequency,
    Project,
    ProjectStatus,
    Tag,
    Task,
    TaskStatus,
    User,
)
from app.schemas.export import ImportResponse

router = APIRouter(tags=["export"])


def _serialize_entity(entity) -> dict:
    data = {}
    for col in entity.__table__.columns:
        val = getattr(entity, col.name)
        if hasattr(val, "value"):
            val = val.value
        if isinstance(val, (datetime,)):
            val = val.isoformat()
        elif hasattr(val, "isoformat"):
            val = val.isoformat()
        data[col.name] = val
    return data


@router.get("/export/json")
async def export_json(
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    # Fetch all user data
    goals_result = await db.execute(
        select(Goal).where(Goal.user_id == current_user.id)
    )
    projects_result = await db.execute(
        select(Project).where(Project.user_id == current_user.id)
    )
    tasks_result = await db.execute(
        select(Task).where(Task.user_id == current_user.id)
    )
    tags_result = await db.execute(
        select(Tag).where(Tag.user_id == current_user.id)
    )
    habits_result = await db.execute(
        select(Habit)
        .options(selectinload(Habit.completions))
        .where(Habit.user_id == current_user.id)
    )

    export_data = {
        "exported_at": datetime.now(timezone.utc).isoformat(),
        "goals": [_serialize_entity(g) for g in goals_result.scalars().all()],
        "projects": [_serialize_entity(p) for p in projects_result.scalars().all()],
        "tasks": [_serialize_entity(t) for t in tasks_result.scalars().all()],
        "tags": [_serialize_entity(t) for t in tags_result.scalars().all()],
        "habits": [
            {
                **_serialize_entity(h),
                "completions": [_serialize_entity(c) for c in h.completions],
            }
            for h in habits_result.scalars().all()
        ],
    }

    content = json.dumps(export_data, indent=2, default=str)
    return StreamingResponse(
        io.BytesIO(content.encode()),
        media_type="application/json",
        headers={"Content-Disposition": "attachment; filename=prismtask_export.json"},
    )


@router.get("/export/csv")
async def export_csv(
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    tasks_result = await db.execute(
        select(Task).where(Task.user_id == current_user.id).order_by(Task.created_at)
    )
    tasks = tasks_result.scalars().all()

    output = io.StringIO()
    writer = csv.writer(output)
    writer.writerow([
        "id", "project_id", "parent_id", "title", "description",
        "status", "priority", "due_date", "planned_date", "completed_at",
        "urgency_score", "sort_order", "created_at",
    ])
    for t in tasks:
        writer.writerow([
            t.id, t.project_id, t.parent_id, t.title, t.description,
            t.status.value if hasattr(t.status, "value") else t.status,
            t.priority, t.due_date, t.planned_date, t.completed_at,
            t.urgency_score, t.sort_order, t.created_at,
        ])

    content = output.getvalue()
    return StreamingResponse(
        io.BytesIO(content.encode()),
        media_type="text/csv",
        headers={"Content-Disposition": "attachment; filename=prismtask_tasks.csv"},
    )


@router.post("/import/json", response_model=ImportResponse)
async def import_json(
    file: UploadFile,
    mode: str = "merge",
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    if mode not in ("merge", "replace"):
        raise HTTPException(status_code=400, detail="Mode must be 'merge' or 'replace'")

    content = await file.read()
    try:
        data = json.loads(content)
    except json.JSONDecodeError:
        raise HTTPException(status_code=400, detail="Invalid JSON file")

    if mode == "replace":
        # Delete existing data
        for model in [Task, Project, Goal, Tag, Habit]:
            await db.execute(
                delete(model).where(model.user_id == current_user.id)
            )
        await db.flush()

    counts = {"tasks": 0, "projects": 0, "tags": 0, "habits": 0}

    # Import goals first (projects depend on them)
    for goal_data in data.get("goals", []):
        goal_data.pop("id", None)
        goal_data["user_id"] = current_user.id
        if "status" in goal_data:
            goal_data["status"] = GoalStatus(goal_data["status"])
        db.add(Goal(**goal_data))

    # Import projects
    for proj_data in data.get("projects", []):
        proj_data.pop("id", None)
        proj_data["user_id"] = current_user.id
        if "status" in proj_data:
            proj_data["status"] = ProjectStatus(proj_data["status"])
        db.add(Project(**proj_data))
        counts["projects"] += 1

    # Import tags
    for tag_data in data.get("tags", []):
        tag_data.pop("id", None)
        tag_data["user_id"] = current_user.id
        db.add(Tag(**tag_data))
        counts["tags"] += 1

    await db.flush()

    # Import tasks
    for task_data in data.get("tasks", []):
        task_data.pop("id", None)
        task_data["user_id"] = current_user.id
        if "status" in task_data:
            task_data["status"] = TaskStatus(task_data["status"])
        db.add(Task(**task_data))
        counts["tasks"] += 1

    # Import habits
    for habit_data in data.get("habits", []):
        completions_data = habit_data.pop("completions", [])
        habit_data.pop("id", None)
        habit_data["user_id"] = current_user.id
        if "frequency" in habit_data:
            habit_data["frequency"] = HabitFrequency(habit_data["frequency"])
        habit = Habit(**habit_data)
        db.add(habit)
        await db.flush()
        await db.refresh(habit)
        for comp_data in completions_data:
            comp_data.pop("id", None)
            comp_data["habit_id"] = habit.id
            db.add(HabitCompletion(**comp_data))
        counts["habits"] += 1

    await db.flush()

    return ImportResponse(
        tasks_imported=counts["tasks"],
        projects_imported=counts["projects"],
        tags_imported=counts["tags"],
        habits_imported=counts["habits"],
        mode=mode,
    )
