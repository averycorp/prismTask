import json
from datetime import datetime, timezone
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.middleware.auth import get_current_user
from app.models import Project, Tag, Task, TaskTag, TaskTemplate, User
from app.schemas.template import (
    TemplateCreate,
    TemplateFromTaskRequest,
    TemplateResponse,
    TemplateUpdate,
    TemplateUseRequest,
    TemplateUseResponse,
)

router = APIRouter(tags=["templates"])


def _template_response(tmpl: TaskTemplate) -> TemplateResponse:
    return TemplateResponse(
        id=tmpl.id,
        user_id=tmpl.user_id,
        name=tmpl.name,
        description=tmpl.description,
        icon=tmpl.icon,
        category=tmpl.category,
        template_title=tmpl.template_title,
        template_description=tmpl.template_description,
        template_priority=tmpl.template_priority,
        template_project_id=tmpl.template_project_id,
        template_tags_json=tmpl.template_tags_json,
        template_recurrence_json=tmpl.template_recurrence_json,
        template_duration=tmpl.template_duration,
        template_subtasks_json=tmpl.template_subtasks_json,
        is_built_in=bool(tmpl.is_built_in),
        usage_count=tmpl.usage_count or 0,
        last_used_at=tmpl.last_used_at,
        created_at=tmpl.created_at,
        updated_at=tmpl.updated_at,
    )


async def _get_template_for_user(
    template_id: int, user: User, db: AsyncSession
) -> TaskTemplate:
    result = await db.execute(
        select(TaskTemplate).where(
            TaskTemplate.id == template_id, TaskTemplate.user_id == user.id
        )
    )
    tmpl = result.scalar_one_or_none()
    if not tmpl:
        raise HTTPException(status_code=404, detail="Template not found")
    return tmpl


async def _verify_project_for_user(
    project_id: int, user: User, db: AsyncSession
) -> Project:
    result = await db.execute(
        select(Project).where(Project.id == project_id, Project.user_id == user.id)
    )
    project = result.scalar_one_or_none()
    if not project:
        raise HTTPException(status_code=404, detail="Project not found")
    return project


