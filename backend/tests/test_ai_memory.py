"""Tests for the AI-memory bundle.

Covers:
- ``remember_preference`` / ``forget_preference`` tool calls fired by the
  chat handler are split out of the action chips and applied to the
  ``user_ai_preferences`` table.
- The 15-slot cap is enforced; when the AI emits a 16th remember without
  a paired forget, the oldest-by-updated_at row is dropped.
- The full CRUD surface under ``/api/v1/ai/memory`` works for the
  Settings UI (list / create / update / delete).
- The chat response always carries the authoritative ``user_preferences``
  snapshot so the client can REPLACE-all into Room.
"""

import sys
import types
from datetime import datetime, timedelta, timezone
from unittest.mock import MagicMock, patch

import pytest
import pytest_asyncio
from httpx import AsyncClient
from sqlalchemy import select


@pytest_asyncio.fixture(autouse=True)
async def _mock_anthropic_module():
    mock_mod = types.ModuleType("anthropic")
    mock_mod.Anthropic = MagicMock  # type: ignore
    mock_mod.APIError = Exception  # type: ignore
    sys.modules["anthropic"] = mock_mod

    import importlib

    import app.services.ai_productivity
    importlib.reload(app.services.ai_productivity)

    yield

    if "anthropic" in sys.modules and sys.modules["anthropic"] is mock_mod:
        del sys.modules["anthropic"]
    importlib.reload(app.services.ai_productivity)


@pytest_asyncio.fixture
async def _clear_rate_limiter():
    from app.routers.ai import chat_rate_limiter
    chat_rate_limiter._requests.clear()
    yield
    chat_rate_limiter._requests.clear()


async def _resolve_test_user_id() -> int:
    """Look up the default test user (created by ``auth_headers``)."""
    from tests.conftest import TestSessionLocal

    from app.models import User as UserModel

    async with TestSessionLocal() as session:
        stmt = select(UserModel).where(UserModel.email == "test@example.com")
        return (await session.execute(stmt)).scalar_one().id


async def _seed_preferences(rows: list[dict]) -> None:
    """Insert preference rows directly (bypasses the chat handler)."""
    from tests.conftest import TestSessionLocal

    from app.models import UserAiPreference

    async with TestSessionLocal() as session:
        for r in rows:
            session.add(UserAiPreference(**r))
        await session.commit()


async def _list_preference_rows() -> list:
    from tests.conftest import TestSessionLocal

    from app.models import UserAiPreference

    async with TestSessionLocal() as session:
        stmt = (
            select(UserAiPreference)
            .order_by(UserAiPreference.updated_at)
        )
        return list((await session.execute(stmt)).scalars().all())


def _make_mock_response(data, input_tokens: int = 10, output_tokens: int = 5) -> MagicMock:
    """Build a structured Anthropic Message with text + tool_use blocks."""
    blocks: list[MagicMock] = []
    if isinstance(data, dict):
        reply_text = data.get("message")
        if isinstance(reply_text, str):
            text_block = MagicMock()
            text_block.type = "text"
            text_block.text = reply_text
            blocks.append(text_block)
        for action in (data.get("actions") or []):
            if not isinstance(action, dict) or "type" not in action:
                continue
            tool_block = MagicMock()
            tool_block.type = "tool_use"
            tool_block.name = action["type"]
            tool_block.input = {k: v for k, v in action.items() if k != "type"}
            blocks.append(tool_block)
    message = MagicMock()
    message.content = blocks
    usage = MagicMock()
    usage.input_tokens = input_tokens
    usage.output_tokens = output_tokens
    message.usage = usage
    return message


