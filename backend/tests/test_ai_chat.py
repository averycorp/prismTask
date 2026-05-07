"""Tests for the conversational coach (POST /api/v1/ai/chat).

Service tests run against a stubbed Anthropic client. Router tests use the
shared httpx AsyncClient + pro_auth_headers fixtures from conftest.
"""

import json
import sys
import types
from unittest.mock import MagicMock, patch

import pytest
from httpx import AsyncClient


def _make_mock_response(data, input_tokens: int = 42, output_tokens: int = 17) -> MagicMock:
    content_block = MagicMock()
    content_block.text = json.dumps(data)
    message = MagicMock()
    message.content = [content_block]
    usage = MagicMock()
    usage.input_tokens = input_tokens
    usage.output_tokens = output_tokens
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


class TestChatService:
    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_returns_message_and_actions(self):
        from app.services.ai_productivity import generate_chat_response

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_response(
                {
                    "message": "Sounds rough — let's break it down.",
                    "actions": [
                        {"type": "start_timer", "minutes": 25},
                    ],
                }
            )

            result = generate_chat_response(
                message="I'm overwhelmed",
                conversation_id="chat_2026-04-28_abc12345",
            )
            assert result["message"] == "Sounds rough — let's break it down."
            assert result["actions"] == [{"type": "start_timer", "minutes": 25}]
            assert result["tokens_used"] == {"input": 42, "output": 17}

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_accepts_empty_actions_list(self):
        from app.services.ai_productivity import generate_chat_response

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_response(
                {"message": "Sure thing.", "actions": []}
            )

            result = generate_chat_response(
                message="hi",
                conversation_id="chat_x",
            )
            assert result["actions"] == []

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_retries_then_succeeds_on_malformed_first_response(self):
        from app.services.ai_productivity import generate_chat_response

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client

            bad_response = MagicMock()
            bad_content = MagicMock()
            bad_content.text = "not valid json"
            bad_response.content = [bad_content]
            bad_response.usage = MagicMock(input_tokens=0, output_tokens=0)

            good_response = _make_mock_response(
                {"message": "Hello", "actions": []}
            )
            mock_client.messages.create.side_effect = [bad_response, good_response]

            result = generate_chat_response(
                message="anything",
                conversation_id="chat_x",
            )
            assert result["message"] == "Hello"
            assert mock_client.messages.create.call_count == 2

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_raises_value_error_on_missing_message_field(self):
        from app.services.ai_productivity import generate_chat_response

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_response(
                {"actions": []}  # missing "message"
            )

            with pytest.raises(ValueError):
                generate_chat_response(
                    message="anything",
                    conversation_id="chat_x",
                )

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_passes_task_context_id_to_prompt(self):
        from app.services.ai_productivity import generate_chat_response

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_response(
                {"message": "ok", "actions": []}
            )

            generate_chat_response(
                message="break this down",
                conversation_id="chat_x",
                task_context_id=12345,
            )
            sent_payload = mock_client.messages.create.call_args.kwargs["messages"][0]["content"]
            payload = json.loads(sent_payload)
            assert payload["task_context_id"] == 12345
            assert payload["user_message"] == "break this down"


