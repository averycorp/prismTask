"""Paste/file/vision -> task-candidate extraction endpoints.

Three shapes of "extract structured tasks from raw user content":

* ``POST /tasks/extract-from-text`` — paste-to-tasks (text), v1.4.0 V9.
* ``POST /files/extract`` — file upload (md / pdf / docx / xlsx / image), v1.7.
* ``POST /vision/extract-tasks`` — screenshot-to-tasks (Claude Vision), G.

All three are pure suggestion endpoints — they never mutate task state.
The Android client renders the response in a confirm-before-save UI.
"""

import logging

from fastapi import APIRouter, Depends, File, HTTPException, Request, UploadFile, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.middleware.auth import get_active_user
from app.middleware.rate_limit import daily_ai_rate_limiter
from app.models import User
from app.schemas.ai import (
    ExtractFromTextRequest,
    ExtractFromTextResponse,
    ExtractedTaskCandidate,
    FileExtractedSubtask,
    FileExtractionResponse,
    VisionExtractRequest,
    VisionExtractResponse,
)
from app.services.beta_codes import resolve_effective_tier

# Rate limiters + FILE_EXTRACT_MAX_BYTES are looked up at call time via the
# package module so tests can patch ``app.routers.ai.X`` and have their stub
# take effect (a top-level ``from ._common import X`` would bind the name
# at import time and bypass the patch).

logger = logging.getLogger(__name__)

router = APIRouter()


@router.post("/parse-text", response_model=ExtractFromTextResponse)
@router.post("/tasks/extract-from-text", response_model=ExtractFromTextResponse)
async def extract_from_text(
    data: ExtractFromTextRequest,
    request: Request,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    """
    Extract structured task candidates from pasted conversation text via
    Claude Haiku. The Android client (ConversationTaskExtractor) falls
    back to regex-based extraction when this endpoint is unavailable.

    Input is capped at 10,000 chars by the schema. The Android paste
    screen enforces the same cap client-side.
    """
    from app.routers import ai as _ai_pkg
    _ai_pkg.extract_rate_limiter.check(request, is_admin=current_user.is_admin)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier, is_admin=current_user.is_admin)

    try:
        from app.services.ai_productivity import extract_tasks_from_text as ai_extract
        raw_tasks = ai_extract(data.text, data.source, tier=tier)
    except RuntimeError:
        raise HTTPException(status_code=503, detail="AI service temporarily unavailable")
    except ValueError:
        raise HTTPException(status_code=500, detail="AI returned an invalid response")

    candidates = []
    for t in raw_tasks:
        candidates.append(
            ExtractedTaskCandidate(
                title=str(t.get("title", "")).strip(),
                suggested_due_date=t.get("suggested_due_date"),
                suggested_priority=int(t.get("suggested_priority") or 0),
                suggested_project=t.get("suggested_project"),
                confidence=float(t.get("confidence") or 0.5),
            )
        )
    # Drop anything with an empty title — defensive.
    candidates = [c for c in candidates if c.title]
    return ExtractFromTextResponse(tasks=candidates)