class TestChatRememberAndForget:
    @pytest.mark.asyncio
    async def test_remember_preference_persists_row_and_is_hidden_from_actions(
        self, client: AsyncClient, pro_auth_headers: dict, _clear_rate_limiter
    ):
        """``remember_preference`` should land in the table and NOT
        appear in the chat actions list."""
        with patch("app.services.ai_productivity.generate_chat_response") as mock_gen:
            mock_gen.return_value = {
                "message": "Got it.",
                "actions": [
                    {
                        "type": "remember_preference",
                        "preference_text": "Prefers morning workouts",
                    },
                ],
                "tokens_used": {"input": 5, "output": 5},
            }
            resp = await client.post(
                "/api/v1/ai/chat",
                json={
                    "message": "I always work out in the morning",
                    "conversation_id": "chat_2026-05-11_aaa",
                },
                headers=pro_auth_headers,
            )

        assert resp.status_code == 200, resp.text
        body = resp.json()
        assert all(a["type"] != "remember_preference" for a in body["actions"])
        assert len(body["user_preferences"]) == 1
        assert body["user_preferences"][0]["preference_text"] == "Prefers morning workouts"

        rows = await _list_preference_rows()
        assert [r.preference_text for r in rows] == ["Prefers morning workouts"]

    @pytest.mark.asyncio
    async def test_forget_preference_removes_row(
        self, client: AsyncClient, pro_auth_headers: dict, _clear_rate_limiter
    ):
        user_id = await _resolve_test_user_id()
        await _seed_preferences([
            {"id": "seed1", "user_id": user_id, "preference_text": "Likes loud music"},
        ])

        with patch("app.services.ai_productivity.generate_chat_response") as mock_gen:
            mock_gen.return_value = {
                "message": "Okay, forgotten.",
                "actions": [
                    {"type": "forget_preference", "preference_id": "seed1"},
                ],
                "tokens_used": {"input": 1, "output": 1},
            }
            resp = await client.post(
                "/api/v1/ai/chat",
                json={
                    "message": "forget that I like loud music",
                    "conversation_id": "chat_2026-05-11_bbb",
                },
                headers=pro_auth_headers,
            )

        assert resp.status_code == 200, resp.text
        body = resp.json()
        assert body["user_preferences"] == []
        assert await _list_preference_rows() == []

    @pytest.mark.asyncio
    async def test_remembering_when_full_evicts_oldest(
        self, client: AsyncClient, pro_auth_headers: dict, _clear_rate_limiter
    ):
        """When the AI emits a remember without a paired forget and the
        cap is already at 15, the oldest-by-updated_at row is dropped."""
        user_id = await _resolve_test_user_id()
        base = datetime.now(timezone.utc) - timedelta(days=20)
        seeds = [
            {
                "id": f"seed{i:02d}",
                "user_id": user_id,
                "preference_text": f"Preference {i}",
                "created_at": base + timedelta(days=i),
                "updated_at": base + timedelta(days=i),
            }
            for i in range(15)
        ]
        await _seed_preferences(seeds)

        with patch("app.services.ai_productivity.generate_chat_response") as mock_gen:
            mock_gen.return_value = {
                "message": "Sure.",
                "actions": [
                    {
                        "type": "remember_preference",
                        "preference_text": "Brand new preference",
                    },
                ],
                "tokens_used": {"input": 1, "output": 1},
            }
            resp = await client.post(
                "/api/v1/ai/chat",
                json={
                    "message": "I prefer sushi for lunch",
                    "conversation_id": "chat_2026-05-11_ccc",
                },
                headers=pro_auth_headers,
            )

        assert resp.status_code == 200, resp.text
        body = resp.json()
        texts = [p["preference_text"] for p in body["user_preferences"]]
        assert "Brand new preference" in texts
        assert "Preference 0" not in texts, "oldest should have been evicted"
        assert len(body["user_preferences"]) == 15

    @pytest.mark.asyncio
    async def test_remember_dedupes_case_insensitively(
        self, client: AsyncClient, pro_auth_headers: dict, _clear_rate_limiter
    ):
        user_id = await _resolve_test_user_id()
        await _seed_preferences([
            {"id": "dup1", "user_id": user_id, "preference_text": "Prefers Tea Over Coffee"},
        ])

        with patch("app.services.ai_productivity.generate_chat_response") as mock_gen:
            mock_gen.return_value = {
                "message": "Got it.",
                "actions": [
                    {
                        "type": "remember_preference",
                        "preference_text": "prefers tea over coffee",
                    },
                ],
                "tokens_used": {"input": 1, "output": 1},
            }
            resp = await client.post(
                "/api/v1/ai/chat",
                json={
                    "message": "tea over coffee",
                    "conversation_id": "chat_2026-05-11_ddd",
                },
                headers=pro_auth_headers,
            )

        assert resp.status_code == 200
        body = resp.json()
        assert len(body["user_preferences"]) == 1


