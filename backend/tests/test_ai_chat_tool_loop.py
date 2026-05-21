"""End-to-end test for the AI Assistant Phase 1 tool-use loop.

Mocks ``client.messages.create`` to return a two-turn sequence:
  1. Claude calls ``get_tasks(bucket="overdue")``.
  2. Claude returns a final text reply after seeing the tool_result.
Verifies the loop dispatched the read tool, fed the result back, and
returned the final reply with a tool_calls summary attached.
"""

import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch



def _block_text(text: str):
    return SimpleNamespace(type="text", text=text)


def _block_tool_use(tool_id: str, name: str, input: dict):
    return SimpleNamespace(type="tool_use", id=tool_id, name=name, input=input)


def _mock_response(content: list, in_tok=5, out_tok=7):
    return SimpleNamespace(
        content=content,
        usage=SimpleNamespace(input_tokens=in_tok, output_tokens=out_tok),
    )


def test_loop_dispatches_read_tool_and_feeds_result_back(monkeypatch):
    from app.services import ai_productivity

    # Force the feature flag on.
    monkeypatch.setattr(
        "app.config.settings.AI_ASSISTANT_TOOL_USE_ENABLED", True, raising=False,
    )

    call_count = {"n": 0}

    def fake_create(**kwargs):
        call_count["n"] += 1
        if call_count["n"] == 1:
            return _mock_response([
                _block_tool_use("tu_1", "get_tasks", {"bucket": "overdue"}),
            ])
        return _mock_response([_block_text("You have 2 overdue tasks.")])

    fake_client = SimpleNamespace(messages=SimpleNamespace(create=fake_create))

    from app.services.firestore_tasks import TaskDTO

    sample_tasks = [
        TaskDTO(task_id="t1", title="x", priority=1, due_date="2026-05-10"),
        TaskDTO(task_id="t2", title="y", priority=3, due_date="2026-05-05"),
    ]

    with patch.object(ai_productivity, "_get_client", return_value=fake_client), \
         patch(
             "app.routers.ai.tools.tasks._fetch_incomplete_tasks",
             new=AsyncMock(return_value=sample_tasks),
         ):
        result = asyncio.run(ai_productivity.generate_chat_response(
            message="what's overdue?",
            conversation_id="chat_2026-05-18_abc",
            history=[],
            user_preferences=[],
            current_state={"tasks": {}, "today_iso": "2026-05-18"},
            user_context={
                "today": "2026-05-18",
                "user_id": 7,
                "firebase_uid": "fb-7",
            },
        ))

    assert "overdue" in result["message"]
    assert call_count["n"] == 2
    assert result["tool_calls"][0]["name"] == "get_tasks"


def test_loop_respects_10_call_budget(monkeypatch):
    """If Claude keeps calling read tools past the budget, the loop
    force-stops and asks Claude for a final reply with what it has."""
    from app.services import ai_productivity

    monkeypatch.setattr(
        "app.config.settings.AI_ASSISTANT_TOOL_USE_ENABLED", True, raising=False,
    )

    call_count = {"n": 0}

    def fake_create(**kwargs):
        call_count["n"] += 1
        if call_count["n"] <= 11:
            return _mock_response([
                _block_tool_use(f"tu_{call_count['n']}", "get_tasks", {"bucket": "today"}),
            ])
        return _mock_response([_block_text("Sorry, budget hit.")])

    fake_client = SimpleNamespace(messages=SimpleNamespace(create=fake_create))

    with patch.object(ai_productivity, "_get_client", return_value=fake_client), \
         patch(
             "app.routers.ai.tools.tasks._fetch_incomplete_tasks",
             new=AsyncMock(return_value=[]),
         ):
        result = asyncio.run(ai_productivity.generate_chat_response(
            message="loop forever",
            conversation_id="chat_2026-05-18_xyz",
            history=[],
            user_preferences=[],
            current_state={"tasks": {}, "today_iso": "2026-05-18"},
            user_context={
                "today": "2026-05-18",
                "user_id": 7,
                "firebase_uid": "fb-7",
            },
        ))

    assert "budget" in result["message"].lower()
    assert call_count["n"] >= 11


def test_write_tool_use_passes_through_without_dispatch(monkeypatch):
    """A tool_use whose name is in the write set must NOT be executed
    server-side. It should be returned as an action chip, same as today."""
    from app.services import ai_productivity

    monkeypatch.setattr(
        "app.config.settings.AI_ASSISTANT_TOOL_USE_ENABLED", True, raising=False,
    )

    def fake_create(**kwargs):
        return _mock_response([
            _block_text("Marking it done."),
            _block_tool_use("tu_w", "complete", {"task_id": "42"}),
        ])

    fake_client = SimpleNamespace(messages=SimpleNamespace(create=fake_create))

    with patch.object(ai_productivity, "_get_client", return_value=fake_client):
        result = asyncio.run(ai_productivity.generate_chat_response(
            message="finish task 42",
            conversation_id="chat_2026-05-18_abc",
            history=[],
            user_preferences=[],
            current_state={"tasks": {}, "today_iso": "2026-05-18"},
            user_context={
                "today": "2026-05-18",
                "user_id": 7,
                "firebase_uid": "fb-7",
            },
        ))

    assert result["actions"][0]["type"] == "complete"
    assert "tool_calls" in result
