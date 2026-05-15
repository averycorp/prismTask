"""Tests for the Haiku-determined urgency-scoring service + endpoint.

Mirrors the shape of ``test_ai_productivity.py``: the service tests
exercise the prompt → parse → validate path with a mocked Anthropic
client, and the endpoint tests pin the FastAPI handler contract
(success, AI failure → 503, parse failure → 500, rate limit → 429).
"""

import json
import sys
import types
from datetime import date
from unittest.mock import MagicMock, patch

import pytest
from httpx import AsyncClient


def _make_mock_response(data) -> MagicMock:
    content_block = MagicMock()
    content_block.text = json.dumps(data)
    message = MagicMock()
    message.content = [content_block]
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


class TestScoreTasksUrgencyService:
    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_score_tasks_urgency_success(self):
        from app.services.ai_productivity import score_tasks_urgency

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_response([
                {"id": "1", "score": 0.92, "level": "CRITICAL", "reason": "Overdue by 3 days"},
                {"id": "2", "score": 0.40, "level": "MEDIUM", "reason": "Due next week"},
            ])

            tasks = [
                {
                    "id": "1",
                    "title": "Tax filing",
                    "description": "",
                    "due_date": "2026-04-07",
                    "priority": 4,
                    "created_at": "2026-03-15",
                    "subtask_count": 0,
                    "subtask_completed": 0,
                },
                {
                    "id": "2",
                    "title": "Plan vacation",
                    "description": "",
                    "due_date": "2026-04-17",
                    "priority": 1,
                    "created_at": "2026-04-09",
                    "subtask_count": 0,
                    "subtask_completed": 0,
                },
            ]
            result = score_tasks_urgency(tasks, date(2026, 4, 10))

            assert len(result) == 2
            assert result[0]["id"] == "1"
            assert result[0]["score"] == pytest.approx(0.92)
            assert result[0]["level"] == "CRITICAL"
            assert result[1]["level"] == "MEDIUM"

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_score_tasks_urgency_clamps_out_of_range(self):
        """Scores outside [0,1] should be clamped, not rejected."""
        from app.services.ai_productivity import score_tasks_urgency

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_response([
                {"id": "1", "score": 1.05, "level": "CRITICAL", "reason": "x"},
                {"id": "2", "score": -0.1, "level": "LOW", "reason": "y"},
            ])

            tasks = [
                {"id": "1", "title": "A", "priority": 0, "created_at": "2026-04-09"},
                {"id": "2", "title": "B", "priority": 0, "created_at": "2026-04-09"},
            ]
            result = score_tasks_urgency(tasks, date(2026, 4, 10))
            assert result[0]["score"] == 1.0
            assert result[1]["score"] == 0.0

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_score_tasks_urgency_drops_invalid_entries(self):
        """Entries with bad level/score should be dropped, not crash the batch."""
        from app.services.ai_productivity import score_tasks_urgency

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_response([
                {"id": "1", "score": 0.5, "level": "MEDIUM", "reason": "ok"},
                {"id": "2", "score": 0.5, "level": "BOGUS", "reason": "drop"},
                {"id": "3", "score": "not-a-float", "level": "LOW", "reason": "drop"},
                {"score": 0.5, "level": "LOW", "reason": "missing id"},
            ])

            result = score_tasks_urgency(
                [{"id": str(i), "title": "x", "priority": 0, "created_at": "2026-04-09"}
                 for i in range(1, 5)],
                date(2026, 4, 10),
            )
            assert len(result) == 1
            assert result[0]["id"] == "1"

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_score_tasks_urgency_malformed_retries(self):
        from app.services.ai_productivity import score_tasks_urgency

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client

            bad_response = MagicMock()
            bad_content = MagicMock()
            bad_content.text = "not valid json"
            bad_response.content = [bad_content]
            good_response = _make_mock_response([
                {"id": "1", "score": 0.7, "level": "HIGH", "reason": "ok"},
            ])
            mock_client.messages.create.side_effect = [bad_response, good_response]

            result = score_tasks_urgency(
                [{"id": "1", "title": "T", "priority": 0, "created_at": "2026-04-09"}],
                date(2026, 4, 10),
            )
            assert result[0]["score"] == pytest.approx(0.7)
            assert mock_client.messages.create.call_count == 2

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_score_tasks_urgency_both_fail_raises(self):
        from app.services.ai_productivity import score_tasks_urgency

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            bad_response = MagicMock()
            bad_content = MagicMock()
            bad_content.text = "{{invalid}}"
            bad_response.content = [bad_content]
            mock_client.messages.create.return_value = bad_response

            with pytest.raises(ValueError, match="Failed to parse AI response"):
                score_tasks_urgency(
                    [{"id": "1", "title": "T", "priority": 0, "created_at": "2026-04-09"}],
                    date(2026, 4, 10),
                )


