from datetime import datetime
from typing import Optional

from pydantic import BaseModel, Field


class BugReportCreate(BaseModel):
    category: str = Field(..., description="Bug category: CRASH, UI_GLITCH, FEATURE_NOT_WORKING, DATA_LOSS, PERFORMANCE, SYNC_ISSUE, WIDGET_ISSUE, FEATURE_REQUEST, OTHER")
    description: str = Field(..., description="Description of the issue")
    severity: str = Field(default="MINOR", description="MINOR, MAJOR, or CRITICAL")
    steps: list[str] = Field(default_factory=list, description="Steps to reproduce")
    screenshot_uris: list[str] = Field(default_factory=list, description="Firebase Storage screenshot URIs")
    device_model: str = ""
    device_manufacturer: str = ""
    android_version: int = 0
    app_version: str = ""
    app_version_code: int = 0
    build_type: str = ""
    user_tier: str = ""
    current_screen: str = ""
    task_count: int = 0
    habit_count: int = 0
    available_ram_mb: int = 0
    free_storage_mb: int = 0
    network_type: str = ""
    battery_percent: int = 0
    is_charging: bool = False
    diagnostic_log: Optional[str] = None
    submitted_via: str = "backend"


class BugReportMirror(BaseModel):
    """Schema for /feedback/report — mirrors a Firestore bug report to PG.

    Field names use the camelCase keys produced by the Android client's
    ``reportToMap()`` helper so the app can POST the same payload it writes
    to Firestore without an intermediate transformation.
    """

    id: Optional[str] = None
    userId: Optional[str] = None
    category: str = "OTHER"
    description: str
    severity: str = "MINOR"
    steps: list[str] = Field(default_factory=list)
    screenshotUris: list[str] = Field(default_factory=list)
    deviceModel: str = ""
    deviceManufacturer: str = ""
    androidVersion: int = 0
    appVersion: str = ""
    appVersionCode: int = 0
    buildType: str = ""
    userTier: str = ""
    currentScreen: str = ""
    taskCount: int = 0
    habitCount: int = 0
    availableRamMb: int = 0
    freeStorageMb: int = 0
    networkType: str = ""
    batteryPercent: int = 0
    isCharging: bool = False
    timestamp: Optional[int] = None
    status: Optional[str] = "SUBMITTED"
    diagnosticLog: Optional[str] = None
    submittedVia: Optional[str] = "firestore"


class BugReportStatusUpdate(BaseModel):
    status: str = Field(..., description="SUBMITTED, ACKNOWLEDGED, FIXED, WONT_FIX")
    admin_notes: Optional[str] = None


class BugReportResponse(BaseModel):
    id: int
    report_id: str
    user_id: Optional[int] = None
    category: str
    description: str
    severity: str
    steps: str  # JSON string
    screenshot_uris: str  # JSON string
    device_model: str
    device_manufacturer: str
    android_version: int
    app_version: str
    app_version_code: int
    build_type: str
    user_tier: str
    current_screen: str
    task_count: int
    habit_count: int
    available_ram_mb: int
    free_storage_mb: int
    network_type: str
    battery_percent: int
    is_charging: bool
    status: str
    admin_notes: Optional[str] = None
    diagnostic_log: Optional[str] = None
    submitted_via: str
    created_at: Optional[datetime] = None
    updated_at: Optional[datetime] = None

    model_config = {"from_attributes": True}


class InAppFeedbackCreate(BaseModel):
    """Body for ``POST /api/v1/feedback/in-app``.

    ``sentiment`` is the discriminator (``thumb_up`` / ``thumb_down`` /
    ``rating``). ``rating`` is set only when sentiment == ``rating``.
    ``free_text`` is capped at 4000 chars at the API boundary; the
    Postgres column itself is unbounded ``Text``.
    """

    sentiment: str = Field(..., pattern=r"^(thumb_up|thumb_down|rating)$")
    rating: Optional[int] = Field(default=None, ge=1, le=5)
    free_text: Optional[str] = Field(default=None, max_length=4000)
    client_timestamp: Optional[int] = Field(default=None, ge=0)


class InAppFeedbackResponse(BaseModel):
    success: bool
    feedback_id: int
