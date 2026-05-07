import json
import sys
import types
from datetime import date
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from httpx import AsyncClient


def _make_mock_response(data) -> MagicMock:
    content_block = MagicMock()
    content_block.text = json.dumps(data)
    message = MagicMock()
    message.content = [content_block]
    return message


def _fake_task_dto(task_id: str = "task-1", title: str = "Test task", **overrides):
    """Build a TaskDTO with sensible defaults for router-level tests."""
    from app.services.firestore_tasks import TaskDTO

    fields = {
        "task_id": task_id,
        "title": title,
        "description": None,
        "due_date": None,
        "due_time": None,
        "planned_date": None,
        "priority": 0,
        "project_id": None,
        "eisenhower_quadrant": None,
        "urgency_score": 0.0,
        "sort_order": 0,
        "is_recurring": False,
        "completed_at": None,
    }
    fields.update(overrides)
    return TaskDTO(**fields)


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


class TestEisenhowerService:
    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_categorize_eisenhower_success(self):
        from app.services.ai_productivity import categorize_eisenhower

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_response([
                {"task_id": 1, "quadrant": "Q1", "reason": "Due tomorrow"},
                {"task_id": 2, "quadrant": "Q2", "reason": "Long-term goal"},
            ])

            tasks = [
                {"task_id": 1, "title": "Fix bug", "due_date": "2026-04-11", "priority": 1},
                {"task_id": 2, "title": "Learn Kotlin", "due_date": None, "priority": 2},
            ]
            result = categorize_eisenhower(tasks, date(2026, 4, 10))

            assert len(result) == 2
            assert result[0]["quadrant"] == "Q1"
            assert result[1]["quadrant"] == "Q2"

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_categorize_eisenhower_malformed_retry(self):
        from app.services.ai_productivity import categorize_eisenhower

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client

            bad_response = MagicMock()
            bad_content = MagicMock()
            bad_content.text = "not valid json"
            bad_response.content = [bad_content]

            good_response = _make_mock_response([
                {"task_id": 1, "quadrant": "Q1", "reason": "Urgent"},
            ])

            mock_client.messages.create.side_effect = [bad_response, good_response]

            result = categorize_eisenhower(
                [{"task_id": 1, "title": "Test"}], date(2026, 4, 10)
            )
            assert result[0]["quadrant"] == "Q1"
            assert mock_client.messages.create.call_count == 2


class TestLifeCategoryClassifyText:
    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_classify_life_category_text_success(self):
        from app.services.ai_productivity import classify_life_category_text

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_response({
                "category": "HEALTH",
                "reason": "Doctor appointment",
            })

            result = classify_life_category_text(
                title="Doctor appointment",
                description=None,
            )

            assert result["category"] == "HEALTH"
            assert result["reason"] == "Doctor appointment"

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_classify_life_category_text_invalid_category_retries(self):
        from app.services.ai_productivity import classify_life_category_text

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client

            bad_response = _make_mock_response({"category": "FAMILY", "reason": "x"})
            good_response = _make_mock_response({"category": "PERSONAL", "reason": "Errand"})
            mock_client.messages.create.side_effect = [bad_response, good_response]

            result = classify_life_category_text(title="Pick up dry cleaning", description=None)
            assert result["category"] == "PERSONAL"
            assert mock_client.messages.create.call_count == 2

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_classify_life_category_text_both_fail_raises(self):
        from app.services.ai_productivity import classify_life_category_text

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            bad_response = MagicMock()
            bad_content = MagicMock()
            bad_content.text = "{{invalid}}"
            bad_response.content = [bad_content]
            mock_client.messages.create.return_value = bad_response

            with pytest.raises(ValueError, match="Failed to parse AI response"):
                classify_life_category_text(title="Foo", description=None)

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_classify_life_category_text_uncategorized_is_legal(self):
        from app.services.ai_productivity import classify_life_category_text

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_response({
                "category": "UNCATEGORIZED",
                "reason": "Title gives no signal",
            })

            result = classify_life_category_text(title="xyz", description=None)
            assert result["category"] == "UNCATEGORIZED"


class TestPomodoroService:
    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_plan_pomodoro_success(self):
        from app.services.ai_productivity import plan_pomodoro

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_response({
                "sessions": [
                    {
                        "session_number": 1,
                        "tasks": [{"task_id": 1, "title": "Write report", "allocated_minutes": 25}],
                        "rationale": "Most urgent task",
                    }
                ],
                "total_sessions": 1,
                "total_work_minutes": 25,
                "total_break_minutes": 0,
                "skipped_tasks": [],
            })

            tasks = [{"task_id": 1, "title": "Write report", "due_date": "2026-04-11"}]
            result = plan_pomodoro(
                tasks=tasks,
                available_minutes=60,
                session_length=25,
                break_length=5,
                long_break_length=15,
                focus_preference="balanced",
                today=date(2026, 4, 10),
            )

            assert result["total_sessions"] == 1
            assert len(result["sessions"]) == 1
            assert result["sessions"][0]["tasks"][0]["task_id"] == 1

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_plan_pomodoro_malformed_both_fail(self):
        from app.services.ai_productivity import plan_pomodoro

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client

            bad_response = MagicMock()
            bad_content = MagicMock()
            bad_content.text = "{{invalid}}"
            bad_response.content = [bad_content]
            mock_client.messages.create.return_value = bad_response

            with pytest.raises(ValueError, match="Failed to parse AI response"):
                plan_pomodoro(
                    tasks=[{"task_id": 1, "title": "Test"}],
                    available_minutes=60,
                    session_length=25,
                    break_length=5,
                    long_break_length=15,
                    focus_preference="balanced",
                    today=date(2026, 4, 10),
                )


