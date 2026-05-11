"""Tests for the file -> task extraction service and router.

Service tests run against a stubbed Anthropic client. Router tests use
the shared httpx AsyncClient + auth_headers fixtures from conftest.
"""

import io
import json
import sys
import types
from unittest.mock import MagicMock, patch

import pytest
from httpx import AsyncClient


def _make_mock_message(payload: dict) -> MagicMock:
    """Shape a fake Anthropic Message whose first content block is the JSON
    payload our prompt asks the model to return.
    """
    block = MagicMock()
    block.text = json.dumps(payload)
    message = MagicMock()
    message.content = [block]
    return message


@pytest.fixture(autouse=True)
def mock_anthropic_module():
    mock_mod = types.ModuleType("anthropic")
    mock_mod.Anthropic = MagicMock  # type: ignore
    mock_mod.APIError = Exception  # type: ignore
    sys.modules["anthropic"] = mock_mod

    import importlib

    import app.services.ai_productivity
    importlib.reload(app.services.ai_productivity)
    import app.services.file_extraction  # noqa: F401 — re-import after reload
    importlib.reload(app.services.file_extraction)

    yield mock_mod

    if "anthropic" in sys.modules and sys.modules["anthropic"] is mock_mod:
        del sys.modules["anthropic"]
    importlib.reload(app.services.ai_productivity)
    importlib.reload(app.services.file_extraction)


class TestFileExtractionService:
    """Unit tests for ``extract_from_file``."""

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_text_file_routes_through_text_path(self):
        from app.services.file_extraction import extract_from_file

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_message({
                "title": "Ship the Login Flow",
                "description": "Implement the login form and wire it up.",
                "suggested_due_date": "2026-05-20",
                "suggested_priority": 3,
                "suggested_project": "Auth",
                "tags": ["frontend", "auth"],
                "subtasks": [
                    {"title": "Wire Up Form Validation", "suggested_due_date": None},
                    {"title": "Add Forgot-Password Link", "suggested_due_date": None},
                ],
                "detected_dates": ["2026-05-20"],
                "confidence": 0.85,
                "notes": "Notes file describes a login feature.",
            })

            body = b"# Login feature\n\n- TODO wire validation\n- TODO add forgot link\n"
            result = extract_from_file(
                file_bytes=body,
                filename="login.md",
                mime_type="text/markdown",
            )

            assert result["title"] == "Ship the Login Flow"
            assert result["suggested_priority"] == 3
            assert len(result["subtasks"]) == 2
            assert result["source_file_name"] == "login.md"
            assert result["source_mime_type"] == "text/markdown"

            # The user-message should have routed through the text path —
            # one text block, no image block.
            (call,) = mock_client.messages.create.call_args_list
            messages = call.kwargs["messages"]
            assert len(messages) == 1
            blocks = messages[0]["content"]
            assert all(b["type"] == "text" for b in blocks)

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_image_file_routes_through_multimodal_path(self):
        from app.services.file_extraction import extract_from_file

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_message({
                "title": "Follow Up On Meeting",
                "subtasks": [],
                "tags": [],
                "detected_dates": [],
                "confidence": 0.6,
            })

            # Tiny PNG-ish bytes; the mock doesn't actually parse them.
            result = extract_from_file(
                file_bytes=b"\x89PNG\r\n\x1a\n",
                filename="meeting.png",
                mime_type="image/png",
            )
            assert result["title"] == "Follow Up On Meeting"

            (call,) = mock_client.messages.create.call_args_list
            blocks = call.kwargs["messages"][0]["content"]
            types_seen = [b["type"] for b in blocks]
            assert "image" in types_seen
            assert "text" in types_seen

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_image_jpg_normalized_to_jpeg(self):
        """``image/jpg`` is not a real IANA type — Anthropic rejects it.
        The service must normalize to ``image/jpeg`` before sending."""
        from app.services.file_extraction import extract_from_file

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_message({
                "title": "X", "subtasks": [], "tags": [],
                "detected_dates": [], "confidence": 0.1,
            })

            extract_from_file(
                file_bytes=b"\xff\xd8\xff\xe0",
                filename="photo.jpg",
                mime_type="image/jpg",
            )
            blocks = mock_client.messages.create.call_args.kwargs["messages"][0]["content"]
            image_block = next(b for b in blocks if b["type"] == "image")
            assert image_block["source"]["media_type"] == "image/jpeg"

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_jsx_extension_treated_as_text_even_with_octet_mime(self):
        """Browsers serve .jsx as application/octet-stream — extension wins."""
        from app.services.file_extraction import extract_from_file

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_message({
                "title": "Refactor LoginForm",
                "subtasks": [{"title": "Extract Validation Helper"}],
                "tags": ["refactor"],
                "detected_dates": [], "confidence": 0.7,
            })

            jsx = b"// TODO: extract validation helper\nexport function LoginForm() { return null; }\n"
            result = extract_from_file(
                file_bytes=jsx,
                filename="LoginForm.jsx",
                mime_type="application/octet-stream",
            )
            assert result["title"] == "Refactor LoginForm"
            assert len(result["subtasks"]) == 1

    def test_empty_file_returns_empty_response_without_calling_claude(self):
        from app.services.file_extraction import extract_from_file

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client

            result = extract_from_file(
                file_bytes=b"",
                filename="empty.txt",
                mime_type="text/plain",
            )
            assert result["title"] == ""
            assert result["confidence"] == 0.0
            assert result["source_file_name"] == "empty.txt"
            mock_client.messages.create.assert_not_called()

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_oversize_file_raises_value_error(self):
        from app.services.file_extraction import MAX_FILE_BYTES, extract_from_file

        oversized = b"x" * (MAX_FILE_BYTES + 1)
        with pytest.raises(ValueError, match="too large"):
            extract_from_file(
                file_bytes=oversized,
                filename="huge.txt",
                mime_type="text/plain",
            )

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_pdf_extraction_short_circuits_to_empty_when_no_text(self):
        """If pypdf yields no text we skip the Claude call entirely."""
        from app.services.file_extraction import extract_from_file

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic, \
             patch("app.services.file_extraction._extract_text_from_pdf", return_value=""):
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client

            result = extract_from_file(
                file_bytes=b"%PDF-1.4 fake",
                filename="empty.pdf",
                mime_type="application/pdf",
            )
            assert result["title"] == ""
            mock_client.messages.create.assert_not_called()


