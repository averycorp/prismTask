import pytest
from httpx import AsyncClient
from unittest.mock import patch


@pytest.fixture
async def goal_and_project(client: AsyncClient, auth_headers: dict):
    goal_resp = await client.post(
        "/api/v1/goals", json={"title": "Test Goal"}, headers=auth_headers
    )
    goal_id = goal_resp.json()["id"]

    project_resp = await client.post(
        f"/api/v1/goals/{goal_id}/projects",
        json={"title": "Test Project"},
        headers=auth_headers,
    )
    project_id = project_resp.json()["id"]
    return goal_id, project_id


@pytest.mark.asyncio
async def test_create_task(client: AsyncClient, auth_headers: dict, goal_and_project):
    _, project_id = goal_and_project
    resp = await client.post(
        f"/api/v1/projects/{project_id}/tasks",
        json={"title": "My Task", "priority": 2},
        headers=auth_headers,
    )
    assert resp.status_code == 201
    data = resp.json()
    assert data["title"] == "My Task"
    assert data["priority"] == 2
    assert data["status"] == "todo"
    assert data["depth"] == 0


@pytest.mark.asyncio
async def test_list_tasks(client: AsyncClient, auth_headers: dict, goal_and_project):
    _, project_id = goal_and_project
    await client.post(
        f"/api/v1/projects/{project_id}/tasks",
        json={"title": "Task 1"},
        headers=auth_headers,
    )
    await client.post(
        f"/api/v1/projects/{project_id}/tasks",
        json={"title": "Task 2"},
        headers=auth_headers,
    )
    resp = await client.get(
        f"/api/v1/projects/{project_id}/tasks", headers=auth_headers
    )
    assert resp.status_code == 200
    assert len(resp.json()) >= 2


@pytest.mark.asyncio
async def test_create_subtask(client: AsyncClient, auth_headers: dict, goal_and_project):
    _, project_id = goal_and_project
    task_resp = await client.post(
        f"/api/v1/projects/{project_id}/tasks",
        json={"title": "Parent Task"},
        headers=auth_headers,
    )
    task_id = task_resp.json()["id"]

    resp = await client.post(
        f"/api/v1/tasks/{task_id}/subtasks",
        json={"title": "Subtask 1"},
        headers=auth_headers,
    )
    assert resp.status_code == 201
    data = resp.json()
    assert data["title"] == "Subtask 1"
    assert data["depth"] == 1
    assert data["parent_id"] == task_id


@pytest.mark.asyncio
async def test_subtask_depth_constraint(client: AsyncClient, auth_headers: dict, goal_and_project):
    _, project_id = goal_and_project
    task_resp = await client.post(
        f"/api/v1/projects/{project_id}/tasks",
        json={"title": "Parent"},
        headers=auth_headers,
    )
    task_id = task_resp.json()["id"]

    subtask_resp = await client.post(
        f"/api/v1/tasks/{task_id}/subtasks",
        json={"title": "Child"},
        headers=auth_headers,
    )
    subtask_id = subtask_resp.json()["id"]

    # Attempting to create sub-subtask should fail (depth 1 is max)
    resp = await client.post(
        f"/api/v1/tasks/{subtask_id}/subtasks",
        json={"title": "Grandchild"},
        headers=auth_headers,
    )
    assert resp.status_code == 400
    assert "depth" in resp.json()["detail"].lower()


@pytest.mark.asyncio
async def test_update_task_status(client: AsyncClient, auth_headers: dict, goal_and_project):
    _, project_id = goal_and_project
    task_resp = await client.post(
        f"/api/v1/projects/{project_id}/tasks",
        json={"title": "Complete Me"},
        headers=auth_headers,
    )
    task_id = task_resp.json()["id"]

    resp = await client.patch(
        f"/api/v1/tasks/{task_id}",
        json={"status": "done"},
        headers=auth_headers,
    )
    assert resp.status_code == 200
    assert resp.json()["status"] == "done"
    assert resp.json()["completed_at"] is not None


@pytest.mark.asyncio
async def test_delete_task(client: AsyncClient, auth_headers: dict, goal_and_project):
    _, project_id = goal_and_project
    task_resp = await client.post(
        f"/api/v1/projects/{project_id}/tasks",
        json={"title": "Delete Me"},
        headers=auth_headers,
    )
    task_id = task_resp.json()["id"]

    resp = await client.delete(f"/api/v1/tasks/{task_id}", headers=auth_headers)
    assert resp.status_code == 204

    get_resp = await client.get(f"/api/v1/tasks/{task_id}", headers=auth_headers)
    assert get_resp.status_code == 404


@pytest.mark.asyncio
async def test_get_task_with_subtasks(client: AsyncClient, auth_headers: dict, goal_and_project):
    _, project_id = goal_and_project
    task_resp = await client.post(
        f"/api/v1/projects/{project_id}/tasks",
        json={"title": "Parent"},
        headers=auth_headers,
    )
    task_id = task_resp.json()["id"]

    await client.post(
        f"/api/v1/tasks/{task_id}/subtasks",
        json={"title": "Sub 1"},
        headers=auth_headers,
    )
    await client.post(
        f"/api/v1/tasks/{task_id}/subtasks",
        json={"title": "Sub 2"},
        headers=auth_headers,
    )

    resp = await client.get(f"/api/v1/tasks/{task_id}", headers=auth_headers)
    assert resp.status_code == 200
    assert len(resp.json()["subtasks"]) == 2


@pytest.mark.asyncio
async def test_parse_debug_does_not_leak_api_key_length(
    client: AsyncClient, auth_headers: dict
):
    resp = await client.get("/api/v1/tasks/parse-debug", headers=auth_headers)
    assert resp.status_code == 200
    body = resp.json()
    assert "api_key_length" not in body
    assert isinstance(body.get("api_key_configured"), bool)
    assert "model" in body
    assert "anthropic_installed" in body


@pytest.mark.asyncio
async def test_parse_debug_anthropic_not_installed(
    client: AsyncClient, auth_headers: dict
):
    with patch.dict("sys.modules", {"anthropic": None}):
        resp = await client.get("/api/v1/tasks/parse-debug", headers=auth_headers)
        assert resp.status_code == 200
        body = resp.json()
        assert body.get("anthropic_installed") is False