class TestAIEndpoints:
    @pytest.mark.asyncio
    async def test_eisenhower_endpoint(self, client: AsyncClient, pro_auth_headers: dict):
        auth_headers = pro_auth_headers
        fake_tasks = [_fake_task_dto(task_id="abc123", title="Test task")]

        with patch("app.routers.ai.ai_rate_limiter"), \
             patch(
                 "app.routers.ai.fetch_incomplete_tasks",
                 new=AsyncMock(return_value=fake_tasks),
             ), \
             patch("app.services.ai_productivity.categorize_eisenhower") as mock_cat:
            mock_cat.return_value = [
                {"task_id": "abc123", "quadrant": "Q1", "reason": "Urgent task"},
            ]
            resp = await client.post(
                "/api/v1/ai/eisenhower",
                json={},
                headers=auth_headers,
            )
            assert resp.status_code == 200, resp.text
            data = resp.json()
            assert "categorizations" in data
            assert "summary" in data
            assert data["categorizations"][0]["task_id"] == "abc123"

    @pytest.mark.asyncio
    async def test_pomodoro_endpoint(self, client: AsyncClient, pro_auth_headers: dict):
        auth_headers = pro_auth_headers
        fake_tasks = [_fake_task_dto(task_id="pom-1", title="Focus task")]

        with patch("app.routers.ai.ai_rate_limiter"), \
             patch(
                 "app.routers.ai.fetch_incomplete_tasks",
                 new=AsyncMock(return_value=fake_tasks),
             ), \
             patch("app.services.ai_productivity.plan_pomodoro") as mock_plan:
            mock_plan.return_value = {
                "sessions": [
                    {
                        "session_number": 1,
                        "tasks": [{"task_id": "pom-1", "title": "Focus task", "allocated_minutes": 25}],
                        "rationale": "Only task available",
                    }
                ],
                "total_sessions": 1,
                "total_work_minutes": 25,
                "total_break_minutes": 0,
                "skipped_tasks": [],
            }
            resp = await client.post(
                "/api/v1/ai/pomodoro-plan",
                json={"available_minutes": 60},
                headers=auth_headers,
            )
            assert resp.status_code == 200, resp.text
            data = resp.json()
            assert data["total_sessions"] == 1

    @pytest.mark.asyncio
    async def test_rate_limiting(self, client: AsyncClient, pro_auth_headers: dict):
        auth_headers = pro_auth_headers
        from app.routers.ai import ai_rate_limiter
        ai_rate_limiter._requests.clear()

        with patch(
            "app.routers.ai.fetch_incomplete_tasks",
            new=AsyncMock(return_value=[]),
        ):
            # First call should work (empty-tasks short-circuit returns 200)
            resp = await client.post(
                "/api/v1/ai/eisenhower",
                json={},
                headers=auth_headers,
            )
            assert resp.status_code == 200, resp.text

            # Second call within window should be rate limited
            resp2 = await client.post(
                "/api/v1/ai/eisenhower",
                json={},
                headers=auth_headers,
            )
            assert resp2.status_code == 429


class TestDailyBriefingService:
    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_generate_daily_briefing_success(self):
        from app.services.ai_productivity import generate_daily_briefing

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_response({
                "greeting": "Good morning! Moderate day ahead with 5 tasks.",
                "top_priorities": [
                    {"task_id": 1, "title": "Fix bug", "reason": "Due today, high priority"},
                    {"task_id": 2, "title": "Write report", "reason": "Blocks other work"},
                    {"task_id": 3, "title": "Reply to emails", "reason": "Quick win"},
                ],
                "heads_up": ["2 overdue tasks from yesterday"],
                "suggested_order": [
                    {"task_id": 1, "title": "Fix bug", "suggested_time": "9:00 AM", "reason": "Hardest first"},
                    {"task_id": 2, "title": "Write report", "suggested_time": "10:30 AM", "reason": "Focus time"},
                ],
                "habit_reminders": ["Exercise", "Read"],
                "day_type": "moderate",
            })

            result = generate_daily_briefing(
                today=date(2026, 4, 10),
                overdue_tasks=[{"task_id": 4, "title": "Old task"}],
                today_tasks=[{"task_id": 1, "title": "Fix bug"}],
                planned_tasks=[{"task_id": 2, "title": "Write report"}],
                habits=[{"name": "Exercise", "frequency": "daily"}],
                completed_tasks=[{"task_id": 5, "title": "Done task"}],
            )

            assert result["day_type"] == "moderate"
            assert len(result["top_priorities"]) == 3
            assert result["top_priorities"][0]["task_id"] == 1
            assert len(result["suggested_order"]) == 2

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_generate_daily_briefing_malformed_retry(self):
        from app.services.ai_productivity import generate_daily_briefing

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client

            bad_response = MagicMock()
            bad_content = MagicMock()
            bad_content.text = "not json"
            bad_response.content = [bad_content]

            good_response = _make_mock_response({
                "greeting": "Good morning!",
                "top_priorities": [],
                "heads_up": [],
                "suggested_order": [],
                "habit_reminders": [],
                "day_type": "light",
            })

            mock_client.messages.create.side_effect = [bad_response, good_response]

            result = generate_daily_briefing(
                today=date(2026, 4, 10),
                overdue_tasks=[],
                today_tasks=[],
                planned_tasks=[],
                habits=[],
                completed_tasks=[],
            )
            assert result["day_type"] == "light"
            assert mock_client.messages.create.call_count == 2


