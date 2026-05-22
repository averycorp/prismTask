import pytest
from httpx import AsyncClient
from app.config import settings

@pytest.mark.asyncio
async def test_metrics_no_token(client: AsyncClient, monkeypatch):
    monkeypatch.setattr(settings, "METRICS_SCRAPE_TOKEN", "")
    response = await client.get("/metrics")
    assert response.status_code == 503

@pytest.mark.asyncio
async def test_metrics_invalid_token(client: AsyncClient, monkeypatch):
    monkeypatch.setattr(settings, "METRICS_SCRAPE_TOKEN", "secret")
    monkeypatch.setattr(settings, "METRICS_ALLOWED_IPS", ["*"])
    response = await client.get("/metrics", headers={"Authorization": "Bearer bad"})
    assert response.status_code == 401

@pytest.mark.asyncio
async def test_metrics_ip_blocked(client: AsyncClient, monkeypatch):
    monkeypatch.setattr(settings, "METRICS_SCRAPE_TOKEN", "secret")
    monkeypatch.setattr(settings, "METRICS_ALLOWED_IPS", ["1.1.1.1"])
    response = await client.get("/metrics", headers={
        "Authorization": "Bearer secret",
        "X-Forwarded-For": "1.1.1.1"
    })
    assert response.status_code == 403

@pytest.mark.asyncio
async def test_metrics_ip_allowed(client: AsyncClient, monkeypatch):
    monkeypatch.setattr(settings, "METRICS_SCRAPE_TOKEN", "secret")
    monkeypatch.setattr(settings, "METRICS_ALLOWED_IPS", ["127.0.0.1"])
    response = await client.get("/metrics", headers={
        "Authorization": "Bearer secret",
        "X-Forwarded-For": "1.1.1.1"
    })
    assert response.status_code == 200
