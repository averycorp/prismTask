"""File-content -> structured task extraction.

Companion to ``extract_tasks_from_text``, but accepts a binary file (text,
PDF, DOCX, XLSX, image) and asks Claude Haiku for a richer single-task
suggestion: title, description, due date, priority, project hint, tags,
detected dates, and subtasks.

The Android client wires this into the "Extract from file" affordance on
the task editor — drop in a screenshot, JSX, syllabus, or meeting notes
and the editor pre-fills as much as possible from the contents.

The companion router lives at ``POST /api/v1/ai/files/extract`` and the
response shape is ``app.schemas.ai.FileExtractionResponse``.
"""

from __future__ import annotations

import base64
import io
import json
import logging
from typing import Any

from app.services.ai_productivity import _get_client, _parse_ai_json, get_model

logger = logging.getLogger(__name__)

# Hard cap on the binary we accept (the router enforces this too — keeping
# it here makes the service safe to call directly from tests/scripts).
MAX_FILE_BYTES = 10 * 1024 * 1024  # 10 MB

# How much extracted text we forward to Claude. Haiku has a 200k window but
# we keep it well under that to bound token cost on a 10 MB DOCX/XLSX.
MAX_TEXT_CHARS = 50_000

TEXT_LIKE_MIMES = {
    "text/plain",
    "text/markdown",
    "text/x-markdown",
    "text/html",
    "text/css",
    "text/csv",
    "text/x-python",
    "text/javascript",
    "text/typescript",
    "text/x-kotlin",
    "text/x-java",
    "text/x-c",
    "text/x-c++",
    "text/x-go",
    "text/x-rust",
    "text/x-ruby",
    "text/x-shellscript",
    "text/x-yaml",
    "text/yaml",
    "application/json",
    "application/xml",
    "text/xml",
    "application/javascript",
    "application/typescript",
    "application/x-yaml",
    "application/x-sh",
    "application/x-httpd-php",
}

IMAGE_MIMES = {
    "image/jpeg",
    "image/jpg",
    "image/png",
    "image/gif",
    "image/webp",
}

PDF_MIMES = {"application/pdf"}
DOCX_MIMES = {
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
}
XLSX_MIMES = {
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
}

# Browsers occasionally serve source files as application/octet-stream;
# the extension is the tiebreaker.
TEXT_EXTENSIONS: tuple[str, ...] = (
    ".jsx", ".tsx", ".ts", ".js", ".mjs", ".cjs",
    ".py", ".rb", ".go", ".rs", ".java", ".kt", ".kts", ".scala",
    ".c", ".cpp", ".cc", ".h", ".hpp", ".cs", ".swift", ".m", ".mm",
    ".sh", ".bash", ".zsh", ".ps1", ".bat",
    ".md", ".markdown", ".txt", ".rst", ".adoc",
    ".json", ".yaml", ".yml", ".toml", ".ini", ".cfg", ".env",
    ".html", ".htm", ".css", ".scss", ".sass", ".less",
    ".xml", ".svg", ".sql", ".graphql", ".proto",
    ".lua", ".dart", ".vue", ".astro",
)