class TestAiMemoryCrud:
    @pytest.mark.asyncio
    async def test_list_is_empty_for_new_user(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        resp = await client.get("/api/v1/ai/memory", headers=pro_auth_headers)
        assert resp.status_code == 200, resp.text
        body = resp.json()
        assert body["preferences"] == []
        assert body["cap"] == 15

    @pytest.mark.asyncio
    async def test_create_then_list(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        resp = await client.post(
            "/api/v1/ai/memory",
            json={"preference_text": "Likes pour-over coffee"},
            headers=pro_auth_headers,
        )
        assert resp.status_code == 201, resp.text
        created = resp.json()
        assert created["preference_text"] == "Likes pour-over coffee"
        assert created["source_message_id"] is None

        list_resp = await client.get("/api/v1/ai/memory", headers=pro_auth_headers)
        assert list_resp.status_code == 200
        prefs = list_resp.json()["preferences"]
        assert len(prefs) == 1
        assert prefs[0]["id"] == created["id"]

    @pytest.mark.asyncio
    async def test_create_returns_existing_when_text_matches_case_insensitive(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        first = await client.post(
            "/api/v1/ai/memory",
            json={"preference_text": "Likes Long Walks"},
            headers=pro_auth_headers,
        )
        assert first.status_code == 201
        first_id = first.json()["id"]

        second = await client.post(
            "/api/v1/ai/memory",
            json={"preference_text": "likes long walks"},
            headers=pro_auth_headers,
        )
        assert second.status_code in (200, 201)
        assert second.json()["id"] == first_id

    @pytest.mark.asyncio
    async def test_update_changes_text(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        create_resp = await client.post(
            "/api/v1/ai/memory",
            json={"preference_text": "Likes mornings"},
            headers=pro_auth_headers,
        )
        pid = create_resp.json()["id"]

        update_resp = await client.patch(
            f"/api/v1/ai/memory/{pid}",
            json={"preference_text": "Prefers mornings to evenings"},
            headers=pro_auth_headers,
        )
        assert update_resp.status_code == 200, update_resp.text
        assert update_resp.json()["preference_text"] == "Prefers mornings to evenings"

    @pytest.mark.asyncio
    async def test_update_unknown_id_404s(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        resp = await client.patch(
            "/api/v1/ai/memory/does-not-exist",
            json={"preference_text": "anything"},
            headers=pro_auth_headers,
        )
        assert resp.status_code == 404

    @pytest.mark.asyncio
    async def test_delete_removes_row(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        create_resp = await client.post(
            "/api/v1/ai/memory",
            json={"preference_text": "Likes minimalism"},
            headers=pro_auth_headers,
        )
        pid = create_resp.json()["id"]

        del_resp = await client.delete(
            f"/api/v1/ai/memory/{pid}", headers=pro_auth_headers
        )
        assert del_resp.status_code == 204

        list_resp = await client.get("/api/v1/ai/memory", headers=pro_auth_headers)
        assert list_resp.json()["preferences"] == []

    @pytest.mark.asyncio
    async def test_delete_unknown_id_is_idempotent(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        resp = await client.delete(
            "/api/v1/ai/memory/does-not-exist", headers=pro_auth_headers
        )
        assert resp.status_code == 204

    @pytest.mark.asyncio
    async def test_create_when_full_returns_409(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        user_id = await _resolve_test_user_id()
        await _seed_preferences([
            {
                "id": f"capseed{i:02d}",
                "user_id": user_id,
                "preference_text": f"Filler {i}",
            }
            for i in range(15)
        ])

        resp = await client.post(
            "/api/v1/ai/memory",
            json={"preference_text": "Brand new from settings"},
            headers=pro_auth_headers,
        )
        assert resp.status_code == 409
