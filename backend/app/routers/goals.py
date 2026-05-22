from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.database import get_db
from app.middleware.auth import get_current_user
from app.models import Goal, GoalStatus, User
from app.schemas.goal import GoalCreate, GoalDetailResponse, GoalResponse, GoalUpdate

router = APIRouter(prefix="/goals", tags=["goals"])


@router.get("", response_model=list[GoalResponse])
async def list_goals(
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(Goal)
        .where(Goal.user_id == current_user.id)
        .order_by(Goal.sort_order, Goal.created_at)
    )
    goals = result.scalars().all()
    return [_goal_response(g) for g in goals]


@router.post("", response_model=GoalResponse, status_code=status.HTTP_201_CREATED)
async def create_goal(
    data: GoalCreate,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    goal_data = data.model_dump(exclude_unset=True)
    if "status" in goal_data:
        goal_data["status"] = GoalStatus(goal_data["status"])
    goal = Goal(user_id=current_user.id, **goal_data)
    db.add(goal)
    await db.flush()
    await db.refresh(goal)
    return _goal_response(goal)


@router.get("/{goal_id}", response_model=GoalDetailResponse)
async def get_goal(
    goal_id: int,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(Goal)
        .options(selectinload(Goal.projects))
        .where(Goal.id == goal_id, Goal.user_id == current_user.id)
    )
    goal = result.scalar_one_or_none()
    if not goal:
        raise HTTPException(status_code=404, detail="Goal not found")

    resp = _goal_response(goal).model_dump()
    resp["projects"] = [
        {
            "id": p.id,
            "title": p.title,
            "description": p.description,
            "status": p.status.value if hasattr(p.status, "value") else p.status,
            "due_date": p.due_date,
            "sort_order": p.sort_order,
            "created_at": p.created_at,
            "updated_at": p.updated_at,
        }
        for p in goal.projects
    ]
    return GoalDetailResponse(**resp)


@router.patch("/reorder", status_code=status.HTTP_200_OK)
async def reorder_goals(
    items: list[dict],
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    goal_orders = {}
    for item in items:
        goal_id = item.get("id")
        sort_order = item.get("sort_order")
        if goal_id is None or sort_order is None:
            raise HTTPException(
                status_code=400, detail="Each item must have 'id' and 'sort_order'"
            )
        goal_orders[goal_id] = sort_order

    if not goal_orders:
        return {"detail": "Goals reordered"}

    goal_ids = list(goal_orders.keys())
    result = await db.execute(
        select(Goal).where(Goal.id.in_(goal_ids), Goal.user_id == current_user.id)
    )
    goals = result.scalars().all()

    if len(goals) != len(goal_ids):
        found_ids = {g.id for g in goals}
        missing_ids = set(goal_ids) - found_ids
        missing_id = next(iter(missing_ids))
        raise HTTPException(status_code=404, detail=f"Goal {missing_id} not found")

    for goal in goals:
        goal.sort_order = goal_orders[goal.id]

    await db.flush()
    return {"detail": "Goals reordered"}


@router.patch("/{goal_id}", response_model=GoalResponse)
async def update_goal(
    goal_id: int,
    data: GoalUpdate,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(Goal).where(Goal.id == goal_id, Goal.user_id == current_user.id)
    )
    goal = result.scalar_one_or_none()
    if not goal:
        raise HTTPException(status_code=404, detail="Goal not found")

    update_data = data.model_dump(exclude_unset=True)
    if "status" in update_data:
        update_data["status"] = GoalStatus(update_data["status"])
    for key, value in update_data.items():
        setattr(goal, key, value)

    await db.flush()
    await db.refresh(goal)
    return _goal_response(goal)


@router.delete("/{goal_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_goal(
    goal_id: int,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(Goal).where(Goal.id == goal_id, Goal.user_id == current_user.id)
    )
    goal = result.scalar_one_or_none()
    if not goal:
        raise HTTPException(status_code=404, detail="Goal not found")
    await db.delete(goal)


def _goal_response(goal: Goal) -> GoalResponse:
    return GoalResponse(
        id=goal.id,
        user_id=goal.user_id,
        title=goal.title,
        description=goal.description,
        status=goal.status.value if hasattr(goal.status, "value") else goal.status,
        target_date=goal.target_date,
        color=goal.color,
        sort_order=goal.sort_order,
        created_at=goal.created_at,
        updated_at=goal.updated_at,
    )
