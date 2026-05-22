from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.middleware.auth import get_current_user
from app.models import Tag, TaskTag, User
from app.schemas.tag import TagCreate, TagResponse, TagUpdate

router = APIRouter(prefix="/tags", tags=["tags"])


def _tag_response(tag: Tag) -> TagResponse:
    return TagResponse(
        id=tag.id,
        user_id=tag.user_id,
        name=tag.name,
        color=tag.color,
        created_at=tag.created_at,
    )


@router.get("", response_model=list[TagResponse])
async def list_tags(
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(Tag).where(Tag.user_id == current_user.id).order_by(Tag.name)
    )
    return [_tag_response(t) for t in result.scalars().all()]


@router.post("", response_model=TagResponse, status_code=status.HTTP_201_CREATED)
async def create_tag(
    data: TagCreate,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    tag = Tag(user_id=current_user.id, **data.model_dump())
    db.add(tag)
    await db.flush()
    await db.refresh(tag)
    return _tag_response(tag)


@router.patch("/{tag_id}", response_model=TagResponse)
async def update_tag(
    tag_id: int,
    data: TagUpdate,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(Tag).where(Tag.id == tag_id, Tag.user_id == current_user.id)
    )
    tag = result.scalar_one_or_none()
    if not tag:
        raise HTTPException(status_code=404, detail="Tag not found")

    for key, value in data.model_dump(exclude_unset=True).items():
        setattr(tag, key, value)

    await db.flush()
    await db.refresh(tag)
    return _tag_response(tag)


@router.delete("/{tag_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_tag(
    tag_id: int,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(Tag).where(Tag.id == tag_id, Tag.user_id == current_user.id)
    )
    tag = result.scalar_one_or_none()
    if not tag:
        raise HTTPException(status_code=404, detail="Tag not found")
    await db.delete(tag)


@router.put("/tasks/{task_id}/tags", response_model=list[TagResponse])
async def set_task_tags(
    task_id: int,
    tag_ids: list[int],
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    from app.models import Task

    result = await db.execute(
        select(Task).where(Task.id == task_id, Task.user_id == current_user.id)
    )
    task = result.scalar_one_or_none()
    if not task:
        raise HTTPException(status_code=404, detail="Task not found")

    # Remove existing task-tag associations
    existing = await db.execute(
        select(TaskTag).where(TaskTag.task_id == task_id)
    )
    for tt in existing.scalars().all():
        await db.delete(tt)

    # Add new associations
    tags = []
    if tag_ids:
        tag_result = await db.execute(
            select(Tag).where(Tag.id.in_(tag_ids), Tag.user_id == current_user.id)
        )
        tags = tag_result.scalars().all()

        # Verify all provided tag IDs were found
        found_tag_ids = {t.id for t in tags}
        for tid in tag_ids:
            if tid not in found_tag_ids:
                raise HTTPException(status_code=404, detail=f"Tag {tid} not found")

        # Reorder tags to match the input order
        tag_dict = {t.id: t for t in tags}
        tags = [tag_dict[tid] for tid in tag_ids]

        for t in tags:
            db.add(TaskTag(task_id=task_id, tag_id=t.id))

    await db.flush()
    return [_tag_response(t) for t in tags]