@router.get("", response_model=list[TemplateResponse])
async def list_templates(
    category: Optional[str] = Query(default=None),
    sort_by: str = Query(default="usage_count"),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    query = select(TaskTemplate).where(TaskTemplate.user_id == current_user.id)
    if category is not None:
        query = query.where(TaskTemplate.category == category)

    if sort_by == "name":
        query = query.order_by(TaskTemplate.name)
    elif sort_by == "created_at":
        query = query.order_by(TaskTemplate.created_at.desc())
    else:
        # Default: usage_count DESC (most used first)
        query = query.order_by(TaskTemplate.usage_count.desc(), TaskTemplate.name)

    result = await db.execute(query)
    return [_template_response(t) for t in result.scalars().all()]


@router.post("", response_model=TemplateResponse, status_code=status.HTTP_201_CREATED)
async def create_template(
    data: TemplateCreate,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    tmpl_data = data.model_dump(exclude_unset=True)

    # If project is specified, verify ownership
    project_id = tmpl_data.get("template_project_id")
    if project_id is not None:
        await _verify_project_for_user(project_id, current_user, db)

    tmpl = TaskTemplate(user_id=current_user.id, **tmpl_data)
    db.add(tmpl)
    await db.flush()
    await db.refresh(tmpl)
    return _template_response(tmpl)


@router.post(
    "/from-task/{task_id}",
    response_model=TemplateResponse,
    status_code=status.HTTP_201_CREATED,
)
async def create_template_from_task(
    task_id: int,
    data: TemplateFromTaskRequest,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    # Verify task belongs to user
    task_result = await db.execute(
        select(Task).where(Task.id == task_id, Task.user_id == current_user.id)
    )
    task = task_result.scalar_one_or_none()
    if not task:
        raise HTTPException(status_code=404, detail="Task not found")

    # Collect tag IDs associated with this task
    tt_result = await db.execute(
        select(TaskTag.tag_id).where(TaskTag.task_id == task_id)
    )
    tag_ids = [row[0] for row in tt_result.all()]
    tags_json = json.dumps(tag_ids) if tag_ids else None

    # Collect subtask titles
    sub_result = await db.execute(
        select(Task.title)
        .where(Task.parent_id == task_id)
        .order_by(Task.sort_order, Task.created_at)
    )
    subtask_titles = [row[0] for row in sub_result.all()]
    subtasks_json = json.dumps(subtask_titles) if subtask_titles else None

    tmpl = TaskTemplate(
        user_id=current_user.id,
        name=data.name,
        description=data.description,
        icon=data.icon,
        category=data.category,
        template_title=task.title,
        template_description=task.description,
        template_priority=task.priority,
        template_project_id=task.project_id,
        template_tags_json=tags_json,
        template_recurrence_json=task.recurrence_json,
        template_duration=None,
        template_subtasks_json=subtasks_json,
    )
    db.add(tmpl)
    await db.flush()
    await db.refresh(tmpl)
    return _template_response(tmpl)


@router.get("/{template_id}", response_model=TemplateResponse)
async def get_template(
    template_id: int,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    tmpl = await _get_template_for_user(template_id, current_user, db)
    return _template_response(tmpl)


@router.patch("/{template_id}", response_model=TemplateResponse)
async def update_template(
    template_id: int,
    data: TemplateUpdate,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    tmpl = await _get_template_for_user(template_id, current_user, db)
    update_data = data.model_dump(exclude_unset=True)

    # If project override, verify ownership
    if "template_project_id" in update_data and update_data["template_project_id"] is not None:
        await _verify_project_for_user(
            update_data["template_project_id"], current_user, db
        )

    for key, value in update_data.items():
        setattr(tmpl, key, value)

    # If this was a built-in template and the user modified it, clear the flag
    if tmpl.is_built_in and update_data:
        tmpl.is_built_in = False

    await db.flush()
    await db.refresh(tmpl)
    return _template_response(tmpl)


@router.delete("/{template_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_template(
    template_id: int,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    tmpl = await _get_template_for_user(template_id, current_user, db)
    await db.delete(tmpl)


@router.post("/{template_id}/use", response_model=TemplateUseResponse)
async def use_template(
    template_id: int,
    data: Optional[TemplateUseRequest] = None,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    tmpl = await _get_template_for_user(template_id, current_user, db)
    overrides = data or TemplateUseRequest()

    # Resolve project: override > template > error
    project_id = overrides.project_id or tmpl.template_project_id
    if project_id is None:
        raise HTTPException(
            status_code=400,
            detail="Template has no project set and no project_id override provided",
        )
    await _verify_project_for_user(project_id, current_user, db)

    title = tmpl.template_title or tmpl.name
    priority = tmpl.template_priority if tmpl.template_priority is not None else 3

    new_task = Task(
        project_id=project_id,
        user_id=current_user.id,
        parent_id=None,
        title=title,
        description=tmpl.template_description,
        priority=priority,
        due_date=overrides.due_date,
        recurrence_json=tmpl.template_recurrence_json,
        depth=0,
    )
    db.add(new_task)
    await db.flush()
    await db.refresh(new_task)

    # Create subtasks if specified
    if tmpl.template_subtasks_json:
        try:
            subtask_titles = json.loads(tmpl.template_subtasks_json)
        except (ValueError, TypeError):
            subtask_titles = []
        if isinstance(subtask_titles, list):
            for idx, sub_title in enumerate(subtask_titles):
                if not isinstance(sub_title, str) or not sub_title.strip():
                    continue
                subtask = Task(
                    project_id=project_id,
                    user_id=current_user.id,
                    parent_id=new_task.id,
                    title=sub_title,
                    priority=priority,
                    depth=1,
                    sort_order=idx,
                )
                db.add(subtask)

    # Create tag associations if specified
    if tmpl.template_tags_json:
        try:
            tag_ids = json.loads(tmpl.template_tags_json)
        except (ValueError, TypeError):
            tag_ids = []
        if isinstance(tag_ids, list):
            valid_tag_ids = [tid for tid in tag_ids if isinstance(tid, int)]
            if valid_tag_ids:
                # Verify all tags belong to the user in a single query
                tag_result = await db.execute(
                    select(Tag.id).where(
                        Tag.id.in_(valid_tag_ids), Tag.user_id == current_user.id
                    )
                )
                user_tag_ids = set(tag_result.scalars().all())
                for tag_id in valid_tag_ids:
                    if tag_id in user_tag_ids:
                        db.add(TaskTag(task_id=new_task.id, tag_id=tag_id))

    # Update usage tracking
    tmpl.usage_count = (tmpl.usage_count or 0) + 1
    tmpl.last_used_at = datetime.now(timezone.utc)

    await db.flush()

    return TemplateUseResponse(
        task_id=new_task.id,
        message=f"Task created from template '{tmpl.name}'",
    )