def extract_from_file(
    file_bytes: bytes,
    filename: str,
    mime_type: str,
    tier: str = "FREE",
) -> dict[str, Any]:
    """Extract a structured task suggestion from a file upload.

    Routes the bytes to a text extractor (PDF / DOCX / XLSX / text-like)
    or to Claude's multimodal endpoint (images), then asks Haiku for a
    single-task JSON response. ``tier`` is accepted for symmetry with the
    other ai_productivity helpers; model selection is handled by
    ``get_model("file_extraction")`` and is currently Haiku for everyone.
    """
    prompt_filename = (filename or "uploaded-file").strip() or "uploaded-file"
    mime = (mime_type or "application/octet-stream").lower()

    if not file_bytes:
        return _empty_response(prompt_filename, mime)
    if len(file_bytes) > MAX_FILE_BYTES:
        raise ValueError(
            f"File too large: {len(file_bytes)} bytes (max {MAX_FILE_BYTES})"
        )

    client = _get_client()
    model = get_model("file_extraction")

    if mime in IMAGE_MIMES:
        user_content = _build_image_message(file_bytes, prompt_filename, mime)
    else:
        body_text = _resolve_text_body(file_bytes, prompt_filename, mime)
        if not body_text.strip():
            return _empty_response(prompt_filename, mime)
        if len(body_text) > MAX_TEXT_CHARS:
            body_text = body_text[:MAX_TEXT_CHARS] + "\n... [truncated]"
        user_content = [
            {"type": "text", "text": _build_prompt(prompt_filename, mime, body_text)}
        ]

    last_error: Exception | None = None
    for attempt in range(2):
        try:
            message = client.messages.create(
                model=model,
                max_tokens=2048,
                messages=[{"role": "user", "content": user_content}],
            )
            content = message.content[0].text
            parsed = _parse_ai_json(content)
            if not isinstance(parsed, dict):
                raise ValueError("Expected a JSON object")
            parsed.setdefault("source_file_name", prompt_filename)
            parsed.setdefault("source_mime_type", mime)
            return parsed
        except (json.JSONDecodeError, KeyError, TypeError, IndexError, ValueError) as e:
            last_error = e
            logger.error(
                "File extraction parse failed (attempt %d): %s", attempt + 1, e
            )
            if attempt == 0:
                continue
            raise ValueError(f"Failed to parse AI response after retry: {e}") from e
        except Exception as e:
            logger.error("File extraction AI error: %s: %s", type(e).__name__, e)
            raise
    raise ValueError(f"Failed to parse AI response: {last_error}")


def _resolve_text_body(file_bytes: bytes, filename: str, mime: str) -> str:
    """Decode/parse file bytes to plain text. Empty string for unsupported types."""
    lower_name = filename.lower()
    if mime in PDF_MIMES or lower_name.endswith(".pdf"):
        return _extract_text_from_pdf(file_bytes)
    if mime in DOCX_MIMES or lower_name.endswith(".docx"):
        return _extract_text_from_docx(file_bytes)
    if mime in XLSX_MIMES or lower_name.endswith(".xlsx"):
        return _extract_text_from_xlsx(file_bytes)
    if _is_text_like(mime, filename):
        return file_bytes.decode("utf-8", errors="replace")
    # Last-ditch: many "application/octet-stream" uploads are actually text.
    try:
        decoded = file_bytes.decode("utf-8")
    except UnicodeDecodeError:
        return ""
    return decoded if decoded.isprintable() or "\n" in decoded else ""


def _is_text_like(mime: str, filename: str) -> bool:
    if mime in TEXT_LIKE_MIMES or mime.startswith("text/"):
        return True
    return filename.lower().endswith(TEXT_EXTENSIONS)


def _extract_text_from_pdf(data: bytes) -> str:
    try:
        import pypdf
    except ImportError as e:
        raise RuntimeError("pypdf is not installed") from e
    try:
        reader = pypdf.PdfReader(io.BytesIO(data))
    except Exception as e:  # noqa: BLE001 — pypdf raises a wide tree
        logger.warning("PDF could not be opened: %s", e)
        return ""
    parts: list[str] = []
    for page in reader.pages:
        try:
            parts.append(page.extract_text() or "")
        except Exception as e:  # noqa: BLE001
            logger.debug("PDF page extraction failed: %s", e)
    return "\n\n".join(p for p in parts if p).strip()


def _extract_text_from_docx(data: bytes) -> str:
    try:
        from docx import Document  # python-docx
    except ImportError as e:
        raise RuntimeError(
            "python-docx is not installed (add `python-docx` to requirements.txt)"
        ) from e
    try:
        doc = Document(io.BytesIO(data))
    except Exception as e:  # noqa: BLE001 — python-docx raises PackageNotFoundError + others
        logger.warning("DOCX could not be opened: %s", e)
        return ""
    parts: list[str] = []
    for para in doc.paragraphs:
        text = para.text.strip()
        if text:
            parts.append(text)
    for table in doc.tables:
        for row in table.rows:
            cells = [c.text.strip() for c in row.cells if c.text and c.text.strip()]
            if cells:
                parts.append(" | ".join(cells))
    return "\n".join(parts).strip()