class TestChatEndpoint:
    @pytest.mark.asyncio
    async def test_endpoint_returns_message_and_echoes_conversation_id(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from app.routers.ai import chat_rate_limiter

        chat_rate_limiter._requests.clear()

        with patch("app.services.ai_productivity.generate_chat_response") as mock_gen:
            mock_gen.return_value = {
                "message": "Let's talk through it.",
                "actions": [{"type": "start_timer", "minutes": 25}],
                "tokens_used": {"input": 50, "output": 20},
            }

            resp = await client.post(
                "/api/v1/ai/chat",
                json={
                    "message": "I'm stuck on a task",
                    "conversation_id": "chat_2026-04-28_aaaa",
                },
                headers=pro_auth_headers,
            )
            assert resp.status_code == 200, resp.text
            body = resp.json()
            assert body["message"] == "Let's talk through it."
            assert body["conversation_id"] == "chat_2026-04-28_aaaa"
            assert body["actions"] == [{
                "type": "start_timer",
                "task_id": None,
                "task_ids": None,
                "to": None,
                "subtasks": None,
                "minutes": 25,
                "title": None,
                "due": None,
                "priority": None,
            }]
            assert body["tokens_used"] == {"input": 50, "output": 20}

    @pytest.mark.asyncio
    async def test_endpoint_rejects_empty_message(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from app.routers.ai import chat_rate_limiter

        chat_rate_limiter._requests.clear()

        resp = await client.post(
            "/api/v1/ai/chat",
            json={"message": "", "conversation_id": "chat_x"},
            headers=pro_auth_headers,
        )
        # Pydantic catches min_length=1.
        assert resp.status_code == 422

    @pytest.mark.asyncio
    async def test_endpoint_drops_unknown_action_types(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        """Haiku occasionally invents action types the client can't apply.
        The router validates each action against the schema and silently
        drops unknown ones rather than 500-ing the whole turn."""
        from app.routers.ai import chat_rate_limiter

        chat_rate_limiter._requests.clear()

        with patch("app.services.ai_productivity.generate_chat_response") as mock_gen:
            mock_gen.return_value = {
                "message": "Here are some options.",
                "actions": [
                    {"type": "start_timer", "minutes": 25},
                    {"type": "send_email"},  # not a valid type
                ],
                "tokens_used": {"input": 1, "output": 1},
            }

            resp = await client.post(
                "/api/v1/ai/chat",
                json={"message": "hello", "conversation_id": "chat_x"},
                headers=pro_auth_headers,
            )
            assert resp.status_code == 200, resp.text
            body = resp.json()
            types = [a["type"] for a in body["actions"]]
            assert "start_timer" in types
            assert "send_email" not in types

    @pytest.mark.asyncio
    async def test_endpoint_503_when_anthropic_unavailable(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from app.routers.ai import chat_rate_limiter

        chat_rate_limiter._requests.clear()

        with patch("app.services.ai_productivity.generate_chat_response") as mock_gen:
            mock_gen.side_effect = RuntimeError("ANTHROPIC_API_KEY not set")

            resp = await client.post(
                "/api/v1/ai/chat",
                json={"message": "hi", "conversation_id": "chat_x"},
                headers=pro_auth_headers,
            )
            assert resp.status_code == 503

    @pytest.mark.asyncio
    async def test_endpoint_500_on_malformed_ai_response(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from app.routers.ai import chat_rate_limiter

        chat_rate_limiter._requests.clear()

        with patch("app.services.ai_productivity.generate_chat_response") as mock_gen:
            mock_gen.side_effect = ValueError("bad json")

            resp = await client.post(
                "/api/v1/ai/chat",
                json={"message": "hi", "conversation_id": "chat_x"},
                headers=pro_auth_headers,
            )
            assert resp.status_code == 500

    @pytest.mark.asyncio
    async def test_endpoint_403_for_free_tier(
        self, client: AsyncClient, auth_headers: dict
    ):
        """Free users hit the daily AI rate limiter's tier gate (limit=0)
        before any Anthropic call, returning 403."""
        from app.routers.ai import chat_rate_limiter

        chat_rate_limiter._requests.clear()

        resp = await client.post(
            "/api/v1/ai/chat",
            json={"message": "hi", "conversation_id": "chat_x"},
            headers=auth_headers,
        )
        assert resp.status_code == 403

    @pytest.mark.asyncio
    async def test_endpoint_accepts_free_user_with_active_beta_pro(
        self, client: AsyncClient, auth_headers: dict
    ):
        """Beta-tester unlock codes elevate FREE users to PRO for AI gates.

        Regression for the bug where ``current_user.effective_tier`` (the
        sync property) skipped the beta-code lookup that ``/auth/me``
        applies to its response, so beta-pro accounts hit 403 on every
        AI endpoint despite being told they were Pro.
        """
        from datetime import datetime, timedelta, timezone
        from sqlalchemy import select
        from app.models import BetaCode, BetaCodeRedemption, User as UserModel
        from app.routers.ai import chat_rate_limiter
        from tests.conftest import TestSessionLocal

        chat_rate_limiter._requests.clear()

        async with TestSessionLocal() as session:
            await session.execute(
                select(UserModel).where(UserModel.email == "test@example.com")
            )
            user_row = (
                await session.execute(
                    select(UserModel).where(UserModel.email == "test@example.com")
                )
            ).scalar_one()
            session.add(BetaCode(code="TEST-BETA"))
            session.add(
                BetaCodeRedemption(
                    code="TEST-BETA",
                    user_id=user_row.id,
                    grants_pro_until=datetime.now(timezone.utc) + timedelta(days=30),
                )
            )
            await session.commit()

        with patch("app.services.ai_productivity.generate_chat_response") as mock_gen:
            mock_gen.return_value = {
                "message": "Sure thing.",
                "actions": [],
                "tokens_used": {"input": 1, "output": 1},
            }
            resp = await client.post(
                "/api/v1/ai/chat",
                json={"message": "hi", "conversation_id": "chat_x"},
                headers=auth_headers,
            )

        assert resp.status_code == 200, resp.text
        assert resp.json()["message"] == "Sure thing."

    @pytest.mark.asyncio
    async def test_endpoint_451_when_ai_features_disabled(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        """The PII-egress opt-out header must short-circuit the chat
        endpoint just like every other AI router endpoint."""
        from app.middleware.ai_gate import HEADER_NAME, HEADER_VALUE_DISABLED
        from app.routers.ai import chat_rate_limiter

        chat_rate_limiter._requests.clear()

        headers = {**pro_auth_headers, HEADER_NAME: HEADER_VALUE_DISABLED}
        resp = await client.post(
            "/api/v1/ai/chat",
            json={"message": "hi", "conversation_id": "chat_x"},
            headers=headers,
        )
        assert resp.status_code == 451

    @pytest.mark.asyncio
    async def test_endpoint_rate_limits_after_burst(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from app.routers.ai import chat_rate_limiter

        chat_rate_limiter._requests.clear()
        original_max = chat_rate_limiter.max_requests
        chat_rate_limiter.max_requests = 1

        try:
            with patch("app.services.ai_productivity.generate_chat_response") as mock_gen:
                mock_gen.return_value = {
                    "message": "ok",
                    "actions": [],
                    "tokens_used": {"input": 1, "output": 1},
                }
                resp1 = await client.post(
                    "/api/v1/ai/chat",
                    json={"message": "hi", "conversation_id": "chat_x"},
                    headers=pro_auth_headers,
                )
                assert resp1.status_code == 200, resp1.text

                resp2 = await client.post(
                    "/api/v1/ai/chat",
                    json={"message": "hi", "conversation_id": "chat_x"},
                    headers=pro_auth_headers,
                )
                assert resp2.status_code == 429
        finally:
            chat_rate_limiter.max_requests = original_max
            chat_rate_limiter._requests.clear()

    @pytest.mark.asyncio
    async def test_endpoint_passes_task_context_id_to_service(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from app.routers.ai import chat_rate_limiter

        chat_rate_limiter._requests.clear()

        with patch("app.services.ai_productivity.generate_chat_response") as mock_gen:
            mock_gen.return_value = {
                "message": "Got it.",
                "actions": [],
                "tokens_used": {"input": 1, "output": 1},
            }

            resp = await client.post(
                "/api/v1/ai/chat",
                json={
                    "message": "help me with this",
                    "conversation_id": "chat_x",
                    "task_context_id": 9876,
                },
                headers=pro_auth_headers,
            )
            assert resp.status_code == 200, resp.text
            assert mock_gen.call_args.kwargs["task_context_id"] == 9876


class TestChatContextBlock:
    """Phase 2 fix #1 (audit Axes A.1 + E.1): the backend now forwards
    rolling user/assistant history and a task_context snapshot to Anthropic
    so the model has multi-turn memory and grounded task content instead of
    an opaque integer it cannot dereference."""

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_service_forwards_history_as_messages_array(self):
        from app.services.ai_productivity import generate_chat_response

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_response(
                {"message": "ok", "actions": []}
            )

            generate_chat_response(
                message="and what about today?",
                conversation_id="chat_x",
                history=[
                    {"role": "user", "content": "what's overdue?"},
                    {"role": "assistant", "content": "two tasks are overdue."},
                ],
            )
            sent = mock_client.messages.create.call_args.kwargs["messages"]
            # 2 history entries + 1 latest user turn carrying the structured
            # context block = 3 total.
            assert len(sent) == 3
            assert sent[0] == {"role": "user", "content": "what's overdue?"}
            assert sent[1] == {"role": "assistant", "content": "two tasks are overdue."}
            assert sent[2]["role"] == "user"
            assert "and what about today?" in sent[2]["content"]

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_service_drops_invalid_history_entries(self):
        """Defensive: the schema layer caps role to user|assistant and
        forces non-empty content, but the service must not blow up if a
        unit test or future caller bypasses the schema."""
        from app.services.ai_productivity import generate_chat_response

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_response(
                {"message": "ok", "actions": []}
            )

            generate_chat_response(
                message="hi",
                conversation_id="chat_x",
                history=[
                    {"role": "system", "content": "should be dropped"},
                    {"role": "user", "content": ""},  # empty content dropped
                    {"role": "user", "content": "kept"},
                ],
            )
            sent = mock_client.messages.create.call_args.kwargs["messages"]
            # 1 valid history entry + 1 latest user turn = 2.
            assert len(sent) == 2
            assert sent[0] == {"role": "user", "content": "kept"}

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_service_includes_task_context_in_user_payload(self):
        from app.services.ai_productivity import generate_chat_response

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_response(
                {"message": "ok", "actions": []}
            )

            generate_chat_response(
                message="how do I start?",
                conversation_id="chat_x",
                task_context_id=42,
                task_context={
                    "title": "Finish thesis chapter 3",
                    "description": "Discussion section, literature review",
                    "due_date": "2026-05-12",
                    "priority": 3,
                    "project_name": "Thesis",
                    "is_completed": False,
                },
            )
            sent_payload = mock_client.messages.create.call_args.kwargs["messages"][-1]["content"]
            payload = json.loads(sent_payload)
            assert payload["task_context_id"] == 42
            assert payload["task_context"]["title"] == "Finish thesis chapter 3"
            assert payload["task_context"]["project_name"] == "Thesis"
            assert payload["task_context"]["priority"] == 3

    @pytest.mark.asyncio
    async def test_endpoint_forwards_history_and_task_context(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        """Router → service plumbing test: history + task_context arrive at
        the service layer without losing fields or shape."""
        from app.routers.ai import chat_rate_limiter

        chat_rate_limiter._requests.clear()

        with patch("app.services.ai_productivity.generate_chat_response") as mock_gen:
            mock_gen.return_value = {
                "message": "Got it.",
                "actions": [],
                "tokens_used": {"input": 1, "output": 1},
            }

            resp = await client.post(
                "/api/v1/ai/chat",
                json={
                    "message": "and what about now?",
                    "conversation_id": "chat_x",
                    "task_context_id": 7,
                    "task_context": {
                        "title": "Pay rent",
                        "due_date": "2026-05-10",
                        "priority": 4,
                    },
                    "history": [
                        {"role": "user", "content": "what's due tomorrow?"},
                        {"role": "assistant", "content": "rent is."},
                    ],
                },
                headers=pro_auth_headers,
            )
            assert resp.status_code == 200, resp.text
            kwargs = mock_gen.call_args.kwargs
            assert kwargs["task_context_id"] == 7
            assert kwargs["task_context"]["title"] == "Pay rent"
            assert kwargs["task_context"]["priority"] == 4
            assert len(kwargs["history"]) == 2
            assert kwargs["history"][0] == {
                "role": "user",
                "content": "what's due tomorrow?",
            }

    @pytest.mark.asyncio
    async def test_endpoint_rejects_invalid_history_role(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from app.routers.ai import chat_rate_limiter

        chat_rate_limiter._requests.clear()

        resp = await client.post(
            "/api/v1/ai/chat",
            json={
                "message": "hi",
                "conversation_id": "chat_x",
                "history": [{"role": "system", "content": "bad"}],
            },
            headers=pro_auth_headers,
        )
        # Pydantic's pattern validator on ChatHistoryEntry.role rejects this.
        assert resp.status_code == 422

    @pytest.mark.asyncio
    async def test_endpoint_rejects_history_over_cap(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from app.routers.ai import chat_rate_limiter

        chat_rate_limiter._requests.clear()

        # 13 entries — one over the schema's max_length=12.
        oversized = [{"role": "user", "content": str(i)} for i in range(13)]
        resp = await client.post(
            "/api/v1/ai/chat",
            json={
                "message": "hi",
                "conversation_id": "chat_x",
                "history": oversized,
            },
            headers=pro_auth_headers,
        )
        assert resp.status_code == 422

    @pytest.mark.asyncio
    async def test_endpoint_still_accepts_legacy_tier_field(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        """Back-compat: in-flight Android builds still send `tier` even
        though the server no longer reads it for chat. The schema must
        silently accept the field so older clients don't 422 mid-rollout."""
        from app.routers.ai import chat_rate_limiter

        chat_rate_limiter._requests.clear()

        with patch("app.services.ai_productivity.generate_chat_response") as mock_gen:
            mock_gen.return_value = {
                "message": "ok",
                "actions": [],
                "tokens_used": {"input": 1, "output": 1},
            }

            resp = await client.post(
                "/api/v1/ai/chat",
                json={
                    "message": "hi",
                    "conversation_id": "chat_x",
                    "tier": "PRO",
                },
                headers=pro_auth_headers,
            )
            assert resp.status_code == 200, resp.text
            # `tier` must NOT be forwarded to the service — it's been
            # removed from the function signature.
            assert "tier" not in mock_gen.call_args.kwargs
