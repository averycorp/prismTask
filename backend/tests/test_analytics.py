import sys
import types
from datetime import date, timedelta
from unittest.mock import MagicMock, patch

import pytest
from httpx import AsyncClient

from app.services.analytics import determine_trend


# --- Helper to set up test data ---


async def _create_project(client: AsyncClient, headers: dict, title: str = "Test Project") -> int:
    """Create a goal + project and return the project_id."""
    goal_resp = await client.post(
        "/api/v1/goals",
        json={"title": "Test Goal"},
        headers=headers,
    )
    goal_id = goal_resp.json()["id"]
    proj_resp = await client.post(
        f"/api/v1/goals/{goal_id}/projects",
        json={"title": title},
        headers=headers,
    )
    return proj_resp.json()["id"]


async def _create_task(
    client: AsyncClient,
    headers: dict,
    project_id: int,
    title: str = "Task",
    due_date: str | None = None,
    status: str = "todo",
    priority: int = 3,
) -> int:
    """Create a task and return its id."""
    payload: dict = {"title": title, "priority": priority}
    if due_date:
        payload["due_date"] = due_date
    resp = await client.post(
        f"/api/v1/projects/{project_id}/tasks",
        json=payload,
        headers=headers,
    )
    task_id = resp.json()["id"]

    if status != "todo":
        await client.patch(
            f"/api/v1/tasks/{task_id}",
            json={"status": status},
            headers=headers,
        )
    return task_id


async def _create_habit(client: AsyncClient, headers: dict, name: str = "Exercise") -> int:
    """Create a habit and return its id."""
    resp = await client.post(
        "/api/v1/habits",
        json={"name": name, "frequency": "daily"},
        headers=headers,
    )
    return resp.json()["id"]


async def _complete_habit(client: AsyncClient, headers: dict, habit_id: int, date_str: str):
    """Record a habit completion for a specific date."""
    await client.post(
        f"/api/v1/habits/{habit_id}/complete",
        json={"date": date_str},
        headers=headers,
    )


# --- Tests ---


def test_determine_trend():
    """Test determine_trend function."""
    # List length < 2
    assert determine_trend([]) == "stable"
    assert determine_trend([{"score": 10}]) == "stable"

    # Improving trend (second half > first half by > 3)
    # first half: 10, second half: 14. Diff is 4 (>3).
    assert determine_trend([{"score": 10}, {"score": 14}]) == "improving"
    # first half: 10, 10 (avg 10), second half: 14, 14 (avg 14). Diff is 4.
    assert determine_trend([{"score": 10}, {"score": 10}, {"score": 14}, {"score": 14}]) == "improving"

    # Declining trend (second half < first half by < -3)
    # first half: 14, second half: 10. Diff is -4 (<-3).
    assert determine_trend([{"score": 14}, {"score": 10}]) == "declining"
    assert determine_trend([{"score": 14}, {"score": 14}, {"score": 10}, {"score": 10}]) == "declining"

    # Stable trend (diff between -3 and 3)
    # first half: 10, second half: 12. Diff is 2.
    assert determine_trend([{"score": 10}, {"score": 12}]) == "stable"
    # first half: 10, second half: 7. Diff is -3.
    assert determine_trend([{"score": 10}, {"score": 7}]) == "stable"
    # first half: 10, second half: 13. Diff is 3.
    assert determine_trend([{"score": 10}, {"score": 13}]) == "stable"


