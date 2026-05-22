import pytest
import time
import json
from httpx import AsyncClient
from tests.test_templates import goal_and_project

@pytest.mark.asyncio
async def test_benchmark_use_template(client: AsyncClient, auth_headers: dict, goal_and_project):
    _, project_id = goal_and_project
    # Create 50 tags
    tag_ids = []
    for i in range(50):
        resp = await client.post("/api/v1/tags", json={"name": f"Tag {i}", "color": "#ff0000"}, headers=auth_headers)
        if resp.status_code == 201 or resp.status_code == 200:
            tag_ids.append(resp.json()["id"])

    # Create template
    create = await client.post(
        "/api/v1/templates",
        json={
            "name": "Heavy Template",
            "template_title": "Heavy Task",
            "template_project_id": project_id,
            "template_tags_json": json.dumps(tag_ids)
        },
        headers=auth_headers
    )
    assert create.status_code == 201
    template_id = create.json()["id"]

    # Warmup
    await client.post(f"/api/v1/templates/{template_id}/use", json={}, headers=auth_headers)

    # Benchmark
    start_time = time.perf_counter()
    iterations = 50
    for _ in range(iterations):
        await client.post(f"/api/v1/templates/{template_id}/use", json={}, headers=auth_headers)
    end_time = time.perf_counter()

    avg_time = (end_time - start_time) / iterations * 1000
    print(f"\n--- BENCHMARK RESULTS ---")
    print(f"Average time per template use (50 tags): {avg_time:.2f} ms")
    print(f"-------------------------\n")
