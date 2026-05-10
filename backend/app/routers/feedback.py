import json
import secrets

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.middleware.admin import require_admin
from app.middleware.auth import get_current_user, get_optional_user
from app.models import BugReportModel, InAppFeedback, User
from app.schemas.feedback import (
    BugReportCreate,
    BugReportMirror,
    BugReportResponse,
    BugReportStatusUpdate,
    InAppFeedbackCreate,
    InAppFeedbackResponse,
)

router = APIRouter(prefix="/feedback", tags=["feedback"])


@router.post("/bug-report", status_code=status.HTTP_201_CREATED)
async def create_bug_report(
    report_data: BugReportCreate,
    db: AsyncSession = Depends(get_db),
    current_user: User | None = Depends(get_optional_user),
):
    report_id = secrets.token_urlsafe(32)

    report = BugReportModel(
        report_id=report_id,
        user_id=current_user.id if current_user else None,
        category=report_data.category,
        description=report_data.description,
        severity=report_data.severity,
        steps=json.dumps(report_data.steps),
        screenshot_uris=json.dumps(report_data.screenshot_uris),
        device_model=report_data.device_model,
        device_manufacturer=report_data.device_manufacturer,
        android_version=report_data.android_version,
        app_version=report_data.app_version,
        app_version_code=report_data.app_version_code,
        build_type=report_data.build_type,
        user_tier=report_data.user_tier,
        current_screen=report_data.current_screen,
        task_count=report_data.task_count,
        habit_count=report_data.habit_count,
        available_ram_mb=report_data.available_ram_mb,
        free_storage_mb=report_data.free_storage_mb,
        network_type=report_data.network_type,
        battery_percent=report_data.battery_percent,
        is_charging=report_data.is_charging,
        status="SUBMITTED",
        diagnostic_log=report_data.diagnostic_log,
        submitted_via=report_data.submitted_via,
    )

    db.add(report)
    await db.flush()
    await db.refresh(report)

    return {"id": report.report_id, "status": "submitted", "message": "Thanks!"}


@router.post("/report", status_code=status.HTTP_201_CREATED)
async def mirror_bug_report(
    report_data: BugReportMirror,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
):
    """Mirror a Firestore bug report into PostgreSQL so it shows up in the
    admin debug-logs panel. The Android client writes the report to Firestore
    first (authoritative) and then posts the same payload here fire-and-forget.

    The request body uses the camelCase field names produced by the client's
    ``reportToMap()`` helper; see ``BugReportMirror`` for the full contract.
    """
    report_id = report_data.id or secrets.token_urlsafe(32)

    existing = await db.execute(
        select(BugReportModel).where(BugReportModel.report_id == report_id)
    )
    if existing.scalar_one_or_none() is not None:
        # Idempotent: the client may retry if the first call failed after the
        # Firestore write succeeded. Return 201 so the client treats it as OK.
        return {"id": report_id, "status": "submitted"}

    report = BugReportModel(
        report_id=report_id,
        user_id=current_user.id,
        category=report_data.category,
        description=report_data.description,
        severity=report_data.severity,
        steps=json.dumps(report_data.steps),
        screenshot_uris=json.dumps(report_data.screenshotUris),
        device_model=report_data.deviceModel,
        device_manufacturer=report_data.deviceManufacturer,
        android_version=report_data.androidVersion,
        app_version=report_data.appVersion,
        app_version_code=report_data.appVersionCode,
        build_type=report_data.buildType,
        user_tier=report_data.userTier,
        current_screen=report_data.currentScreen,
        task_count=report_data.taskCount,
        habit_count=report_data.habitCount,
        available_ram_mb=report_data.availableRamMb,
        free_storage_mb=report_data.freeStorageMb,
        network_type=report_data.networkType,
        battery_percent=report_data.batteryPercent,
        is_charging=report_data.isCharging,
        status=report_data.status or "SUBMITTED",
        diagnostic_log=report_data.diagnosticLog,
        submitted_via=report_data.submittedVia or "firestore",
    )

    db.add(report)
    await db.flush()
    await db.refresh(report)

    return {"id": report.report_id, "status": "submitted"}


@router.get("/bug-reports", response_model=list[BugReportResponse])
async def list_bug_reports(
    status_filter: str | None = None,
    severity: str | None = None,
    page: int = 1,
    limit: int = 20,
    db: AsyncSession = Depends(get_db),
    _admin: User = Depends(require_admin),
):

    query = select(BugReportModel).order_by(BugReportModel.created_at.desc())

    if status_filter:
        query = query.where(BugReportModel.status == status_filter)
    if severity:
        query = query.where(BugReportModel.severity == severity)

    offset = (page - 1) * limit
    query = query.offset(offset).limit(limit)

    result = await db.execute(query)
    reports = result.scalars().all()

    return reports


@router.patch("/bug-reports/{report_id}", response_model=BugReportResponse)
async def update_bug_report_status(
    report_id: str,
    update: BugReportStatusUpdate,
    db: AsyncSession = Depends(get_db),
    _admin: User = Depends(require_admin),
):

    result = await db.execute(
        select(BugReportModel).where(BugReportModel.report_id == report_id)
    )
    report = result.scalar_one_or_none()
    if not report:
        raise HTTPException(status_code=404, detail="Report not found")

    valid_statuses = {"SUBMITTED", "ACKNOWLEDGED", "FIXED", "WONT_FIX"}
    if update.status not in valid_statuses:
        raise HTTPException(status_code=422, detail=f"Invalid status. Must be one of: {valid_statuses}")

    report.status = update.status
    if update.admin_notes is not None:
        report.admin_notes = update.admin_notes

    await db.flush()
    await db.refresh(report)

    return report


@router.post(
    "/in-app",
    status_code=status.HTTP_201_CREATED,
    response_model=InAppFeedbackResponse,
)
async def submit_in_app_feedback(
    body: InAppFeedbackCreate,
    db: AsyncSession = Depends(get_db),
    current_user: User = Depends(get_current_user),
) -> InAppFeedbackResponse:
    """Custom in-app rating prompt write path (E2 in-app ratings).

    User-scoped; auth required. Free-text is stored Postgres-only and
    never logged to standard log channels (treated as PII per
    ``docs/audits/E2_IN_APP_RATINGS_AUDIT.md`` § Item 5 STOP-5A).
    """
    if body.sentiment == "rating" and body.rating is None:
        raise HTTPException(
            status_code=422,
            detail="rating field required when sentiment == 'rating'",
        )

    feedback = InAppFeedback(
        user_id=current_user.id,
        sentiment=body.sentiment,
        rating=body.rating,
        free_text=body.free_text,
        client_timestamp=body.client_timestamp,
    )
    db.add(feedback)
    await db.flush()
    await db.refresh(feedback)
    return InAppFeedbackResponse(success=True, feedback_id=feedback.id)
