"""Tests for D11 E.3 chat conversation persistence.

Covers:
- ``/api/v1/ai/chat`` writes user + assistant rows to ``chat_messages``.
- ``/api/v1/ai/chat/history`` retrieval, pagination, conversation filter,
  user isolation.
"""

import sys
import types
from unittest.mock import MagicMock, patch

import pytest
import pytest_asyncio
from httpx import AsyncClient
from sqlalchemy import select

from app.models import ChatMessage as ChatMessageModel


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


async def _post_chat(
    client: AsyncClient,
    headers: dict,
    *,
    message: str,
    conversation_id: str,
    response_message: str = "Sure thing.",
    actions: list | None = None,
    task_context: dict | None = None,
):
    actions = actions if actions is not None else []
    payload: dict = {"message": message, "conversation_id": conversation_id}
    if task_context is not None:
        payload["task_context"] = task_context
    with patch("app.services.ai_productivity.generate_chat_response") as mock_gen:
        mock_gen.return_value = {
            "message": response_message,
            "actions": actions,
            "tokens_used": {"input": 12, "output": 7},
        }
        return await client.post(
            "/api/v1/ai/chat", json=payload, headers=headers
        )


async def _list_chat_rows(conversation_id: str | None = None) -> list[ChatMessageModel]:
    from tests.conftest import TestSessionLocal

    async with TestSessionLocal() as session:
        stmt = select(ChatMessageModel)
        if conversation_id is not None:
            stmt = stmt.where(ChatMessageModel.conversation_id == conversation_id)
        stmt = stmt.order_by(ChatMessageModel.created_at)
        return list((await session.execute(stmt)).scalars().all())


class TestChatPersistenceOnPost:
    @pytest.mark.asyncio
    async def test_chat_post_writes_user_and_assistant_rows(
        self, client: AsyncClient, pro_auth_headers: dict, _clear_rate_limiter
    ):
        resp = await _post_chat(
            client,
            pro_auth_headers,
            message="I'm overwhelmed",
            conversation_id="chat_2026-05-08_aaaa",
            response_message="Let's break it down.",
            actions=[{"type": "start_timer", "minutes": 25}],
        )
        assert resp.status_code == 200, resp.text

        rows = await _list_chat_rows("chat_2026-05-08_aaaa")
        assert len(rows) == 2
        user_row, assistant_row = rows
        assert user_row.role == "user"
        assert user_row.content == "I'm overwhelmed"
        assert user_row.actions in (None, [])
        assert assistant_row.role == "assistant"
        assert assistant_row.content == "Let's break it down."
        assert assistant_row.actions and len(assistant_row.actions) == 1
        assert assistant_row.actions[0]["type"] == "start_timer"
        assert assistant_row.tokens_input == 12
        assert assistant_row.tokens_output == 7

    @pytest.mark.asyncio
    async def test_chat_post_persists_task_context_snapshot(
        self, client: AsyncClient, pro_auth_headers: dict, _clear_rate_limiter
    ):
        ctx = {
            "title": "Write quarterly report",
            "description": "Due Friday",
            "priority": 3,
        }
        resp = await _post_chat(
            client,
            pro_auth_headers,
            message="What should I do first?",
            conversation_id="chat_2026-05-08_bbbb",
            task_context=ctx,
        )
        assert resp.status_code == 200, resp.text

        rows = await _list_chat_rows("chat_2026-05-08_bbbb")
        assert len(rows) == 2
        user_row = rows[0]
        snap = user_row.task_context_snapshot
        assert snap is not None
        assert snap["title"] == "Write quarterly report"
        assert snap["priority"] == 3

    @pytest.mark.asyncio
    async def test_chat_post_records_chronological_order(
        self, client: AsyncClient, pro_auth_headers: dict, _clear_rate_limiter
    ):
        await _post_chat(
            client, pro_auth_headers,
            message="hi", conversation_id="chat_2026-05-08_cccc",
        )
        rows = await _list_chat_rows("chat_2026-05-08_cccc")
        # Assistant row's created_at must be > user row's so ordering by
        # created_at always returns user-then-assistant.
        assert rows[0].role == "user"
        assert rows[1].role == "assistant"
        assert rows[0].created_at < rows[1].created_at


