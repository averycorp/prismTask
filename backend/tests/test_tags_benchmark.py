import pytest
import time
from httpx import AsyncClient

@pytest.mark.asyncio
async def test_set_task_tags_benchmark(client: AsyncClient, auth_headers: dict):
    # Setup
    goal_resp = await client.post(
        "/api/v1/goals", json={"title": "Bench Goal"}, headers=auth_headers
    )
    goal_id = goal_resp.json()["id"]

    proj_resp = await client.post(
        f"/api/v1/goals/{goal_id}/projects",
        json={"title": "Bench Project"},
        headers=auth_headers,
    )
    project_id = proj_resp.json()["id"]

    task_resp = await client.post(
        f"/api/v1/projects/{project_id}/tasks",
        json={"title": "Bench Task"},
        headers=auth_headers,
    )
    task_id = task_resp.json()["id"]

    # Create 50 tags
    tag_ids = []
    for i in range(50):
        tag_resp = await client.post("/api/v1/tags", json={"name": f"tag{i}"}, headers=auth_headers)
        tag_ids.append(tag_resp.json()["id"])

    # Benchmark setting tags
    start_time = time.time()
    resp = await client.put(
        f"/api/v1/tags/tasks/{task_id}/tags",
        json=tag_ids,
        headers=auth_headers,
    )
    end_time = time.time()

    assert resp.status_code == 200
    print(f"\nBENCHMARK: Time to set 50 tags: {end_time - start_time:.4f} seconds")
