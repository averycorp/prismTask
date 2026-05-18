from datetime import date, datetime
from typing import Optional

from pydantic import BaseModel


class HabitCreate(BaseModel):
    name: str
    description: Optional[str] = None
    icon: Optional[str] = None
    color: Optional[str] = None
    category: Optional[str] = None
    frequency: str = "daily"
    target_count: int = 1
    active_days_json: Optional[str] = None
    nag_suppression_override_enabled: bool = False
    nag_suppression_days_override: int = -1
    today_skip_after_complete_days: int = -1
    today_skip_before_schedule_days: int = -1
    # Per-habit streak-forgiveness overrides; ``None`` ↔ inherit global
    # (Android maps these to/from its ``-1`` sentinel on the wire).
    streak_max_missed_days: Optional[int] = None
    forgiveness_enabled: Optional[int] = None
    forgiveness_allowed_misses: Optional[int] = None
    forgiveness_grace_period_days: Optional[int] = None


class HabitUpdate(BaseModel):
    name: Optional[str] = None
    description: Optional[str] = None
    icon: Optional[str] = None
    color: Optional[str] = None
    category: Optional[str] = None
    frequency: Optional[str] = None
    target_count: Optional[int] = None
    active_days_json: Optional[str] = None
    is_active: Optional[bool] = None
    nag_suppression_override_enabled: Optional[bool] = None
    nag_suppression_days_override: Optional[int] = None
    today_skip_after_complete_days: Optional[int] = None
    today_skip_before_schedule_days: Optional[int] = None
    streak_max_missed_days: Optional[int] = None
    forgiveness_enabled: Optional[int] = None
    forgiveness_allowed_misses: Optional[int] = None
    forgiveness_grace_period_days: Optional[int] = None


class HabitResponse(BaseModel):
    id: int
    user_id: int
    name: str
    description: Optional[str] = None
    icon: Optional[str] = None
    color: Optional[str] = None
    category: Optional[str] = None
    frequency: str
    target_count: int
    active_days_json: Optional[str] = None
    is_active: bool
    nag_suppression_override_enabled: bool = False
    nag_suppression_days_override: int = -1
    today_skip_after_complete_days: int = -1
    today_skip_before_schedule_days: int = -1
    streak_max_missed_days: Optional[int] = None
    forgiveness_enabled: Optional[int] = None
    forgiveness_allowed_misses: Optional[int] = None
    forgiveness_grace_period_days: Optional[int] = None
    created_at: datetime
    updated_at: datetime

    model_config = {"from_attributes": True}


class HabitCompletionCreate(BaseModel):
    date: date
    count: int = 1


class HabitCompletionResponse(BaseModel):
    id: int
    habit_id: int
    date: date
    count: int
    created_at: datetime

    model_config = {"from_attributes": True}


class HabitWithCompletions(HabitResponse):
    completions: list[HabitCompletionResponse] = []


class HabitStats(BaseModel):
    habit_id: int
    current_streak: int
    longest_streak: int
    total_completions: int
    completion_rate: float
    completions_this_week: int