class TestExtractFromFileRouter:
    """Integration tests for ``POST /api/v1/ai/files/extract``.

    The endpoint sits behind the AI feature gate (Pro-tier only), so these
    tests use ``pro_auth_headers`` instead of the bare ``auth_headers``
    fixture — auth_headers users get 403 from the AI middleware.
    """

    @pytest.mark.asyncio
    async def test_returns_structured_suggestion_for_text_file(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        with patch("app.services.file_extraction.extract_from_file") as mock_svc:
            mock_svc.return_value = {
                "title": "Plan Sprint",
                "description": "Outline the next two-week sprint.",
                "suggested_due_date": "2026-05-20",
                "suggested_priority": 2,
                "suggested_project": "Planning",
                "tags": ["sprint", "planning"],
                "subtasks": [
                    {"title": "Draft Goals", "suggested_due_date": None},
                    {"title": "Schedule Kickoff", "suggested_due_date": "2026-05-15"},
                ],
                "detected_dates": ["2026-05-15", "2026-05-20"],
                "confidence": 0.78,
                "notes": "Markdown notes file with TODOs",
                "source_file_name": "sprint.md",
                "source_mime_type": "text/markdown",
            }

            files = {"file": ("sprint.md", io.BytesIO(b"- TODO Draft goals"), "text/markdown")}
            resp = await client.post(
                "/api/v1/ai/files/extract",
                files=files,
                headers=pro_auth_headers,
            )
            assert resp.status_code == 200, resp.text
            body = resp.json()
            assert body["title"] == "Plan Sprint"
            assert body["suggested_priority"] == 2
            assert len(body["subtasks"]) == 2
            assert body["subtasks"][1]["suggested_due_date"] == "2026-05-15"
            assert body["detected_dates"] == ["2026-05-15", "2026-05-20"]
            assert body["source_file_name"] == "sprint.md"

    @pytest.mark.asyncio
    async def test_drops_subtasks_with_blank_titles(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        with patch("app.services.file_extraction.extract_from_file") as mock_svc:
            mock_svc.return_value = {
                "title": "X",
                "subtasks": [
                    {"title": "Real Subtask"},
                    {"title": "   "},
                    {"suggested_due_date": "2026-05-12"},  # missing title
                    "not a dict",
                ],
                "tags": ["#TaggedWithHash", "  ", "clean-tag"],
                "detected_dates": [],
                "confidence": 0.5,
            }

            files = {"file": ("x.txt", io.BytesIO(b"x"), "text/plain")}
            resp = await client.post(
                "/api/v1/ai/files/extract",
                files=files,
                headers=pro_auth_headers,
            )
            assert resp.status_code == 200, resp.text
            body = resp.json()
            assert [s["title"] for s in body["subtasks"]] == ["Real Subtask"]
            # Hash stripped; blank dropped.
            assert body["tags"] == ["TaggedWithHash", "clean-tag"]

    @pytest.mark.asyncio
    async def test_oversize_upload_returns_413(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from app.routers.ai import FILE_EXTRACT_MAX_BYTES

        big = b"x" * (FILE_EXTRACT_MAX_BYTES + 1)
        files = {"file": ("big.txt", io.BytesIO(big), "text/plain")}
        resp = await client.post(
            "/api/v1/ai/files/extract",
            files=files,
            headers=pro_auth_headers,
        )
        assert resp.status_code == 413

    @pytest.mark.asyncio
    async def test_service_unavailable_returns_503(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        with patch(
            "app.services.file_extraction.extract_from_file",
            side_effect=RuntimeError("ANTHROPIC_API_KEY not set"),
        ):
            files = {"file": ("x.txt", io.BytesIO(b"x"), "text/plain")}
            resp = await client.post(
                "/api/v1/ai/files/extract",
                files=files,
                headers=pro_auth_headers,
            )
            assert resp.status_code == 503

    @pytest.mark.asyncio
    async def test_unparseable_ai_response_returns_422(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        with patch(
            "app.services.file_extraction.extract_from_file",
            side_effect=ValueError("Failed to parse AI response after retry"),
        ):
            files = {"file": ("x.txt", io.BytesIO(b"x"), "text/plain")}
            resp = await client.post(
                "/api/v1/ai/files/extract",
                files=files,
                headers=pro_auth_headers,
            )
            assert resp.status_code == 422

    @pytest.mark.asyncio
    async def test_unauthenticated_request_is_rejected(self, client: AsyncClient):
        files = {"file": ("x.txt", io.BytesIO(b"x"), "text/plain")}
        resp = await client.post("/api/v1/ai/files/extract", files=files)
        assert resp.status_code in (401, 403)