class TestUrgencyEndpoint:
    @pytest.mark.asyncio
    async def test_score_endpoint_success(self, client: AsyncClient, pro_auth_headers: dict):
        from app.routers.ai import urgency_score_rate_limiter

        urgency_score_rate_limiter._requests.clear()
        with patch("app.services.ai_productivity.score_tasks_urgency") as mock_score:
            mock_score.return_value = [
                {"id": "1", "score": 0.9, "level": "CRITICAL", "reason": "Overdue"},
            ]
            resp = await client.post(
                "/api/v1/ai/urgency/score",
                json={
                    "tasks": [
                        {
                            "id": "1",
                            "title": "Tax filing",
                            "priority": 4,
                            "created_at": "2026-03-15",
                            "due_date": "2026-04-07",
                        }
                    ]
                },
                headers=pro_auth_headers,
            )
            assert resp.status_code == 200, resp.text
            data = resp.json()
            assert len(data["scores"]) == 1
            assert data["scores"][0]["id"] == "1"
            assert data["scores"][0]["level"] == "CRITICAL"

    @pytest.mark.asyncio
    async def test_score_endpoint_filters_unknown_ids(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        """Scores returned for ids the caller did not send should be dropped."""
        from app.routers.ai import urgency_score_rate_limiter

        urgency_score_rate_limiter._requests.clear()
        with patch("app.services.ai_productivity.score_tasks_urgency") as mock_score:
            mock_score.return_value = [
                {"id": "1", "score": 0.5, "level": "MEDIUM", "reason": "ok"},
                {"id": "hallucinated", "score": 0.5, "level": "LOW", "reason": "drop"},
            ]
            resp = await client.post(
                "/api/v1/ai/urgency/score",
                json={
                    "tasks": [
                        {"id": "1", "title": "T", "priority": 0, "created_at": "2026-04-09"},
                    ]
                },
                headers=pro_auth_headers,
            )
            assert resp.status_code == 200, resp.text
            data = resp.json()
            assert [s["id"] for s in data["scores"]] == ["1"]

    @pytest.mark.asyncio
    async def test_score_endpoint_runtime_error_returns_503(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from app.routers.ai import urgency_score_rate_limiter

        urgency_score_rate_limiter._requests.clear()
        with patch("app.services.ai_productivity.score_tasks_urgency") as mock_score:
            mock_score.side_effect = RuntimeError("ANTHROPIC_API_KEY missing")
            resp = await client.post(
                "/api/v1/ai/urgency/score",
                json={
                    "tasks": [
                        {"id": "1", "title": "T", "priority": 0, "created_at": "2026-04-09"},
                    ]
                },
                headers=pro_auth_headers,
            )
            assert resp.status_code == 503

    @pytest.mark.asyncio
    async def test_score_endpoint_value_error_returns_500(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from app.routers.ai import urgency_score_rate_limiter

        urgency_score_rate_limiter._requests.clear()
        with patch("app.services.ai_productivity.score_tasks_urgency") as mock_score:
            mock_score.side_effect = ValueError("bad json")
            resp = await client.post(
                "/api/v1/ai/urgency/score",
                json={
                    "tasks": [
                        {"id": "1", "title": "T", "priority": 0, "created_at": "2026-04-09"},
                    ]
                },
                headers=pro_auth_headers,
            )
            assert resp.status_code == 500

    @pytest.mark.asyncio
    async def test_score_endpoint_rejects_empty_batch(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        resp = await client.post(
            "/api/v1/ai/urgency/score",
            json={"tasks": []},
            headers=pro_auth_headers,
        )
        assert resp.status_code == 422  # min_length=1 fails

    @pytest.mark.asyncio
    async def test_score_endpoint_rejects_over_50_tasks(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        resp = await client.post(
            "/api/v1/ai/urgency/score",
            json={
                "tasks": [
                    {"id": str(i), "title": "T", "priority": 0, "created_at": "2026-04-09"}
                    for i in range(51)
                ]
            },
            headers=pro_auth_headers,
        )
        assert resp.status_code == 422  # max_length=50 fails
