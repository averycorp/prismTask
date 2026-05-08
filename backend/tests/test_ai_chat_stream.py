"""Tests for the streaming conversational coach (POST /api/v1/ai/chat/stream).

F7 D.1 + F8 D.2 mega bundle. Companion to test_ai_chat.py — covers the
SSE endpoint that streams Claude responses token-by-token. Service tests
mock Anthropic's ``messages.stream()`` context manager; router tests
exercise the SSE event grammar over the shared httpx AsyncClient.
"""

import json
import sys
import types
from contextlib import contextmanager
from unittest.mock import MagicMock, patch

import pytest
from httpx import AsyncClient


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


def _fake_stream_context(deltas: list[str], input_tokens: int = 30, output_tokens: int = 12):
    """Build a fake ``client.messages.stream(...)`` context manager.

    Mirrors the Anthropic SDK 0.42 surface area the streaming chat
    handler uses: ``with client.messages.stream(...) as stream`` exposes
    a ``text_stream`` iterator over text deltas plus a
    ``get_final_message()`` method returning a Message-like object with
    a ``usage`` field.
    """
    final_message = MagicMock()
    final_message.usage = MagicMock()
    final_message.usage.input_tokens = input_tokens
    final_message.usage.output_tokens = output_tokens

    @contextmanager
    def _ctx(**_kwargs):
        stream = MagicMock()
        stream.text_stream = iter(deltas)
        stream.get_final_message = MagicMock(return_value=final_message)
        yield stream

    return _ctx


# ---------------------------------------------------------------------------
# _extract_partial_message_field — partial JSON scanner
# ---------------------------------------------------------------------------

class TestExtractPartialMessageField:
    def test_returns_none_when_message_key_not_yet_seen(self):
        from app.services.ai_productivity import _extract_partial_message_field

        assert _extract_partial_message_field("") is None
        assert _extract_partial_message_field("{") is None
        assert _extract_partial_message_field('{"actions": []}') is None
        assert _extract_partial_message_field('{"message"') is None
        assert _extract_partial_message_field('{"message":') is None

    def test_extracts_partial_value_before_closing_quote(self):
        from app.services.ai_productivity import _extract_partial_message_field

        assert _extract_partial_message_field('{"message": "Hello world') == "Hello world"
        assert _extract_partial_message_field('{"message": "') == ""

    def test_extracts_value_at_closing_quote(self):
        from app.services.ai_productivity import _extract_partial_message_field

        assert _extract_partial_message_field('{"message": "Hello"') == "Hello"
        assert (
            _extract_partial_message_field('{"message": "Hello", "actions": []}')
            == "Hello"
        )

    def test_handles_escaped_quote_inside_value(self):
        from app.services.ai_productivity import _extract_partial_message_field

        assert (
            _extract_partial_message_field('{"message": "with \\"quote\\" inside')
            == 'with "quote" inside'
        )

    def test_handles_other_basic_escapes(self):
        from app.services.ai_productivity import _extract_partial_message_field

        assert (
            _extract_partial_message_field('{"message": "line1\\nline2')
            == "line1\nline2"
        )
        assert (
            _extract_partial_message_field('{"message": "tab\\there')
            == "tab\there"
        )
        assert (
            _extract_partial_message_field('{"message": "back\\\\slash')
            == "back\\slash"
        )

    def test_drops_trailing_partial_backslash(self):
        from app.services.ai_productivity import _extract_partial_message_field

        # Stream cuts mid-escape — drop the trailing backslash so we
        # don't emit it raw; it'll resolve next delta.
        assert _extract_partial_message_field('{"message": "trail\\') == "trail"

    def test_decodes_complete_unicode_escape(self):
        from app.services.ai_productivity import _extract_partial_message_field

        assert (
            _extract_partial_message_field('{"message": "smile\\u263a"')
            == "smile☺"
        )

    def test_drops_partial_unicode_escape(self):
        from app.services.ai_productivity import _extract_partial_message_field

        # Three hex digits collected so far — drop the partial \u sequence.
        assert _extract_partial_message_field('{"message": "smile\\u26') == "smile"

    def test_value_with_embedded_brace(self):
        """Chat replies sometimes mention JSON-shaped text. The scanner
        must ignore unquoted ``}`` inside the value — only the closing
        quote terminates the scan."""
        from app.services.ai_productivity import _extract_partial_message_field

        assert (
            _extract_partial_message_field('{"message": "use {key: value}"')
            == "use {key: value}"
        )


# ---------------------------------------------------------------------------
# generate_chat_response_stream — service-layer streaming
# ---------------------------------------------------------------------------