class TestProductivityScore:
    @pytest.mark.asyncio
    async def test_productivity_score_with_completed_tasks(self, client: AsyncClient, auth_headers: dict):
        """Test that completed tasks produce a non-zero score."""
        today = date.today().isoformat()
        project_id = await _create_project(client, auth_headers)

        # Create tasks due today — complete some
        await _create_task(client, auth_headers, project_id, "Task 1", due_date=today, status="done")
        await _create_task(client, auth_headers, project_id, "Task 2", due_date=today, status="done")
        await _create_task(client, auth_headers, project_id, "Task 3", due_date=today, status="todo")

        resp = await client.get(
            "/api/v1/analytics/productivity-score",
            params={"start_date": today, "end_date": today, "period": "daily"},
            headers=auth_headers,
        )
        assert resp.status_code == 200
        data = resp.json()
        assert len(data["scores"]) == 1
        assert data["scores"][0]["score"] > 0
        assert "breakdown" in data["scores"][0]
        # 2/3 tasks completed = ~66.7% task_completion
        assert data["scores"][0]["breakdown"]["task_completion"] == pytest.approx(66.7, abs=0.1)
        assert data["average_score"] > 0
        assert data["trend"] in ("improving", "declining", "stable")
        assert data["best_day"] is not None
        assert data["worst_day"] is not None

    @pytest.mark.asyncio
    async def test_productivity_score_empty_returns_defaults(self, client: AsyncClient, auth_headers: dict):
        """Test that no data still returns a valid response with 100% defaults."""
        today = date.today().isoformat()
        resp = await client.get(
            "/api/v1/analytics/productivity-score",
            params={"start_date": today, "end_date": today},
            headers=auth_headers,
        )
        assert resp.status_code == 200
        data = resp.json()
        assert len(data["scores"]) == 1
        # No due tasks means 100% by default for all components
        assert data["scores"][0]["score"] == 100.0


class TestTimeTracking:
    @pytest.mark.asyncio
    async def test_time_tracking_by_project(self, client: AsyncClient, auth_headers: dict):
        """Test time tracking aggregation grouped by project."""
        today = date.today().isoformat()
        project_id = await _create_project(client, auth_headers, "Work Project")

        # Create completed tasks with actual_duration
        task_id = await _create_task(client, auth_headers, project_id, "Tracked Task 1", due_date=today, status="done")

        # Patch task to add duration data directly via the API
        await client.patch(
            f"/api/v1/tasks/{task_id}",
            json={"status": "done"},
            headers=auth_headers,
        )

        resp = await client.get(
            "/api/v1/analytics/time-tracking",
            params={"start_date": today, "end_date": today, "group_by": "project"},
            headers=auth_headers,
        )
        assert resp.status_code == 200
        data = resp.json()
        assert "entries" in data
        assert "total_tracked_minutes" in data
        assert "total_estimated_minutes" in data
        assert "overall_accuracy_pct" in data

    @pytest.mark.asyncio
    async def test_time_tracking_by_day(self, client: AsyncClient, auth_headers: dict):
        """Test time tracking aggregation grouped by day."""
        today = date.today().isoformat()

        resp = await client.get(
            "/api/v1/analytics/time-tracking",
            params={"start_date": today, "end_date": today, "group_by": "day"},
            headers=auth_headers,
        )
        assert resp.status_code == 200
        data = resp.json()
        assert isinstance(data["entries"], list)
        assert data["total_tracked_minutes"] == 0


