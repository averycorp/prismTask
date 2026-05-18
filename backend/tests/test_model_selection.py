"""Tests for feature-based AI model selection.

Weekly planner, monthly review, and the AI Assistant chat use Sonnet;
all other AI features use Haiku.
"""

from unittest.mock import MagicMock, patch


def test_get_model_weekly_planner_returns_sonnet():
    from app.services.ai_productivity import get_model, MODEL_SONNET
    assert get_model("weekly_planner") == MODEL_SONNET


def test_get_model_monthly_review_returns_sonnet():
    from app.services.ai_productivity import get_model, MODEL_SONNET
    assert get_model("monthly_review") == MODEL_SONNET


def test_get_model_chat_returns_sonnet():
    from app.services.ai_productivity import get_model, MODEL_SONNET
    assert get_model("chat") == MODEL_SONNET


def test_get_model_eisenhower_returns_haiku():
    from app.services.ai_productivity import get_model, MODEL_HAIKU
    assert get_model("eisenhower") == MODEL_HAIKU


def test_get_model_pomodoro_returns_haiku():
    from app.services.ai_productivity import get_model, MODEL_HAIKU
    assert get_model("pomodoro") == MODEL_HAIKU


def test_get_model_nlp_returns_haiku():
    from app.services.ai_productivity import get_model, MODEL_HAIKU
    assert get_model("nlp") == MODEL_HAIKU


def test_get_model_daily_briefing_returns_haiku():
    from app.services.ai_productivity import get_model, MODEL_HAIKU
    assert get_model("daily_briefing") == MODEL_HAIKU


def test_get_model_unknown_feature_returns_haiku():
    from app.services.ai_productivity import get_model, MODEL_HAIKU
    assert get_model(None) == MODEL_HAIKU
    assert get_model("some_unknown_feature") == MODEL_HAIKU


def test_eisenhower_always_uses_haiku_regardless_of_tier():
    from datetime import date
    from app.services.ai_productivity import MODEL_HAIKU
    mock_client = MagicMock()
    mock_message = MagicMock()
    mock_message.content = [MagicMock(text='[{"task_id": 1, "quadrant": "Q1", "reason": "test"}]')]
    mock_client.messages.create.return_value = mock_message
    with patch("app.services.ai_productivity._get_client", return_value=mock_client):
        from app.services.ai_productivity import categorize_eisenhower
        result = categorize_eisenhower(
            [{"task_id": 1, "title": "Test task", "priority": 3}],
            date(2026, 4, 12), tier="PRO",
        )
    assert mock_client.messages.create.call_args.kwargs["model"] == MODEL_HAIKU
    assert len(result) == 1


def test_weekly_planner_uses_sonnet_for_pro_tier():
    from datetime import date
    from app.services.ai_productivity import MODEL_SONNET
    mock_client = MagicMock()
    mock_message = MagicMock()
    mock_message.content = [MagicMock(text='{"plan": {}, "unscheduled": [], "week_summary": "", "tips": []}')]
    mock_client.messages.create.return_value = mock_message
    with patch("app.services.ai_productivity._get_client", return_value=mock_client):
        from app.services.ai_productivity import generate_weekly_plan
        generate_weekly_plan(
            week_start=date(2026, 4, 13),
            week_end=date(2026, 4, 19),
            work_days=["Monday", "Tuesday"],
            focus_hours_per_day=4,
            prefer_front_loading=True,
            tasks=[],
            recurring_tasks=[],
            tier="PRO",
        )
    assert mock_client.messages.create.call_args.kwargs["model"] == MODEL_SONNET


def test_nlp_parse_uses_haiku():
    from datetime import date
    from app.services.ai_productivity import MODEL_HAIKU
    mock_client = MagicMock()
    mock_message = MagicMock()
    mock_message.content = [MagicMock(text='{"title": "Buy groceries", "confidence": 0.9}')]
    mock_client.messages.create.return_value = mock_message
    with patch("app.services.nlp_parser.anthropic") as mock_anthropic, \
         patch.dict("os.environ", {"ANTHROPIC_API_KEY": "test-key"}):
        mock_anthropic.Anthropic.return_value = mock_client
        from app.services.nlp_parser import parse_task_input
        result = parse_task_input("Buy groceries", [], date(2026, 4, 12), tier="PRO")
    assert result.title == "Buy groceries"
    assert mock_client.messages.create.call_args.kwargs["model"] == MODEL_HAIKU


def test_admin_override_promotes_haiku_feature_to_sonnet():
    from app.services.ai_productivity import (
        MODEL_HAIKU,
        MODEL_SONNET,
        admin_model_override,
        get_model,
    )
    assert get_model("eisenhower") == MODEL_HAIKU
    token = admin_model_override.set("sonnet")
    try:
        assert get_model("eisenhower") == MODEL_SONNET
        assert get_model("nlp") == MODEL_SONNET
        assert get_model("chat") == MODEL_SONNET
        assert get_model(None) == MODEL_SONNET
    finally:
        admin_model_override.reset(token)
    assert get_model("eisenhower") == MODEL_HAIKU


def test_admin_override_does_not_break_existing_sonnet_features():
    from app.services.ai_productivity import (
        MODEL_SONNET,
        admin_model_override,
        get_model,
    )
    token = admin_model_override.set("sonnet")
    try:
        assert get_model("weekly_planner") == MODEL_SONNET
        assert get_model("monthly_review") == MODEL_SONNET
    finally:
        admin_model_override.reset(token)


def test_admin_override_dependency_ignores_non_admin():
    import asyncio
    from unittest.mock import MagicMock

    from app.middleware.admin_model_override import (
        HEADER_NAME,
        apply_admin_model_override,
    )
    from app.services.ai_productivity import (
        MODEL_HAIKU,
        admin_model_override,
        get_model,
    )

    request = MagicMock()
    request.headers = {HEADER_NAME: "sonnet"}
    non_admin = MagicMock(is_admin=False)

    async def run() -> str:
        gen = apply_admin_model_override(request, non_admin)
        await gen.__anext__()
        try:
            return get_model("eisenhower")
        finally:
            try:
                await gen.__anext__()
            except StopAsyncIteration:
                pass

    assert asyncio.run(run()) == MODEL_HAIKU
    # contextvar should be untouched outside the generator scope
    assert admin_model_override.get() is None


def test_admin_override_dependency_applies_for_admin():
    import asyncio
    from unittest.mock import MagicMock

    from app.middleware.admin_model_override import (
        HEADER_NAME,
        apply_admin_model_override,
    )
    from app.services.ai_productivity import (
        MODEL_SONNET,
        admin_model_override,
        get_model,
    )

    request = MagicMock()
    request.headers = {HEADER_NAME: "sonnet"}
    admin = MagicMock(is_admin=True)

    async def run() -> str:
        gen = apply_admin_model_override(request, admin)
        await gen.__anext__()
        try:
            return get_model("eisenhower")
        finally:
            try:
                await gen.__anext__()
            except StopAsyncIteration:
                pass

    assert asyncio.run(run()) == MODEL_SONNET
    # cleaned up after the dependency exits
    assert admin_model_override.get() is None