def _extract_text_from_xlsx(data: bytes) -> str:
    try:
        from openpyxl import load_workbook
    except ImportError as e:
        raise RuntimeError(
            "openpyxl is not installed (add `openpyxl` to requirements.txt)"
        ) from e
    try:
        wb = load_workbook(io.BytesIO(data), read_only=True, data_only=True)
    except Exception as e:  # noqa: BLE001 — openpyxl raises InvalidFileException + others
        logger.warning("XLSX could not be opened: %s", e)
        return ""
    try:
        parts: list[str] = []
        for sheet in wb.worksheets:
            parts.append(f"# Sheet: {sheet.title}")
            for row in sheet.iter_rows(values_only=True):
                cells = [str(c) for c in row if c is not None and str(c).strip()]
                if cells:
                    parts.append(" | ".join(cells))
        return "\n".join(parts).strip()
    finally:
        wb.close()


def _build_image_message(file_bytes: bytes, filename: str, mime: str) -> list[dict]:
    """Multimodal payload — Claude reads the image and the prompt together."""
    encoded = base64.standard_b64encode(file_bytes).decode("ascii")
    # image/jpg is not a real IANA type but browsers occasionally send it;
    # Anthropic only accepts image/jpeg, so normalize.
    media_type = "image/jpeg" if mime == "image/jpg" else mime
    return [
        {
            "type": "image",
            "source": {
                "type": "base64",
                "media_type": media_type,
                "data": encoded,
            },
        },
        {
            "type": "text",
            "text": _build_prompt(
                filename,
                media_type,
                "[image contents — see attached image]",
            ),
        },
    ]


def _build_prompt(filename: str, mime: str, body_text: str) -> str:
    return f"""You are an assistant that turns an uploaded file into a single
structured task with optional subtasks. The user wants as much auto-fill as
possible from the file contents — title, description, due date, priority,
project hint, tags, and any subtasks the file implies.

File metadata:
- name: {filename}
- mime: {mime}

File contents:
---
{body_text}
---

Return ONLY valid JSON with this exact shape (no commentary, no markdown fences):

{{
  "title": "Concise imperative title in Title Case, under 12 words",
  "description": "Optional short summary (1-2 sentences) or null",
  "suggested_due_date": "YYYY-MM-DD or null",
  "suggested_priority": 0,
  "suggested_project": "Short project name or null",
  "tags": ["tag1", "tag2"],
  "subtasks": [
    {{"title": "Subtask in imperative Title Case", "suggested_due_date": null}}
  ],
  "detected_dates": ["YYYY-MM-DD"],
  "confidence": 0.0,
  "notes": "Optional 1-sentence rationale or null"
}}

Rules:
- ``suggested_priority`` is 0 (none), 1 (low), 2 (medium), 3 (high), 4 (urgent).
- Pull every date the file references (deadlines, meeting dates, due dates,
  EXIF-style timestamps embedded in filenames). List all of them in
  ``detected_dates`` and pick the soonest forward-looking one as
  ``suggested_due_date``. If only past dates are present,
  ``suggested_due_date`` is null.
- Subtasks should reflect actionable lines in the file: TODO/FIXME comments
  in code, bullet items in notes, headings in syllabi, line items in
  spreadsheets. Cap at 20 subtasks.
- Tags are lowercase, no leading ``#``.
- If the file is empty or contains nothing actionable, return ``title=""``
  and ``confidence=0.0`` — never invent content.
"""


def _empty_response(filename: str, mime: str) -> dict[str, Any]:
    return {
        "title": "",
        "description": None,
        "suggested_due_date": None,
        "suggested_priority": 0,
        "suggested_project": None,
        "tags": [],
        "subtasks": [],
        "detected_dates": [],
        "confidence": 0.0,
        "notes": None,
        "source_file_name": filename,
        "source_mime_type": mime,
    }