class TestBurndown:
    @pytest.mark.asyncio
    async def test_burndown_data_generation(self, client: AsyncClient, auth_headers: dict):
        """Test that burndown data is correctly generated for a project."""
        today = date.today()
        today_str = today.isoformat()
        project_id = await _create_project(client, auth_headers, "Burndown Project")

        # Create several tasks
        await _create_task(client, auth_headers, project_id, "B1", due_date=today_str)
        await _create_task(client, auth_headers, project_id, "B2", due_date=today_str, status="done")
        await _create_task(client, auth_headers, project_id, "B3", due_date=today_str)

        resp = await client.get(
            "/api/v1/analytics/project-progress",
            params={"project_id": project_id, "start_date": today_str, "end_date": today_str},
            headers=auth_headers,
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["project_name"] == "Burndown Project"
        assert data["total_tasks"] == 3
        assert data["completed_tasks"] == 1
        assert len(data["burndown"]) == 1
        assert data["burndown"][0]["remaining"] == 2
        assert data["burndown"][0]["completed_cumulative"] == 1
        assert "velocity" in data
        assert "is_on_track" in data

    @pytest.mark.asyncio
    async def test_burndown_treats_fractional_progress_as_partial_completion(
        self, client: AsyncClient, auth_headers: dict
    ):
        """PrismTask-timeline-class scope, PR-4 (audit P9 option a).

        A task with ``progress_percent = 60`` contributes 0.6 toward
        ``completed_cumulative`` even when its status is still
        ``in_progress``. Open fractional rows only contribute on the
        report's last day so day-by-day values stay monotonic.
        """
        today = date.today()
        today_str = today.isoformat()
        project_id = await _create_project(client, auth_headers, "Frac Project")

        # 3 tasks: one binary done, one fractional 60%, one plain todo.
        binary_done = await _create_task(
            client, auth_headers, project_id, "binary_done",
            due_date=today_str, status="done",
        )
        fractional = await _create_task(
            client, auth_headers, project_id, "fractional",
            due_date=today_str, status="in_progress",
        )
        await _create_task(client, auth_headers, project_id, "todo", due_date=today_str)

        # Author the fractional progress via PATCH.
        await client.patch(
            f"/api/v1/tasks/{fractional}",
            json={"progress_percent": 60},
            headers=auth_headers,
        )

        resp = await client.get(
            "/api/v1/analytics/project-progress",
            params={
                "project_id": project_id,
                "start_date": today_str,
                "end_date": today_str,
            },
            headers=auth_headers,
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["total_tasks"] == 3
        # 1.0 (binary done) + 0.6 (fractional) + 0 = 1.6
        assert data["completed_tasks"] == 1.6
        # Same value reported as the day's cumulative on the only day.
        assert data["burndown"][0]["completed_cumulative"] == 1.6
        # 3 existing - 1.6 done = 1.4 remaining.
        assert data["burndown"][0]["remaining"] == 1.4
        # Untouched binary_done still works.
        assert binary_done > 0

    @pytest.mark.asyncio
    async def test_burndown_clamps_out_of_range_progress_percent(
        self, client: AsyncClient, auth_headers: dict
    ):
        """Defensive clamp at the burndown layer — a malformed row with
        ``progress_percent`` outside [0, 100] is bounded rather than
        breaking the report."""
        today = date.today()
        today_str = today.isoformat()
        project_id = await _create_project(client, auth_headers, "Clamp Project")

        too_high = await _create_task(
            client, auth_headers, project_id, "too_high",
            due_date=today_str, status="in_progress",
        )
        await client.patch(
            f"/api/v1/tasks/{too_high}",
            json={"progress_percent": 250},
            headers=auth_headers,
        )

        resp = await client.get(
            "/api/v1/analytics/project-progress",
            params={
                "project_id": project_id,
                "start_date": today_str,
                "end_date": today_str,
            },
            headers=auth_headers,
        )
        assert resp.status_code == 200
        # 250 clamps to 100 → 1.0 unit.
        assert resp.json()["completed_tasks"] == 1.0

    @pytest.mark.asyncio
    async def test_burndown_not_found(self, client: AsyncClient, auth_headers: dict):
        """Test that non-existent project returns 404."""
        resp = await client.get(
            "/api/v1/analytics/project-progress",
            params={"project_id": 99999},
            headers=auth_headers,
        )
        assert resp.status_code == 404


class TestHabitCorrelations:
    @pytest.fixture(autouse=True)
    def mock_anthropic_module(self):
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

    @pytest.mark.asyncio
    async def test_habit_correlation_with_mocked_claude(self, client: AsyncClient, auth_headers: dict):
        """Test habit correlations endpoint with mocked AI response."""
        from app.routers.analytics import correlation_rate_limiter
        correlation_rate_limiter._requests.clear()

        # Create a habit and some completions
        habit_id = await _create_habit(client, auth_headers, "Exercise")
        today = date.today()
        for i in range(3):
            d = (today - timedelta(days=i)).isoformat()
            await _complete_habit(client, auth_headers, habit_id, d)

        mock_ai_response = {
            "correlations": [
                {
                    "habit": "Exercise",
                    "done_productivity": 85.0,
                    "not_done_productivity": 60.0,
                    "correlation": "positive",
                    "interpretation": "You complete more tasks on exercise days",
                }
            ],
            "top_insight": "Exercise boosts your productivity",
            "recommendation": "Keep exercising daily",
        }

        with patch("app.services.ai_productivity.analyze_habit_correlations") as mock_analyze:
            mock_analyze.return_value = mock_ai_response
            resp = await client.get(
                "/api/v1/analytics/habit-correlations",
                headers=auth_headers,
            )
            assert resp.status_code == 200
            data = resp.json()
            assert len(data["correlations"]) == 1
            assert data["correlations"][0]["habit"] == "Exercise"
            assert data["correlations"][0]["correlation"] == "positive"
            assert data["top_insight"] == "Exercise boosts your productivity"


class TestSummary:
    @pytest.mark.asyncio
    async def test_summary_endpoint(self, client: AsyncClient, auth_headers: dict):
        """Test the analytics summary endpoint returns complete data."""
        today = date.today().isoformat()
        project_id = await _create_project(client, auth_headers)

        # Create a completed task
        await _create_task(client, auth_headers, project_id, "Summary Task", due_date=today, status="done")

        # Create a habit with a completion
        habit_id = await _create_habit(client, auth_headers, "Read")
        await _complete_habit(client, auth_headers, habit_id, today)

        resp = await client.get("/api/v1/analytics/summary", headers=auth_headers)
        assert resp.status_code == 200
        data = resp.json()

        assert "today" in data
        assert data["today"]["completed"] >= 1
        assert "remaining" in data["today"]
        assert "score" in data["today"]

        assert "this_week" in data
        assert "completed" in data["this_week"]
        assert "trend" in data["this_week"]

        assert "this_month" in data
        assert "streaks" in data
        assert "current_productive_days" in data["streaks"]
        assert "longest_productive_days" in data["streaks"]

        assert "habits" in data
        assert "completion_rate_7d" in data["habits"]
        assert "completion_rate_30d" in data["habits"]

# --- Tests for determine_trend ---

from app.services.analytics import determine_trend

def test_determine_trend_less_than_two_elements():
    assert determine_trend([]) == "stable"
    assert determine_trend([{"score": 10}]) == "stable"

def test_determine_trend_improving():
    # first_half avg: (10+10)/2=10, second_half avg: (20+20)/2=20. diff = 10 (>3) -> improving
    scores = [{"score": 10}, {"score": 10}, {"score": 20}, {"score": 20}]
    assert determine_trend(scores) == "improving"

def test_determine_trend_declining():
    # first_half avg: (20+20)/2=20, second_half avg: (10+10)/2=10. diff = -10 (<-3) -> declining
    scores = [{"score": 20}, {"score": 20}, {"score": 10}, {"score": 10}]
    assert determine_trend(scores) == "declining"

def test_determine_trend_stable():
    # first_half avg: (10+10)/2=10, second_half avg: (12+12)/2=12. diff = 2 (<=3 and >=-3) -> stable
    scores = [{"score": 10}, {"score": 10}, {"score": 12}, {"score": 12}]
    assert determine_trend(scores) == "stable"

    # diff = -2
    scores = [{"score": 12}, {"score": 12}, {"score": 10}, {"score": 10}]
    assert determine_trend(scores) == "stable"

def test_determine_trend_odd_number_of_elements():
    # len=5, mid=2. first_half: [:2] (10, 10) avg=10. second_half: [2:] (15, 20, 20) avg=(55)/3=18.33. diff = 8.33 > 3 -> improving
    scores = [{"score": 10}, {"score": 10}, {"score": 15}, {"score": 20}, {"score": 20}]
    assert determine_trend(scores) == "improving"

    # len=3, mid=1. first_half: [:1] (20) avg=20. second_half: [1:] (10, 10) avg=10. diff = -10 < -3 -> declining
    scores = [{"score": 20}, {"score": 10}, {"score": 10}]
    assert determine_trend(scores) == "declining"
