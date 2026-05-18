from datetime import date, timedelta

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.database import get_db
from app.middleware.auth import get_current_user
from app.models import Habit, HabitCompletion, HabitFrequency, User
from app.schemas.habit import (
    HabitCompletionCreate,
    HabitCompletionResponse,
    HabitCreate,
    HabitResponse,
    HabitStats,
    HabitUpdate,
    HabitWithCompletions,
)

router = APIRouter(prefix="/habits", tags=["habits"])


def _habit_response(habit: Habit) -> HabitResponse:
    return HabitResponse(
        id=habit.id,
        user_id=habit.user_id,
        name=habit.name,
        description=habit.description,
        icon=habit.icon,
        color=habit.color,
        category=habit.category,
        frequency=habit.frequency.value if hasattr(habit.frequency, "value") else habit.frequency,
        target_count=habit.target_count,
        active_days_json=habit.active_days_json,
        is_active=habit.is_active,
        nag_suppression_override_enabled=getattr(
            habit, "nag_suppression_override_enabled", False
        ),
        nag_suppression_days_override=getattr(
            habit, "nag_suppression_days_override", -1
        ),
        today_skip_after_complete_days=getattr(
            habit, "today_skip_after_complete_days", -1
        ),
        today_skip_before_schedule_days=getattr(
            habit, "today_skip_before_schedule_days", -1
        ),
        streak_max_missed_days=getattr(habit, "streak_max_missed_days", None),
        forgiveness_enabled=getattr(habit, "forgiveness_enabled", None),
        forgiveness_allowed_misses=getattr(
            habit, "forgiveness_allowed_misses", None
        ),
        forgiveness_grace_period_days=getattr(
            habit, "forgiveness_grace_period_days", None
        ),
        created_at=habit.created_at,
        updated_at=habit.updated_at,
    )


@router.get("", response_model=list[HabitResponse])
async def list_habits(
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(Habit)
        .where(Habit.user_id == current_user.id, Habit.is_active.is_(True))
        .order_by(Habit.created_at)
    )
    return [_habit_response(h) for h in result.scalars().all()]


@router.post("", response_model=HabitResponse, status_code=status.HTTP_201_CREATED)
async def create_habit(
    data: HabitCreate,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    habit_data = data.model_dump(exclude_unset=True)
    if "frequency" in habit_data:
        habit_data["frequency"] = HabitFrequency(habit_data["frequency"])
    habit = Habit(user_id=current_user.id, **habit_data)
    db.add(habit)
    await db.flush()
    await db.refresh(habit)
    return _habit_response(habit)


@router.get("/{habit_id}", response_model=HabitWithCompletions)
async def get_habit(
    habit_id: int,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(Habit)
        .options(selectinload(Habit.completions))
        .where(Habit.id == habit_id, Habit.user_id == current_user.id)
    )
    habit = result.scalar_one_or_none()
    if not habit:
        raise HTTPException(status_code=404, detail="Habit not found")

    resp = _habit_response(habit).model_dump()
    resp["completions"] = [
        HabitCompletionResponse(
            id=c.id,
            habit_id=c.habit_id,
            date=c.date,
            count=c.count,
            created_at=c.created_at,
        )
        for c in habit.completions
    ]
    return HabitWithCompletions(**resp)


@router.patch("/{habit_id}", response_model=HabitResponse)
async def update_habit(
    habit_id: int,
    data: HabitUpdate,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(Habit).where(Habit.id == habit_id, Habit.user_id == current_user.id)
    )
    habit = result.scalar_one_or_none()
    if not habit:
        raise HTTPException(status_code=404, detail="Habit not found")

    update_data = data.model_dump(exclude_unset=True)
    if "frequency" in update_data:
        update_data["frequency"] = HabitFrequency(update_data["frequency"])
    for key, value in update_data.items():
        setattr(habit, key, value)

    await db.flush()
    await db.refresh(habit)
    return _habit_response(habit)


@router.delete("/{habit_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_habit(
    habit_id: int,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(Habit).where(Habit.id == habit_id, Habit.user_id == current_user.id)
    )
    habit = result.scalar_one_or_none()
    if not habit:
        raise HTTPException(status_code=404, detail="Habit not found")
    await db.delete(habit)


@router.post("/{habit_id}/complete", response_model=HabitCompletionResponse, status_code=status.HTTP_201_CREATED)
async def complete_habit(
    habit_id: int,
    data: HabitCompletionCreate,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(Habit).where(Habit.id == habit_id, Habit.user_id == current_user.id)
    )
    habit = result.scalar_one_or_none()
    if not habit:
        raise HTTPException(status_code=404, detail="Habit not found")

    # Check for existing completion on this date
    existing = await db.execute(
        select(HabitCompletion).where(
            HabitCompletion.habit_id == habit_id,
            HabitCompletion.date == data.date,
        )
    )
    completion = existing.scalar_one_or_none()
    if completion:
        # Toggle off: delete it
        await db.delete(completion)
        await db.flush()
        # Return the deleted one with id for reference
        return HabitCompletionResponse(
            id=completion.id,
            habit_id=completion.habit_id,
            date=completion.date,
            count=0,
            created_at=completion.created_at,
        )

    completion = HabitCompletion(
        habit_id=habit_id,
        date=data.date,
        count=data.count,
    )
    db.add(completion)
    await db.flush()
    await db.refresh(completion)
    return HabitCompletionResponse(
        id=completion.id,
        habit_id=completion.habit_id,
        date=completion.date,
        count=completion.count,
        created_at=completion.created_at,
    )


@router.get("/{habit_id}/stats", response_model=HabitStats)
async def habit_stats(
    habit_id: int,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(Habit).where(Habit.id == habit_id, Habit.user_id == current_user.id)
    )
    habit = result.scalar_one_or_none()
    if not habit:
        raise HTTPException(status_code=404, detail="Habit not found")

    # Get all completions sorted by date
    comp_result = await db.execute(
        select(HabitCompletion)
        .where(HabitCompletion.habit_id == habit_id)
        .order_by(HabitCompletion.date)
    )
    completions = comp_result.scalars().all()
    completion_dates = sorted({c.date for c in completions})

    total = len(completion_dates)

    # Current streak
    current_streak = 0
    today = date.today()
    check_date = today
    completion_set = set(completion_dates)
    while check_date in completion_set:
        current_streak += 1
        check_date -= timedelta(days=1)

    # If today not completed, check if yesterday starts the streak
    if current_streak == 0:
        check_date = today - timedelta(days=1)
        while check_date in completion_set:
            current_streak += 1
            check_date -= timedelta(days=1)

    # Longest streak
    longest_streak = 0
    if completion_dates:
        streak = 1
        for i in range(1, len(completion_dates)):
            if (completion_dates[i] - completion_dates[i - 1]).days == 1:
                streak += 1
            else:
                longest_streak = max(longest_streak, streak)
                streak = 1
        longest_streak = max(longest_streak, streak)

    # Completion rate (last 30 days)
    thirty_days_ago = today - timedelta(days=30)
    recent = sum(1 for d in completion_dates if d >= thirty_days_ago)
    rate = round(recent / 30 * 100, 1)

    # This week
    week_start = today - timedelta(days=today.weekday())
    this_week = sum(1 for d in completion_dates if d >= week_start)

    return HabitStats(
        habit_id=habit_id,
        current_streak=current_streak,
        longest_streak=longest_streak,
        total_completions=total,
        completion_rate=rate,
        completions_this_week=this_week,
    )
