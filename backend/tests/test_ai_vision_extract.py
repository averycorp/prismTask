"""Tests for the vision-extract endpoint (POST /api/v1/ai/vision/extract-tasks).

Service tests run against a stubbed Anthropic client (mirroring the
test_ai_chat.py pattern). Router tests use the shared httpx AsyncClient +
pro_auth_headers fixtures from conftest.

The router-level ``require_ai_features_enabled`` dependency is exercised
generically by ``test_ai_gate.py``; these tests focus on the vision
endpoint's own behavior.
"""

import sys
import types
from unittest.mock import MagicMock, patch

import pytest
from httpx import AsyncClient


# 1x1 transparent PNG (smallest valid PNG payload), already base64-encoded.
TINY_PNG_BASE64 = (
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="
)


def _make_mock_vision_response(tasks: list[dict]) -> MagicMock:
    """Build a minimal Anthropic Message containing a single text block
    with the JSON array the vision service is expected to return."""
    import json
    text_block = MagicMock()
    text_block.type = "text"
    text_block.text = json.dumps(tasks)
    message = MagicMock()
    message.content = [text_block]
    usage = MagicMock(input_tokens=120, output_tokens=80)
    message.usage = usage
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

    yield mock_mod

    if "anthropic" in sys.modules and sys.modules["anthropic"] is mock_mod:
        del sys.modules["anthropic"]
    importlib.reload(app.services.ai_productivity)


class TestExtractTasksFromImageService:
    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_returns_parsed_task_list(self):
        from app.services.ai_productivity import extract_tasks_from_image

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_vision_response([
                {
                    "title": "Reply to Sarah's email",
                    "suggested_due_date": None,
                    "suggested_priority": 2,
                    "suggested_project": None,
                    "confidence": 0.9,
                },
            ])
            result = extract_tasks_from_image(
                image_base64=TINY_PNG_BASE64,
                image_media_type="image/png",
            )
            assert len(result) == 1
            assert result[0]["title"] == "Reply to Sarah's email"

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_empty_image_returns_empty_list(self):
        from app.services.ai_productivity import extract_tasks_from_image
        assert extract_tasks_from_image(
            image_base64="",
            image_media_type="image/png",
        ) == []

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_constructs_anthropic_image_message_block(self):
        """Verify the service sends a properly-shaped vision content array
        (image source + text prompt) rather than a plain text message."""
        from app.services.ai_productivity import extract_tasks_from_image

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_vision_response([])

            extract_tasks_from_image(
                image_base64=TINY_PNG_BASE64,
                image_media_type="image/jpeg",
            )

            kwargs = mock_client.messages.create.call_args.kwargs
            messages = kwargs["messages"]
            assert len(messages) == 1
            content = messages[0]["content"]
            assert isinstance(content, list)
            assert content[0]["type"] == "image"
            assert content[0]["source"]["type"] == "base64"
            assert content[0]["source"]["media_type"] == "image/jpeg"
            assert content[0]["source"]["data"] == TINY_PNG_BASE64
            assert content[1]["type"] == "text"

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_retries_then_succeeds_on_malformed_response(self):
        from app.services.ai_productivity import extract_tasks_from_image

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            bad = MagicMock()
            bad.content = [MagicMock(type="text", text="not json")]
            good = _make_mock_vision_response([
                {
                    "title": "Send report",
                    "suggested_priority": 0,
                    "confidence": 0.6,
                },
            ])
            mock_client.messages.create.side_effect = [bad, good]
            result = extract_tasks_from_image(
                image_base64=TINY_PNG_BASE64,
                image_media_type="image/png",
            )
            assert len(result) == 1
            assert mock_client.messages.create.call_count == 2

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_returns_empty_array_when_no_tasks_detected(self):
        from app.services.ai_productivity import extract_tasks_from_image

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_vision_response([])
            result = extract_tasks_from_image(
                image_base64=TINY_PNG_BASE64,
                image_media_type="image/png",
            )
            assert result == []


class TestVisionExtractTasksRouter:
    @pytest.mark.asyncio
    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    async def test_returns_extracted_tasks_for_pro_user(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_vision_response([
                {
                    "title": "Buy groceries",
                    "suggested_due_date": "2026-05-12",
                    "suggested_priority": 1,
                    "suggested_project": "Errands",
                    "confidence": 0.85,
                },
            ])
            resp = await client.post(
                "/api/v1/ai/vision/extract-tasks",
                headers=pro_auth_headers,
                json={
                    "image_base64": TINY_PNG_BASE64,
                    "image_media_type": "image/png",
                },
            )
            assert resp.status_code == 200, resp.text
            body = resp.json()
            assert "tasks" in body
            assert body["tasks"][0]["title"] == "Buy groceries"
            assert body["tasks"][0]["suggested_priority"] == 1

    @pytest.mark.asyncio
    async def test_requires_authentication(self, client: AsyncClient):
        resp = await client.post(
            "/api/v1/ai/vision/extract-tasks",
            json={
                "image_base64": TINY_PNG_BASE64,
                "image_media_type": "image/png",
            },
        )
        assert resp.status_code in (401, 403)

    @pytest.mark.asyncio
    async def test_rejects_invalid_media_type(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        resp = await client.post(
            "/api/v1/ai/vision/extract-tasks",
            headers=pro_auth_headers,
            json={
                "image_base64": TINY_PNG_BASE64,
                "image_media_type": "image/bmp",
            },
        )
        assert resp.status_code == 422

    @pytest.mark.asyncio
    async def test_rejects_empty_base64(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        resp = await client.post(
            "/api/v1/ai/vision/extract-tasks",
            headers=pro_auth_headers,
            json={
                "image_base64": "",
                "image_media_type": "image/png",
            },
        )
        assert resp.status_code == 422

    @pytest.mark.asyncio
    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    async def test_drops_candidates_with_empty_titles(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_vision_response([
                {"title": "", "suggested_priority": 0, "confidence": 0.1},
                {"title": "Real task", "suggested_priority": 0, "confidence": 0.9},
            ])
            resp = await client.post(
                "/api/v1/ai/vision/extract-tasks",
                headers=pro_auth_headers,
                json={
                    "image_base64": TINY_PNG_BASE64,
                    "image_media_type": "image/png",
                },
            )
            assert resp.status_code == 200
            assert len(resp.json()["tasks"]) == 1
            assert resp.json()["tasks"][0]["title"] == "Real task"