class TestChatHistoryEndpoint:
    @pytest.mark.asyncio
    async def test_history_returns_persisted_turns_chronological(
        self, client: AsyncClient, pro_auth_headers: dict, _clear_rate_limiter
    ):
        await _post_chat(
            client, pro_auth_headers,
            message="first", conversation_id="chat_2026-05-08_x",
            response_message="reply 1",
        )
        await _post_chat(
            client, pro_auth_headers,
            message="second", conversation_id="chat_2026-05-08_x",
            response_message="reply 2",
        )

        resp = await client.get(
            "/api/v1/ai/chat/history",
            params={"conversation_id": "chat_2026-05-08_x"},
            headers=pro_auth_headers,
        )
        assert resp.status_code == 200, resp.text
        body = resp.json()
        contents = [m["content"] for m in body["messages"]]
        assert contents == ["first", "reply 1", "second", "reply 2"]
        assert body["next_before"] is None  # only 4 < limit=50

    @pytest.mark.asyncio
    async def test_history_filters_by_conversation_id(
        self, client: AsyncClient, pro_auth_headers: dict, _clear_rate_limiter
    ):
        await _post_chat(
            client, pro_auth_headers,
            message="day1 user", conversation_id="chat_2026-05-07_aaa",
        )
        await _post_chat(
            client, pro_auth_headers,
            message="day2 user", conversation_id="chat_2026-05-08_bbb",
        )

        resp = await client.get(
            "/api/v1/ai/chat/history",
            params={"conversation_id": "chat_2026-05-08_bbb"},
            headers=pro_auth_headers,
        )
        assert resp.status_code == 200
        body = resp.json()
        contents = [m["content"] for m in body["messages"]]
        # Only day2 turns; day1 is filtered out.
        assert "day1 user" not in contents
        assert "day2 user" in contents

    @pytest.mark.asyncio
    async def test_history_pagination_walks_oldest_via_before(
        self, client: AsyncClient, pro_auth_headers: dict, _clear_rate_limiter
    ):
        for i in range(3):
            await _post_chat(
                client, pro_auth_headers,
                message=f"msg-{i}",
                conversation_id="chat_2026-05-08_pg",
            )

        # 6 rows total (3 user + 3 assistant); ask for limit=2 -> last page
        resp = await client.get(
            "/api/v1/ai/chat/history",
            params={"conversation_id": "chat_2026-05-08_pg", "limit": 2},
            headers=pro_auth_headers,
        )
        assert resp.status_code == 200
        body = resp.json()
        assert len(body["messages"]) == 2
        assert body["next_before"] is not None

        # Walk back one more page
        resp2 = await client.get(
            "/api/v1/ai/chat/history",
            params={
                "conversation_id": "chat_2026-05-08_pg",
                "limit": 2,
                "before": body["next_before"],
            },
            headers=pro_auth_headers,
        )
        assert resp2.status_code == 200
        body2 = resp2.json()
        assert len(body2["messages"]) == 2
        # Pages do not overlap
        first_page_ids = {m["id"] for m in body["messages"]}
        second_page_ids = {m["id"] for m in body2["messages"]}
        assert first_page_ids.isdisjoint(second_page_ids)

    @pytest.mark.asyncio
    async def test_history_returns_empty_when_no_messages(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        resp = await client.get(
            "/api/v1/ai/chat/history",
            headers=pro_auth_headers,
        )
        assert resp.status_code == 200
        assert resp.json() == {"messages": [], "next_before": None}

    @pytest.mark.asyncio
    async def test_history_requires_auth(self, client: AsyncClient):
        resp = await client.get("/api/v1/ai/chat/history")
        assert resp.status_code in (401, 403)

    @pytest.mark.asyncio
    async def test_history_isolates_users(
        self, client: AsyncClient, pro_auth_headers: dict, _clear_rate_limiter
    ):
        # User A writes some messages
        await _post_chat(
            client, pro_auth_headers,
            message="alice secret", conversation_id="chat_2026-05-08_a",
        )

        # Register a second user and elevate to PRO
        reg = await client.post(
            "/api/v1/auth/register",
            json={
                "email": "bob@example.com",
                "name": "Bob",
                "password": "bobpass123",
            },
        )
        assert reg.status_code == 201, reg.text
        login = await client.post(
            "/api/v1/auth/login",
            json={"email": "bob@example.com", "password": "bobpass123"},
        )
        bob_token = login.json()["access_token"]
        bob_headers = {"Authorization": f"Bearer {bob_token}"}

        from tests.conftest import _elevate_tier, TEST_FIREBASE_UID
        await _elevate_tier(
            "bob@example.com", "PRO", firebase_uid=TEST_FIREBASE_UID + "-bob"
        )

        # Bob asks for history — must not see Alice's content
        resp = await client.get(
            "/api/v1/ai/chat/history", headers=bob_headers
        )
        assert resp.status_code == 200
        contents = [m["content"] for m in resp.json()["messages"]]
        assert "alice secret" not in contents

    @pytest.mark.asyncio
    async def test_history_rejects_invalid_before_cursor(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        resp = await client.get(
            "/api/v1/ai/chat/history",
            params={"before": "not-a-date"},
            headers=pro_auth_headers,
        )
        assert resp.status_code == 400
