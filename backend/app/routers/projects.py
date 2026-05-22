from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.database import get_db
from app.middleware.auth import get_current_user
from app.models import Goal, Project, ProjectMember, ProjectStatus, User
from app.schemas.project import (
    ProjectCreate,
    ProjectDetailResponse,
    ProjectResponse,
    ProjectUpdate,
)

router = APIRouter(tags=["projects"])


async def _verify_goal_ownership(goal_id: int, user: User, db: AsyncSession) -> Goal:
    result = await db.execute(
        select(Goal).where(Goal.id == goal_id, Goal.user_id == user.id)
    )
    goal = result.scalar_one_or_none()
    if not goal:
        raise HTTPException(status_code=404, detail="Goal not found")
    return goal


def _project_response(p: Project) -> ProjectResponse:
    return ProjectResponse(
        id=p.id,
        goal_id=p.goal_id,
        user_id=p.user_id,
        title=p.title,
        description=p.description,
        status=p.status.value if hasattr(p.status, "value") else p.status,
        due_date=p.due_date,
        sort_order=p.sort_order,
        created_at=p.created_at,
        updated_at=p.updated_at,
    )


@router.get("/goals/{goal_id}/projects", response_model=list[ProjectResponse])
async def list_projects(
    goal_id: int,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    await _verify_goal_ownership(goal_id, current_user, db)
    result = await db.execute(
        select(Project)
        .where(Project.goal_id == goal_id)
        .order_by(Project.sort_order, Project.created_at)
    )
    return [_project_response(p) for p in result.scalars().all()]


@router.post(
    "/goals/{goal_id}/projects",
    response_model=ProjectResponse,
    status_code=status.HTTP_201_CREATED,
)
async def create_project(
    goal_id: int,
    data: ProjectCreate,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    await _verify_goal_ownership(goal_id, current_user, db)
    project_data = data.model_dump(exclude_unset=True)
    if "status" in project_data:
        project_data["status"] = ProjectStatus(project_data["status"])
    project = Project(goal_id=goal_id, user_id=current_user.id, **project_data)
    db.add(project)
    await db.flush()
    await db.refresh(project)

    # Auto-create owner membership for the project creator
    owner_member = ProjectMember(
        project_id=project.id,
        user_id=current_user.id,
        role="owner",
    )
    db.add(owner_member)
    await db.flush()

    return _project_response(project)


async def _get_project_for_user(
    project_id: int, user: User, db: AsyncSession
) -> Project:
    result = await db.execute(
        select(Project).where(Project.id == project_id, Project.user_id == user.id)
    )
    project = result.scalar_one_or_none()
    if not project:
        raise HTTPException(status_code=404, detail="Project not found")
    return project


@router.get("/projects/{project_id}", response_model=ProjectDetailResponse)
async def get_project(
    project_id: int,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(Project)
        .options(selectinload(Project.tasks))
        .where(Project.id == project_id, Project.user_id == current_user.id)
    )
    project = result.scalar_one_or_none()
    if not project:
        raise HTTPException(status_code=404, detail="Project not found")

    resp = _project_response(project).model_dump()
    resp["tasks"] = [
        {
            "id": t.id,
            "title": t.title,
            "status": t.status.value if hasattr(t.status, "value") else t.status,
            "priority": t.priority,
            "due_date": t.due_date,
            "sort_order": t.sort_order,
            "depth": t.depth,
            "created_at": t.created_at,
        }
        for t in project.tasks
    ]
    return ProjectDetailResponse(**resp)


@router.patch("/projects/{project_id}", response_model=ProjectResponse)
async def update_project(
    project_id: int,
    data: ProjectUpdate,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    project = await _get_project_for_user(project_id, current_user, db)
    update_data = data.model_dump(exclude_unset=True)
    if "status" in update_data:
        update_data["status"] = ProjectStatus(update_data["status"])
    for key, value in update_data.items():
        setattr(project, key, value)
    await db.flush()
    await db.refresh(project)
    return _project_response(project)


@router.delete("/projects/{project_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_project(
    project_id: int,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    project = await _get_project_for_user(project_id, current_user, db)
    await db.delete(project)


@router.patch("/projects/reorder", status_code=status.HTTP_200_OK)
async def reorder_projects(
    items: list[dict],
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    project_orders = {}
    for item in items:
        project_id = item.get("id")
        sort_order = item.get("sort_order")
        if project_id is None or sort_order is None:
            raise HTTPException(
                status_code=400, detail="Each item must have 'id' and 'sort_order'"
            )
        project_orders[project_id] = sort_order

    if not project_orders:
        return {"detail": "Projects reordered"}

    result = await db.execute(
        select(Project).where(
            Project.id.in_(project_orders.keys()), Project.user_id == current_user.id
        )
    )
    projects = result.scalars().all()

    found_projects = {p.id: p for p in projects}

    for item in items:
        project_id = item["id"]
        if project_id not in found_projects:
            raise HTTPException(
                status_code=404, detail=f"Project {project_id} not found"
            )
        found_projects[project_id].sort_order = item["sort_order"]

    await db.flush()
    return {"detail": "Projects reordered"}