class TestChatStreamService:
    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_emits_token_events_in_order(self):
        from app.services.ai_productivity import generate_chat_response_stream

        # Split a complete JSON envelope across three deltas. The scanner
        # should reconstruct the message field token-by-token.
        deltas = [
            '{"message": "Hello',
            ' there',
            '", "actions": []}',
        ]
        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.stream = _fake_stream_context(deltas)

            events = list(
                generate_chat_response_stream(
                    message="hi",
                    conversation_id="chat_x",
                )
            )

        token_events = [e for e in events if e["type"] == "token"]
        # Each delta that grew the message field emits a token.
        assert len(token_events) == 2
        assert token_events[0]["text"] == "Hello"
        assert token_events[1]["text"] == " there"

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_emits_done_with_validated_actions(self):
        from app.services.ai_productivity import generate_chat_response_stream

        deltas = [
            '{"message": "ok"',
            ', "actions": [{"type": "start_timer", "minutes": 25}]}',
        ]
        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.stream = _fake_stream_context(
                deltas, input_tokens=11, output_tokens=7
            )

            events = list(
                generate_chat_response_stream(
                    message="hi",
                    conversation_id="chat_x",
                )
            )

        done_events = [e for e in events if e["type"] == "done"]
        assert len(done_events) == 1
        done = done_events[0]
        assert done["message"] == "ok"
        assert done["actions"] == [{"type": "start_timer", "minutes": 25}]
        assert done["tokens_used"] == {"input": 11, "output": 7}

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_emits_error_on_anthropic_failure(self):
        from app.services.ai_productivity import generate_chat_response_stream

        @contextmanager
        def _raising_ctx(**_kwargs):
            raise RuntimeError("upstream boom")
            yield  # pragma: no cover

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.stream = _raising_ctx

            events = list(
                generate_chat_response_stream(
                    message="hi",
                    conversation_id="chat_x",
                )
            )

        assert len(events) == 1
        assert events[0]["type"] == "error"
        assert events[0]["code"] == "upstream_error"
        # Error message is human-readable, not the raw exception — keeps
        # internals out of the wire.
        assert events[0]["message"] == "AI service temporarily unavailable"

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_emits_error_on_malformed_final_json(self):
        from app.services.ai_productivity import generate_chat_response_stream

        # Stream completes with no parseable JSON at all.
        deltas = ["this is not JSON at all"]
        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.stream = _fake_stream_context(deltas)

            events = list(
                generate_chat_response_stream(
                    message="hi",
                    conversation_id="chat_x",
                )
            )

        # No tokens (no "message": opener), then a parse error.
        token_events = [e for e in events if e["type"] == "token"]
        error_events = [e for e in events if e["type"] == "error"]
        assert len(token_events) == 0
        assert len(error_events) == 1
        assert error_events[0]["code"] == "parse_error"

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_skips_empty_text_deltas(self):
        from app.services.ai_productivity import generate_chat_response_stream

        # Some Anthropic stream events fire empty text deltas (e.g.
        # content_block_start). The handler must skip these without
        # generating spurious empty token events.
        deltas = ["", '{"message": "ok", "actions": []}', ""]
        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.stream = _fake_stream_context(deltas)

            events = list(
                generate_chat_response_stream(
                    message="hi",
                    conversation_id="chat_x",
                )
            )

        token_events = [e for e in events if e["type"] == "token"]
        assert len(token_events) == 1
        assert token_events[0]["text"] == "ok"


# ---------------------------------------------------------------------------
# Router — POST /api/v1/ai/chat/stream
# ---------------------------------------------------------------------------

def _parse_sse_body(body: str) -> list[tuple[str, dict]]:
    """Parse an SSE response body into (event_name, data_dict) tuples."""
    out: list[tuple[str, dict]] = []
    for chunk in body.split("\n\n"):
        if not chunk.strip():
            continue
        event_name = "message"
        data_lines: list[str] = []
        for line in chunk.split("\n"):
            if line.startswith("event: "):
                event_name = line[len("event: "):]
            elif line.startswith("data: "):
                data_lines.append(line[len("data: "):])
        if data_lines:
            try:
                data = json.loads("\n".join(data_lines))
            except json.JSONDecodeError:
                data = {"_raw": "\n".join(data_lines)}
        else:
            data = {}
        out.append((event_name, data))
    return out