class TestWeeklyPlanService:
    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_generate_weekly_plan_success(self):
        from app.services.ai_productivity import generate_weekly_plan

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_response({
                "plan": {
                    "Monday": {
                        "date": "2026-04-13",
                        "tasks": [
                            {"task_id": 1, "title": "Write report", "suggested_time": "9:00 AM",
                             "duration_minutes": 60, "reason": "Due Tuesday"},
                        ],
                        "total_hours": 1.0,
                        "calendar_events": [],
                        "habits": ["Exercise"],
                    },
                },
                "unscheduled": [
                    {"task_id": 5, "title": "Low priority", "reason": "Defer to next week"},
                ],
                "week_summary": "Light week with 3 tasks.",
                "tips": ["Focus on the report Monday morning"],
            })

            result = generate_weekly_plan(
                week_start=date(2026, 4, 13),
                week_end=date(2026, 4, 19),
                work_days=["MO", "TU", "WE", "TH", "FR"],
                focus_hours_per_day=6,
                prefer_front_loading=True,
                tasks=[{"task_id": 1, "title": "Write report", "due_date": "2026-04-14", "priority": 2}],
                recurring_tasks=[],
            )

            assert "Monday" in result["plan"]
            assert len(result["plan"]["Monday"]["tasks"]) == 1
            assert result["plan"]["Monday"]["tasks"][0]["task_id"] == 1
            assert len(result["unscheduled"]) == 1
            assert result["week_summary"] == "Light week with 3 tasks."

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_generate_weekly_plan_both_fail(self):
        from app.services.ai_productivity import generate_weekly_plan

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client

            bad_response = MagicMock()
            bad_content = MagicMock()
            bad_content.text = "{{not valid}}"
            bad_response.content = [bad_content]
            mock_client.messages.create.return_value = bad_response

            with pytest.raises(ValueError, match="Failed to parse AI response"):
                generate_weekly_plan(
                    week_start=date(2026, 4, 13),
                    week_end=date(2026, 4, 19),
                    work_days=["MO", "TU", "WE", "TH", "FR"],
                    focus_hours_per_day=6,
                    prefer_front_loading=True,
                    tasks=[{"task_id": 1, "title": "Test"}],
                    recurring_tasks=[],
                )