@router.post("/files/extract", response_model=FileExtractionResponse)
async def extract_from_file(
    request: Request,
    file: UploadFile = File(...),
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    """Extract a single structured task suggestion from an uploaded file.

    Supports text-like uploads (markdown, source code, json, csv, jsx, ts,
    etc.), PDFs, DOCX, XLSX, and images. The Android client renders the
    response in ``FileImportSuggestionSheet`` so the user can confirm
    before any task state is mutated. This endpoint never writes — it's
    pure suggestion.
    """
    from app.routers import ai as _ai_pkg
    _ai_pkg.file_extract_rate_limiter.check(request, is_admin=current_user.is_admin)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier, is_admin=current_user.is_admin)

    chunk_size = 1024 * 1024
    contents_buffer = bytearray()
    while True:
        chunk = await file.read(chunk_size)
        if not chunk:
            break
        contents_buffer.extend(chunk)
        if len(contents_buffer) > _ai_pkg.FILE_EXTRACT_MAX_BYTES:
            raise HTTPException(
                status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                detail=f"File must be under {_ai_pkg.FILE_EXTRACT_MAX_BYTES // (1024 * 1024)} MB",
            )
    contents = bytes(contents_buffer)

    filename = file.filename or "uploaded-file"
    mime_type = file.content_type or "application/octet-stream"

    try:
        from app.services.file_extraction import extract_from_file as do_extract

        result = do_extract(
            file_bytes=contents,
            filename=filename,
            mime_type=mime_type,
            tier=tier,
        )
    except RuntimeError as e:
        # Missing optional dep (python-docx / openpyxl) or missing API key.
        logger.error("File extraction service unavailable: %s", e)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="File extraction is temporarily unavailable",
        )
    except ValueError as e:
        # File too large (already caught above) or AI returned bad JSON
        # after retry — surface as 422 so the client knows it's a content
        # problem, not a server problem.
        logger.warning("File extraction rejected: %s", e)
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Could not extract structured data from this file",
        )

    # Validate + coerce — the AI occasionally drops fields or returns the
    # wrong types. Be permissive: drop unknown keys, default missing ones.
    subtasks = []
    for raw in (result.get("subtasks") or []):
        if not isinstance(raw, dict):
            continue
        title = str(raw.get("title", "")).strip()
        if not title:
            continue
        subtasks.append(
            FileExtractedSubtask(
                title=title,
                suggested_due_date=raw.get("suggested_due_date"),
            )
        )

    return FileExtractionResponse(
        title=str(result.get("title", "")).strip(),
        description=(result.get("description") or None),
        suggested_due_date=result.get("suggested_due_date"),
        suggested_priority=int(result.get("suggested_priority") or 0),
        suggested_project=result.get("suggested_project"),
        tags=[str(t).strip().lstrip("#") for t in (result.get("tags") or []) if str(t).strip()],
        subtasks=subtasks,
        detected_dates=[str(d) for d in (result.get("detected_dates") or []) if d],
        confidence=float(result.get("confidence") or 0.0),
        notes=(result.get("notes") or None),
        source_file_name=result.get("source_file_name") or filename,
        source_mime_type=result.get("source_mime_type") or mime_type,
    )


@router.post("/vision/extract-tasks", response_model=VisionExtractResponse)
async def vision_extract_tasks(
    data: VisionExtractRequest,
    request: Request,
    current_user: User = Depends(get_active_user),
    db: AsyncSession = Depends(get_db),
):
    """
    Extract structured task candidates from a screenshot via Claude Vision.

    Mirrors ``/tasks/extract-from-text`` for the image input path. The
    Android client packages the response into the same batch-preview
    UI the paste-extract flow uses.

    Privacy: image bytes are forwarded to Anthropic for extraction and
    are not persisted server-side. The router-level
    ``require_ai_features_enabled`` dependency rejects requests where
    the user has the master AI opt-out enabled.
    """
    from app.routers import ai as _ai_pkg
    _ai_pkg.vision_extract_rate_limiter.check(request, is_admin=current_user.is_admin)
    tier = await resolve_effective_tier(current_user, db)
    daily_ai_rate_limiter.check(current_user.id, tier, is_admin=current_user.is_admin)

    try:
        from app.services.ai_productivity import extract_tasks_from_image as ai_vision_extract
        raw_tasks = ai_vision_extract(
            image_base64=data.image_base64,
            image_media_type=data.image_media_type,
            tier=tier,
        )
    except RuntimeError:
        raise HTTPException(status_code=503, detail="AI service temporarily unavailable")
    except ValueError:
        raise HTTPException(status_code=500, detail="AI returned an invalid response")

    candidates = []
    for t in raw_tasks:
        candidates.append(
            ExtractedTaskCandidate(
                title=str(t.get("title", "")).strip(),
                suggested_due_date=t.get("suggested_due_date"),
                suggested_priority=int(t.get("suggested_priority") or 0),
                suggested_project=t.get("suggested_project"),
                confidence=float(t.get("confidence") or 0.5),
            )
        )
    candidates = [c for c in candidates if c.title]
    return VisionExtractResponse(tasks=candidates)