class TestChatStreamEndpoint:
    @pytest.mark.asyncio
    async def test_endpoint_streams_token_then_done(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from app.routers.ai import chat_rate_limiter

        chat_rate_limiter._requests.clear()

        def _fake_stream(**_kwargs):
            yield {"type": "token", "text": "Hi"}
            yield {"type": "token", "text": " there"}
            yield {
                "type": "done",
                "message": "Hi there",
                "actions": [{"type": "start_timer", "minutes": 25}],
                "tokens_used": {"input": 10, "output": 4},
            }

        with patch(
            "app.services.ai_productivity.generate_chat_response_stream",
            side_effect=_fake_stream,
        ):
            resp = await client.post(
                "/api/v1/ai/chat/stream",
                json={"message": "hi", "conversation_id": "chat_x"},
                headers=pro_auth_headers,
            )

        assert resp.status_code == 200, resp.text
        assert resp.headers["content-type"].startswith("text/event-stream")
        events = _parse_sse_body(resp.text)
        assert [name for name, _ in events] == ["token", "token", "done"]
        assert events[0][1] == {"text": "Hi"}
        assert events[1][1] == {"text": " there"}
        done = events[2][1]
        assert done["message"] == "Hi there"
        assert done["conversation_id"] == "chat_x"
        # Action got validated through ChatActionPayload — full field set
        # is echoed (parity with single-shot endpoint).
        assert done["actions"][0]["type"] == "start_timer"
        assert done["actions"][0]["minutes"] == 25
        assert done["tokens_used"] == {"input": 10, "output": 4}

    @pytest.mark.asyncio
    async def test_endpoint_drops_malformed_actions_in_done(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        """Parity with single-shot endpoint at ai.py:944-960 — actions
        that don't validate against ChatActionPayload are dropped, not
        500'd, so a single bad action doesn't kill the whole turn."""
        from app.routers.ai import chat_rate_limiter

        chat_rate_limiter._requests.clear()

        def _fake_stream(**_kwargs):
            yield {
                "type": "done",
                "message": "ok",
                "actions": [
                    {"type": "start_timer", "minutes": 25},
                    {"type": "send_email"},  # invalid type
                ],
                "tokens_used": {"input": 1, "output": 1},
            }

        with patch(
            "app.services.ai_productivity.generate_chat_response_stream",
            side_effect=_fake_stream,
        ):
            resp = await client.post(
                "/api/v1/ai/chat/stream",
                json={"message": "hi", "conversation_id": "chat_x"},
                headers=pro_auth_headers,
            )

        assert resp.status_code == 200
        events = _parse_sse_body(resp.text)
        done = next(d for n, d in events if n == "done")
        types = [a["type"] for a in done["actions"]]
        assert types == ["start_timer"]

    @pytest.mark.asyncio
    async def test_endpoint_emits_error_event_on_runtime_error(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from app.routers.ai import chat_rate_limiter

        chat_rate_limiter._requests.clear()

        def _failing_stream(**_kwargs):
            raise RuntimeError("client missing")
            yield  # pragma: no cover

        with patch(
            "app.services.ai_productivity.generate_chat_response_stream",
            side_effect=_failing_stream,
        ):
            resp = await client.post(
                "/api/v1/ai/chat/stream",
                json={"message": "hi", "conversation_id": "chat_x"},
                headers=pro_auth_headers,
            )

        assert resp.status_code == 200
        events = _parse_sse_body(resp.text)
        assert events[-1][0] == "error"
        assert events[-1][1]["code"] == "unavailable"

    @pytest.mark.asyncio
    async def test_endpoint_rejects_empty_message(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from app.routers.ai import chat_rate_limiter

        chat_rate_limiter._requests.clear()

        resp = await client.post(
            "/api/v1/ai/chat/stream",
            json={"message": "", "conversation_id": "chat_x"},
            headers=pro_auth_headers,
        )
        assert resp.status_code == 422

    @pytest.mark.asyncio
    async def test_endpoint_includes_no_buffer_headers(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        """Discourage proxy buffering so SSE chunks reach clients
        promptly. ``X-Accel-Buffering: no`` is the canonical hint for
        nginx-flavored intermediaries."""
        from app.routers.ai import chat_rate_limiter

        chat_rate_limiter._requests.clear()

        def _fake_stream(**_kwargs):
            yield {
                "type": "done",
                "message": "ok",
                "actions": [],
                "tokens_used": {"input": 0, "output": 0},
            }

        with patch(
            "app.services.ai_productivity.generate_chat_response_stream",
            side_effect=_fake_stream,
        ):
            resp = await client.post(
                "/api/v1/ai/chat/stream",
                json={"message": "hi", "conversation_id": "chat_x"},
                headers=pro_auth_headers,
            )

        assert resp.headers.get("x-accel-buffering") == "no"
        assert resp.headers.get("cache-control") == "no-cache"