class TestTimeBlockService:
    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_generate_time_blocks_success(self):
        from app.services.ai_productivity import generate_time_blocks

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_response({
                "schedule": [
                    {"start": "09:00", "end": "09:30", "type": "task", "task_id": 1,
                     "title": "Write report", "reason": "Deep work while fresh"},
                    {"start": "09:30", "end": "10:00", "type": "event", "task_id": None,
                     "title": "Team standup", "reason": "Fixed calendar event"},
                    {"start": "10:00", "end": "10:15", "type": "break", "task_id": None,
                     "title": "Break", "reason": "Recovery after work block"},
                ],
                "unscheduled_tasks": [
                    {"task_id": 3, "title": "Low priority task", "reason": "Not enough time"},
                ],
                "stats": {
                    "total_work_minutes": 30,
                    "total_break_minutes": 15,
                    "total_free_minutes": 495,
                    "tasks_scheduled": 1,
                    "tasks_deferred": 1,
                },
            })

            result = generate_time_blocks(
                target_date=date(2026, 4, 10),
                day_start="09:00",
                day_end="18:00",
                block_size_minutes=30,
                include_breaks=True,
                break_frequency_minutes=90,
                break_duration_minutes=15,
                tasks=[{"task_id": 1, "title": "Write report"}],
                calendar_events=[{"title": "Team standup", "start": "09:30", "end": "10:00"}],
            )

            assert len(result["schedule"]) == 3
            assert result["schedule"][0]["type"] == "task"
            assert result["schedule"][1]["type"] == "event"
            assert result["schedule"][2]["type"] == "break"
            assert result["stats"]["tasks_scheduled"] == 1

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_generate_time_blocks_preserves_calendar_events(self):
        from app.services.ai_productivity import generate_time_blocks

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_response({
                "schedule": [
                    {"start": "09:00", "end": "09:30", "type": "task", "task_id": 1,
                     "title": "Task before meeting", "reason": "Morning slot"},
                    {"start": "10:00", "end": "11:00", "type": "event", "task_id": None,
                     "title": "Team meeting", "reason": "Fixed calendar event"},
                    {"start": "11:00", "end": "11:30", "type": "task", "task_id": 2,
                     "title": "Task after meeting", "reason": "After meeting slot"},
                ],
                "unscheduled_tasks": [],
                "stats": {
                    "total_work_minutes": 60,
                    "total_break_minutes": 0,
                    "total_free_minutes": 480,
                    "tasks_scheduled": 2,
                    "tasks_deferred": 0,
                },
            })

            result = generate_time_blocks(
                target_date=date(2026, 4, 10),
                day_start="09:00",
                day_end="18:00",
                block_size_minutes=30,
                include_breaks=False,
                break_frequency_minutes=90,
                break_duration_minutes=15,
                tasks=[
                    {"task_id": 1, "title": "Task before meeting"},
                    {"task_id": 2, "title": "Task after meeting"},
                ],
                calendar_events=[{"title": "Team meeting", "start": "10:00", "end": "11:00"}],
            )

            # Verify calendar event is in the schedule
            event_blocks = [b for b in result["schedule"] if b["type"] == "event"]
            assert len(event_blocks) == 1
            assert event_blocks[0]["title"] == "Team meeting"
            assert result["stats"]["tasks_scheduled"] == 2

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_generate_time_blocks_single_day_backfills_date(self):
        """v1.4.40: for horizon_days=1, every returned block must carry a
        ``date`` field. If Haiku omits it (legacy prompt shape), the service
        backfills ``target_date`` so the client can rely on the contract.
        """
        from app.services.ai_productivity import generate_time_blocks

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            # Deliberately omit "date" on the block — Haiku sometimes does
            # this when the single-day prompt has the date in the header.
            mock_client.messages.create.return_value = _make_mock_response({
                "schedule": [
                    {"start": "09:00", "end": "09:30", "type": "task",
                     "task_id": 1, "title": "Write report",
                     "reason": "Deep work"},
                ],
                "unscheduled_tasks": [],
                "stats": {
                    "total_work_minutes": 30, "total_break_minutes": 0,
                    "total_free_minutes": 510, "tasks_scheduled": 1,
                    "tasks_deferred": 0,
                },
            })

            result = generate_time_blocks(
                target_date=date(2026, 4, 22),
                day_start="09:00",
                day_end="18:00",
                block_size_minutes=30,
                include_breaks=False,
                break_frequency_minutes=90,
                break_duration_minutes=15,
                tasks=[{"task_id": 1, "title": "Write report"}],
                calendar_events=[],
                horizon_days=1,
            )

            assert result["schedule"][0]["date"] == "2026-04-22"

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_generate_time_blocks_horizon_7_prompts_for_week(self):
        """v1.4.40: horizon_days > 1 emits a horizon header, forbids piling
        all work on day 1, and forwards per-task signals / existing blocks
        to the Haiku prompt verbatim.
        """
        from app.services.ai_productivity import generate_time_blocks

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_response({
                "schedule": [
                    {"start": "09:00", "end": "10:00", "type": "task",
                     "task_id": 1, "title": "Design review",
                     "reason": "Q2 deep work Monday", "date": "2026-04-22"},
                    {"start": "14:00", "end": "15:00", "type": "task",
                     "task_id": 2, "title": "Code review",
                     "reason": "Later in week", "date": "2026-04-24"},
                ],
                "unscheduled_tasks": [],
                "stats": {
                    "total_work_minutes": 120, "total_break_minutes": 0,
                    "total_free_minutes": 600, "tasks_scheduled": 2,
                    "tasks_deferred": 0,
                },
            })

            result = generate_time_blocks(
                target_date=date(2026, 4, 22),
                day_start="09:00",
                day_end="18:00",
                block_size_minutes=30,
                include_breaks=False,
                break_frequency_minutes=90,
                break_duration_minutes=15,
                tasks=[
                    {"task_id": 1, "title": "Design review"},
                    {"task_id": 2, "title": "Code review"},
                ],
                calendar_events=[],
                horizon_days=7,
                task_signals=[
                    {"task_id": "1", "eisenhower_quadrant": "Q2",
                     "estimated_pomodoro_sessions": 2},
                    {"task_id": "2", "eisenhower_quadrant": "Q3",
                     "estimated_duration_minutes": 45},
                ],
                existing_blocks=[
                    {"date": "2026-04-23", "start": "10:00", "end": "11:00",
                     "title": "Dentist", "source": "task", "task_id": "99"},
                ],
            )

            # Every block carries a date covering the horizon.
            assert len(result["schedule"]) == 2
            assert {b["date"] for b in result["schedule"]} == {
                "2026-04-22", "2026-04-24",
            }

            prompt = mock_client.messages.create.call_args.kwargs["messages"][0]["content"]
            # Horizon header present and spans 7 days.
            assert "Horizon window: 2026-04-22" in prompt
            assert "through 2026-04-28" in prompt
            assert "7 days total" in prompt
            # Per-task signals forwarded.
            assert "\"eisenhower_quadrant\": \"Q2\"" in prompt
            assert "\"estimated_pomodoro_sessions\": 2" in prompt
            # Existing blocks forwarded as hard constraints.
            assert "HARD CONSTRAINTS" in prompt
            assert "Dentist" in prompt
            # Haiku is told NOT to pile everything on day 1.
            assert "do not pile everything on day 1" in prompt

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_generate_time_blocks_all_tasks_conflict(self):
        """When every candidate task collides with an existing block, Haiku
        is expected to return them in unscheduled_tasks rather than
        overwriting the block — verify the service just forwards that
        shape untouched.
        """
        from app.services.ai_productivity import generate_time_blocks

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_response({
                "schedule": [],
                "unscheduled_tasks": [
                    {"task_id": "1", "title": "Blocked task A",
                     "reason": "Existing block occupies this slot"},
                    {"task_id": "2", "title": "Blocked task B",
                     "reason": "Existing block occupies this slot"},
                ],
                "stats": {
                    "total_work_minutes": 0, "total_break_minutes": 0,
                    "total_free_minutes": 0, "tasks_scheduled": 0,
                    "tasks_deferred": 2,
                },
            })

            result = generate_time_blocks(
                target_date=date(2026, 4, 22),
                day_start="09:00",
                day_end="10:00",  # Deliberately tiny window.
                block_size_minutes=30,
                include_breaks=False,
                break_frequency_minutes=90,
                break_duration_minutes=15,
                tasks=[
                    {"task_id": 1, "title": "Blocked task A"},
                    {"task_id": 2, "title": "Blocked task B"},
                ],
                calendar_events=[],
                horizon_days=1,
                existing_blocks=[
                    {"date": "2026-04-22", "start": "09:00", "end": "10:00",
                     "title": "Standing meeting", "source": "task",
                     "task_id": "77"},
                ],
            )
            assert result["schedule"] == []
            assert len(result["unscheduled_tasks"]) == 2
            assert result["stats"]["tasks_deferred"] == 2


