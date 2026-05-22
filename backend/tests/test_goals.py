import pytest
from httpx import AsyncClient


@pytest.mark.asyncio
async def test_create_goal(client: AsyncClient, auth_headers: dict):
    resp = await client.post(
        "/api/v1/goals",
        json={"title": "Learn Python", "description": "Master Python programming"},
        headers=auth_headers,
    )
    assert resp.status_code == 201
    data = resp.json()
    assert data["title"] == "Learn Python"
    assert data["status"] == "active"


@pytest.mark.asyncio
async def test_list_goals(client: AsyncClient, auth_headers: dict):
    await client.post("/api/v1/goals", json={"title": "Goal 1"}, headers=auth_headers)
    await client.post("/api/v1/goals", json={"title": "Goal 2"}, headers=auth_headers)
    resp = await client.get("/api/v1/goals", headers=auth_headers)
    assert resp.status_code == 200
    assert len(resp.json()) >= 2


@pytest.mark.asyncio
async def test_get_goal(client: AsyncClient, auth_headers: dict):
    create_resp = await client.post(
        "/api/v1/goals", json={"title": "Detail Goal"}, headers=auth_headers
    )
    goal_id = create_resp.json()["id"]
    resp = await client.get(f"/api/v1/goals/{goal_id}", headers=auth_headers)
    assert resp.status_code == 200
    assert resp.json()["title"] == "Detail Goal"
    assert "projects" in resp.json()


@pytest.mark.asyncio
async def test_update_goal(client: AsyncClient, auth_headers: dict):
    create_resp = await client.post(
        "/api/v1/goals", json={"title": "Old Title"}, headers=auth_headers
    )
    goal_id = create_resp.json()["id"]
    resp = await client.patch(
        f"/api/v1/goals/{goal_id}",
        json={"title": "New Title", "status": "achieved"},
        headers=auth_headers,
    )
    assert resp.status_code == 200
    assert resp.json()["title"] == "New Title"
    assert resp.json()["status"] == "achieved"


@pytest.mark.asyncio
async def test_delete_goal(client: AsyncClient, auth_headers: dict):
    create_resp = await client.post(
        "/api/v1/goals", json={"title": "To Delete"}, headers=auth_headers
    )
    goal_id = create_resp.json()["id"]
    resp = await client.delete(f"/api/v1/goals/{goal_id}", headers=auth_headers)
    assert resp.status_code == 204

    get_resp = await client.get(f"/api/v1/goals/{goal_id}", headers=auth_headers)
    assert get_resp.status_code == 404


@pytest.mark.asyncio
async def test_cascade_delete_goal(client: AsyncClient, auth_headers: dict):
    goal_resp = await client.post(
        "/api/v1/goals", json={"title": "Cascade Goal"}, headers=auth_headers
    )
    goal_id = goal_resp.json()["id"]

    project_resp = await client.post(
        f"/api/v1/goals/{goal_id}/projects",
        json={"title": "Cascade Project"},
        headers=auth_headers,
    )
    project_id = project_resp.json()["id"]

    # Delete the goal — project should cascade
    await client.delete(f"/api/v1/goals/{goal_id}", headers=auth_headers)

    get_resp = await client.get(f"/api/v1/projects/{project_id}", headers=auth_headers)
    assert get_resp.status_code == 404


@pytest.mark.asyncio
async def test_get_nonexistent_goal(client: AsyncClient, auth_headers: dict):
    resp = await client.get("/api/v1/goals/99999", headers=auth_headers)
    assert resp.status_code == 404


@pytest.mark.asyncio
async def test_reorder_goals(client: AsyncClient, auth_headers: dict):
    goal1_resp = await client.post(
        "/api/v1/goals", json={"title": "Goal 1", "sort_order": 1}, headers=auth_headers
    )
    goal2_resp = await client.post(
        "/api/v1/goals", json={"title": "Goal 2", "sort_order": 2}, headers=auth_headers
    )

    assert goal1_resp.status_code == 201
    assert goal2_resp.status_code == 201

    goal1_id = goal1_resp.json()["id"]
    goal2_id = goal2_resp.json()["id"]

    reorder_resp = await client.patch(
        "/api/v1/goals/reorder",
        json=[
            {"id": goal1_id, "sort_order": 2},
            {"id": goal2_id, "sort_order": 1},
        ],
        headers=auth_headers,
    )

    print(reorder_resp.json())
    assert reorder_resp.status_code == 200

    list_resp = await client.get("/api/v1/goals", headers=auth_headers)
    assert list_resp.status_code == 200

    goals = list_resp.json()

    for goal in goals:
        if goal["id"] == goal1_id:
            assert goal["sort_order"] == 2
        elif goal["id"] == goal2_id:
            assert goal["sort_order"] == 1
