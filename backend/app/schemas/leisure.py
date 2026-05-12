"""Pydantic schemas for Leisure Budget v2.0.

Three resources:
* ``LeisureActivity`` — user-owned pool entries.
* ``LeisureSession`` — completed leisure sessions (timer or manual).
* ``LeisureSettings`` — per-user daily target + enforcement config.

Categories + enforcement modes are constrained to the spec-locked
enum values; the request validators reject anything else with a 422.
"""

from datetime import date as date_cls, datetime
from typing import Literal, Optional

from pydantic import BaseModel, Field, field_validator


LeisureCategoryT = Literal["PHYSICAL", "SOCIAL", "CREATIVE", "PASSIVE"]
LeisureEnforcementModeT = Literal["SOFT", "MEDIUM", "HARD"]
LeisureSourceT = Literal["TIMER", "MANUAL"]


class LeisureActivityCreate(BaseModel):
    id: str = Field(..., min_length=1, max_length=64)
    name: str = Field(..., min_length=1, max_length=120)
    category: LeisureCategoryT
    default_duration_minutes: Optional[int] = Field(None, ge=1, le=1440)
    enabled: bool = True


class LeisureActivityUpdate(BaseModel):
    name: Optional[str] = Field(None, min_length=1, max_length=120)
    category: Optional[LeisureCategoryT] = None
    default_duration_minutes: Optional[int] = Field(None, ge=1, le=1440)
    enabled: Optional[bool] = None


class LeisureActivityResponse(BaseModel):
    id: str
    name: str
    category: LeisureCategoryT
    default_duration_minutes: Optional[int] = None
    enabled: bool
    created_at: datetime
    updated_at: datetime
    last_completed_at: Optional[datetime] = None

    model_config = {"from_attributes": True}


class LeisureSessionCreate(BaseModel):
    id: str = Field(..., min_length=1, max_length=64)
    activity_id: Optional[str] = Field(None, max_length=64)
    category: LeisureCategoryT
    duration_minutes: int = Field(..., ge=1, le=1440)
    logged_at: datetime
    source: LeisureSourceT


class LeisureSessionResponse(BaseModel):
    id: str
    activity_id: Optional[str] = None
    category: LeisureCategoryT
    duration_minutes: int
    logged_at: datetime
    source: LeisureSourceT
    created_at: datetime

    model_config = {"from_attributes": True}


class LeisureSettingsResponse(BaseModel):
    """The effective leisure settings the client should display.

    ``pending_enforcement_mode`` may be set when the client just changed
    enforcement_mode but it hasn't taken effect yet — display as
    "Effective tomorrow" in the UI. The currently-active mode is the
    ``enforcement_mode`` field; clients should NOT treat ``pending`` as
    active.
    """

    daily_target_minutes: int
    weekend_target_minutes: Optional[int] = None
    enforcement_mode: LeisureEnforcementModeT
    refresh_limit: int
    enabled_categories: list[LeisureCategoryT]
    pending_enforcement_mode: Optional[LeisureEnforcementModeT] = None
    pending_enforcement_effective_date: Optional[date_cls] = None
    updated_at: datetime


class LeisureSettingsUpdate(BaseModel):
    daily_target_minutes: Optional[int] = Field(None, ge=0, le=1440)
    weekend_target_minutes: Optional[int] = Field(None, ge=0, le=1440)
    # Setting enforcement_mode to anything other than SOFT requires Pro
    # tier — gated server-side by ``require_leisure_enforcement_choice``.
    enforcement_mode: Optional[LeisureEnforcementModeT] = None
    refresh_limit: Optional[int] = Field(None, ge=0, le=10)
    enabled_categories: Optional[list[LeisureCategoryT]] = None
    # Set by the client on day-rollover when promoting a pending
    # enforcement-mode change to active. The server clears
    # pending_enforcement_mode + pending_enforcement_effective_date when
    # this is set to true.
    promote_pending_enforcement: Optional[bool] = None

    @field_validator("enabled_categories")
    @classmethod
    def _at_least_one_category(
        cls, v: Optional[list[str]]
    ) -> Optional[list[str]]:
        if v is not None and len(v) == 0:
            # Empty would make the random-pull algorithm return nothing
            # forever — block that at the API boundary rather than have
            # the UI ship into a dead state.
            raise ValueError("at least one category must be enabled")
        return v