class TestTimeBlockRequestSchema:
    """Malformed time-block requests are rejected at the Pydantic boundary."""

    @pytest.mark.asyncio
    async def test_malformed_horizon_days_rejected(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from app.routers.ai import time_block_rate_limiter
        time_block_rate_limiter._requests.clear()

        # horizon_days=0 is below the ge=1 bound.
        resp = await client.post(
            "/api/v1/ai/time-block",
            json={"horizon_days": 0},
            headers=pro_auth_headers,
        )
        assert resp.status_code == 422

    @pytest.mark.asyncio
    async def test_malformed_existing_block_source_rejected(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from app.routers.ai import time_block_rate_limiter
        time_block_rate_limiter._requests.clear()

        resp = await client.post(
            "/api/v1/ai/time-block",
            json={
                "existing_blocks": [
                    {"date": "2026-04-22", "start": "09:00", "end": "10:00",
                     "title": "??", "source": "gcal"},  # invalid source
                ],
            },
            headers=pro_auth_headers,
        )
        assert resp.status_code == 422


class TestTimeBlockRouterHorizon:
    """End-to-end: horizon_days param flows through the router to the response."""

    @pytest.mark.asyncio
    async def test_router_returns_proposed_and_horizon_days(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        from app.routers.ai import time_block_rate_limiter
        time_block_rate_limiter._requests.clear()

        task_dto = _fake_task_dto(
            task_id="t1", title="Plan project",
            due_date="2026-04-23",
            due_date_obj=date(2026, 4, 23),
            priority=3,
            eisenhower_quadrant="Q2",
        )

        ai_response = {
            "schedule": [
                {"start": "09:00", "end": "10:00", "type": "task",
                 "task_id": "t1", "title": "Plan project",
                 "reason": "Q2 morning slot", "date": "2026-04-22"},
            ],
            "unscheduled_tasks": [],
            "stats": {
                "total_work_minutes": 60, "total_break_minutes": 0,
                "total_free_minutes": 480, "tasks_scheduled": 1,
                "tasks_deferred": 0,
            },
        }

        with patch(
            "app.routers.ai.fetch_incomplete_tasks",
            new=AsyncMock(return_value=[task_dto]),
        ), patch(
            "app.services.ai_productivity.generate_time_blocks",
            return_value=ai_response,
        ) as mock_svc:
            resp = await client.post(
                "/api/v1/ai/time-block",
                json={
                    "date": "2026-04-22",
                    "horizon_days": 2,
                    "task_signals": [
                        {"task_id": "t1", "eisenhower_quadrant": "Q2",
                         "estimated_pomodoro_sessions": 2,
                         "pomodoro_source": "estimated_from_duration"},
                    ],
                    "existing_blocks": [
                        {"date": "2026-04-22", "start": "13:00",
                         "end": "14:00", "title": "Lunch",
                         "source": "pomodoro"},
                    ],
                },
                headers=pro_auth_headers,
            )

        assert resp.status_code == 200, resp.text
        body = resp.json()
        assert body["horizon_days"] == 2
        assert body["proposed"] is True
        assert body["schedule"][0]["date"] == "2026-04-22"

        # Service was called with the horizon + signals plumbed through.
        call_kwargs = mock_svc.call_args.kwargs
        assert call_kwargs["horizon_days"] == 2
        assert call_kwargs["task_signals"][0]["task_id"] == "t1"
        assert call_kwargs["existing_blocks"][0]["source"] == "pomodoro"


class TestNewAIEndpoints:
    @pytest.mark.asyncio
    async def test_daily_briefing_rate_limiting(self, client: AsyncClient, pro_auth_headers: dict):
        auth_headers = pro_auth_headers
        from app.routers.ai import briefing_rate_limiter
        briefing_rate_limiter._requests.clear()

        with patch(
            "app.routers.ai.fetch_incomplete_tasks",
            new=AsyncMock(return_value=[]),
        ), patch(
            "app.routers.ai.fetch_recently_completed_tasks",
            new=AsyncMock(return_value=[]),
        ):
            # First call should succeed (empty briefing)
            resp = await client.post(
                "/api/v1/ai/daily-briefing",
                json={},
                headers=auth_headers,
            )
            assert resp.status_code == 200, resp.text

            # Second call within 1-hour window should be rate limited
            resp2 = await client.post(
                "/api/v1/ai/daily-briefing",
                json={},
                headers=auth_headers,
            )
            assert resp2.status_code == 429

    @pytest.mark.asyncio
    async def test_weekly_plan_rate_limiting(self, client: AsyncClient, pro_auth_headers: dict):
        auth_headers = pro_auth_headers
        from app.routers.ai import weekly_plan_rate_limiter
        weekly_plan_rate_limiter._requests.clear()

        with patch(
            "app.routers.ai.fetch_incomplete_tasks",
            new=AsyncMock(return_value=[]),
        ):
            # First call should succeed (empty plan)
            resp = await client.post(
                "/api/v1/ai/weekly-plan",
                json={},
                headers=auth_headers,
            )
            assert resp.status_code == 200, resp.text

            # Second call within 30-min window should be rate limited
            resp2 = await client.post(
                "/api/v1/ai/weekly-plan",
                json={},
                headers=auth_headers,
            )
            assert resp2.status_code == 429

    @pytest.mark.asyncio
    async def test_time_block_rate_limiting(self, client: AsyncClient, pro_auth_headers: dict):
        auth_headers = pro_auth_headers
        from app.routers.ai import time_block_rate_limiter
        time_block_rate_limiter._requests.clear()
        # v1.4.40: rate limit is now 10/hour (was 1 per 15 min). Verify the
        # new config by sending 10 successful requests, then expecting 429.
        with patch(
            "app.routers.ai.fetch_incomplete_tasks",
            new=AsyncMock(return_value=[]),
        ):
            for i in range(10):
                resp = await client.post(
                    "/api/v1/ai/time-block",
                    json={},
                    headers=auth_headers,
                )
                assert resp.status_code == 200, f"request #{i + 1}: {resp.text}"

            resp11 = await client.post(
                "/api/v1/ai/time-block",
                json={},
                headers=auth_headers,
            )
            assert resp11.status_code == 429


class TestWeeklyReviewService:
    """The prompt assembly is the only non-trivial logic here — verify that
    all four input sections land in the Sonnet prompt."""

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_prompt_includes_all_four_sections(self):
        from app.services.ai_productivity import generate_weekly_review

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_response({
                "wins": ["Shipped the payment fix"],
                "slips": ["Dishes never got done"],
                "patterns": ["Q2 work slipped"],
                "next_week_focus": ["Block Tuesday AM for the migration"],
                "narrative": "Solid week, with one recurring slip pattern.",
            })

            completed = [
                {"task_id": "c1", "title": "Ship payment fix", "priority": 3,
                 "eisenhower_quadrant": "Q1", "life_category": "work",
                 "completed_at": "2026-04-15T10:00:00"},
            ]
            slipped = [
                {"task_id": "s1", "title": "Do the dishes", "priority": 1,
                 "life_category": "personal"},
            ]
            open_tasks = [
                {"task_id": "o1", "title": "Finish DB migration", "priority": 4,
                 "eisenhower_quadrant": "Q2", "due_date": "2026-04-22"},
            ]

            result = generate_weekly_review(
                week_start="2026-04-13",
                week_end="2026-04-19",
                completed_tasks=completed,
                slipped_tasks=slipped,
                open_tasks=open_tasks,
                habit_summary={"exercise_streak": 4, "meditation_rate": 0.71},
                pomodoro_summary={"total_minutes": 480, "sessions": 12},
                notes="Felt scattered on Wednesday but recovered.",
                tier="PRO",
            )

            assert result["narrative"].startswith("Solid week")
            assert result["patterns"] == ["Q2 work slipped"]
            assert result["next_week_focus"] == ["Block Tuesday AM for the migration"]

            # Inspect the prompt Sonnet received.
            call_args = mock_client.messages.create.call_args
            prompt = call_args.kwargs["messages"][0]["content"]

            # Section 1: week bounds
            assert "2026-04-13 to 2026-04-19" in prompt

            # Section 2: completed tasks (header with count + the task title)
            assert "completed 1 task" in prompt
            assert "Ship payment fix" in prompt
            assert "Q1" in prompt  # quadrant metadata

            # Section 3: slipped tasks
            assert "1 task(s) that slipped" in prompt
            assert "Do the dishes" in prompt

            # Section 4: open tasks
            assert "Currently open on their plate" in prompt
            assert "Finish DB migration" in prompt
            assert "due 2026-04-22" in prompt

            # Opaque pass-through blocks
            assert "exercise_streak" in prompt
            assert "total_minutes" in prompt

            # User notes
            assert "Felt scattered on Wednesday" in prompt

            # Sonnet, not Haiku (weekly review runs on monthly_review model).
            from app.services.ai_productivity import MODEL_SONNET
            assert call_args.kwargs["model"] == MODEL_SONNET

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_prompt_handles_empty_inputs_gracefully(self):
        """A week with no completed/slipped/open tasks and no summaries
        should still produce a valid prompt (no KeyError, no crash)."""
        from app.services.ai_productivity import generate_weekly_review

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_mock_response({
                "wins": [],
                "slips": [],
                "patterns": [],
                "next_week_focus": [],
                "narrative": "A quiet week with nothing logged.",
            })

            generate_weekly_review(
                week_start="2026-04-13",
                week_end="2026-04-19",
                completed_tasks=[],
                slipped_tasks=[],
                open_tasks=[],
                habit_summary=None,
                pomodoro_summary=None,
                notes=None,
                tier="PRO",
            )

            prompt = mock_client.messages.create.call_args.kwargs["messages"][0]["content"]
            # Empty sections render as "(none)" / "(not provided)" so the
            # model doesn't see dangling headers.
            assert "completed 0 task" in prompt
            assert "0 task(s) that slipped" in prompt
            assert "(none)" in prompt
            assert "(not provided)" in prompt


# ----------------------------------------------------------------------------
# A2 Pomodoro+ AI Coaching (pre-session / break-activity / session-recap)
# ----------------------------------------------------------------------------


def _make_text_mock_response(text: str) -> MagicMock:
    """Mock a Haiku response that isn't JSON — coaching surfaces return raw text."""
    content_block = MagicMock()
    content_block.text = text
    message = MagicMock()
    message.content = [content_block]
    return message


class TestPomodoroCoachingService:
    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_pre_session_prompt_includes_tasks(self):
        from app.services.ai_productivity import generate_pomodoro_coaching

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_text_mock_response(
                "Start with the report draft — it's the most urgent."
            )
            result = generate_pomodoro_coaching(
                trigger="pre_session",
                upcoming_tasks=[{"task_id": "1", "title": "Write report", "allocated_minutes": 25}],
                session_length_minutes=25,
            )
            assert result == "Start with the report draft — it's the most urgent."
            prompt = mock_client.messages.create.call_args.kwargs["messages"][0]["content"]
            assert "25-minute" in prompt
            assert "Write report" in prompt

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_pre_session_strips_quotes_and_whitespace(self):
        from app.services.ai_productivity import generate_pomodoro_coaching

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            # Haiku sometimes wraps the response in quotes / adds trailing newlines.
            mock_client.messages.create.return_value = _make_text_mock_response(
                '  "Begin with the draft."  \n'
            )
            result = generate_pomodoro_coaching(
                trigger="pre_session",
                upcoming_tasks=[{"title": "Draft"}],
                session_length_minutes=25,
            )
            assert result == "Begin with the draft."

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_break_activity_prompt_includes_recent_suggestions(self):
        from app.services.ai_productivity import generate_pomodoro_coaching

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_text_mock_response(
                "Roll your shoulders back a few times."
            )
            result = generate_pomodoro_coaching(
                trigger="break_activity",
                elapsed_minutes=50,
                break_type="short",
                recent_suggestions=["Drink water.", "Eye rest."],
            )
            assert "Roll your shoulders" in result
            prompt = mock_client.messages.create.call_args.kwargs["messages"][0]["content"]
            # Recent suggestions must appear in the prompt so Haiku varies.
            assert "Drink water." in prompt
            assert "Eye rest." in prompt
            assert "short" in prompt

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_session_recap_prompt_partitions_tasks(self):
        from app.services.ai_productivity import generate_pomodoro_coaching

        with patch("app.services.ai_productivity.anthropic") as mock_anthropic:
            mock_client = MagicMock()
            mock_anthropic.Anthropic.return_value = mock_client
            mock_client.messages.create.return_value = _make_text_mock_response(
                "Great job shipping the draft. Next up: tighten the intro."
            )
            result = generate_pomodoro_coaching(
                trigger="session_recap",
                completed_tasks=[{"title": "Draft"}],
                started_tasks=[{"title": "Review"}],
                session_duration_minutes=50,
            )
            assert "Great job" in result
            prompt = mock_client.messages.create.call_args.kwargs["messages"][0]["content"]
            assert "50-minute" in prompt
            assert "Draft" in prompt
            assert "Review" in prompt

    @patch.dict("os.environ", {"ANTHROPIC_API_KEY": "sk-test-key"})
    def test_unknown_trigger_raises_value_error(self):
        from app.services.ai_productivity import generate_pomodoro_coaching

        with patch("app.services.ai_productivity.anthropic"):
            with pytest.raises(ValueError, match="Unknown Pomodoro coaching trigger"):
                generate_pomodoro_coaching(trigger="invented_surface")

    def test_missing_api_key_raises_runtime_error(self):
        from app.services.ai_productivity import generate_pomodoro_coaching

        with patch.dict("os.environ", {}, clear=True), \
             patch("app.services.ai_productivity.settings") as mock_settings:
            mock_settings.ANTHROPIC_API_KEY = None
            with pytest.raises(RuntimeError, match="ANTHROPIC_API_KEY"):
                generate_pomodoro_coaching(
                    trigger="pre_session",
                    upcoming_tasks=[],
                    session_length_minutes=25,
                )


class TestPomodoroCoachingEndpoint:
    @pytest.mark.asyncio
    async def test_pre_session_happy_path(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        with patch("app.routers.ai.pomodoro_coaching_rate_limiter"), \
             patch("app.services.ai_productivity.generate_pomodoro_coaching") as mock_gen:
            mock_gen.return_value = "Start with the draft."
            resp = await client.post(
                "/api/v1/ai/pomodoro-coaching",
                json={
                    "trigger": "pre_session",
                    "upcoming_tasks": [
                        {"task_id": "1", "title": "Write report", "allocated_minutes": 25}
                    ],
                    "session_length_minutes": 25,
                },
                headers=pro_auth_headers,
            )
            assert resp.status_code == 200, resp.text
            assert resp.json() == {"message": "Start with the draft."}
            mock_gen.assert_called_once()
            kwargs = mock_gen.call_args.kwargs
            assert kwargs["trigger"] == "pre_session"
            assert kwargs["session_length_minutes"] == 25

    @pytest.mark.asyncio
    async def test_break_activity_forwards_all_fields(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        with patch("app.routers.ai.pomodoro_coaching_rate_limiter"), \
             patch("app.services.ai_productivity.generate_pomodoro_coaching") as mock_gen:
            mock_gen.return_value = "Take a 2-minute walk."
            resp = await client.post(
                "/api/v1/ai/pomodoro-coaching",
                json={
                    "trigger": "break_activity",
                    "elapsed_minutes": 50,
                    "break_type": "short",
                    "recent_suggestions": ["Drink water."],
                },
                headers=pro_auth_headers,
            )
            assert resp.status_code == 200, resp.text
            kwargs = mock_gen.call_args.kwargs
            assert kwargs["trigger"] == "break_activity"
            assert kwargs["elapsed_minutes"] == 50
            assert kwargs["break_type"] == "short"
            assert kwargs["recent_suggestions"] == ["Drink water."]

    @pytest.mark.asyncio
    async def test_invalid_trigger_rejected_by_schema(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        with patch("app.routers.ai.pomodoro_coaching_rate_limiter"):
            resp = await client.post(
                "/api/v1/ai/pomodoro-coaching",
                json={"trigger": "not_a_real_surface"},
                headers=pro_auth_headers,
            )
            # Pydantic rejects the regex-constrained trigger → 422.
            assert resp.status_code == 422

    @pytest.mark.asyncio
    async def test_invalid_break_type_rejected(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        with patch("app.routers.ai.pomodoro_coaching_rate_limiter"):
            resp = await client.post(
                "/api/v1/ai/pomodoro-coaching",
                json={"trigger": "break_activity", "break_type": "medium"},
                headers=pro_auth_headers,
            )
            assert resp.status_code == 422

    @pytest.mark.asyncio
    async def test_service_runtime_error_becomes_503(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        with patch("app.routers.ai.pomodoro_coaching_rate_limiter"), \
             patch("app.services.ai_productivity.generate_pomodoro_coaching") as mock_gen:
            mock_gen.side_effect = RuntimeError("ANTHROPIC_API_KEY missing")
            resp = await client.post(
                "/api/v1/ai/pomodoro-coaching",
                json={"trigger": "pre_session", "session_length_minutes": 25},
                headers=pro_auth_headers,
            )
            assert resp.status_code == 503

    @pytest.mark.asyncio
    async def test_service_value_error_becomes_500(
        self, client: AsyncClient, pro_auth_headers: dict
    ):
        with patch("app.routers.ai.pomodoro_coaching_rate_limiter"), \
             patch("app.services.ai_productivity.generate_pomodoro_coaching") as mock_gen:
            mock_gen.side_effect = ValueError("bad response shape")
            resp = await client.post(
                "/api/v1/ai/pomodoro-coaching",
                json={"trigger": "session_recap", "session_duration_minutes": 50},
                headers=pro_auth_headers,
            )
            assert resp.status_code == 500
