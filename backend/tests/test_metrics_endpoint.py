"""Tests for the /metrics Prometheus endpoint.

The endpoint is bearer-gated on ``settings.METRICS_SCRAPE_TOKEN``. Empty
token returns 503 so a misconfigured prod deploy can't accidentally leak
metrics to anonymous callers; mismatched token returns 401; matched
token returns the standard text/plain Prometheus exposition.
"""

import pytest
from httpx import AsyncClient

from app.config import settings


@pytest.fixture
def scrape_token(monkeypatch):
    """Set METRICS_SCRAPE_TOKEN and wildcard METRICS_ALLOWED_IPS for the test."""
    token = "test-scrape-token"
    monkeypatch.setattr(settings, "METRICS_SCRAPE_TOKEN", token)
    monkeypatch.setattr(settings, "METRICS_ALLOWED_IPS", ["*"])
    return token


@pytest.mark.asyncio
async def test_metrics_unauthenticated_returns_401(client: AsyncClient, scrape_token):
    resp = await client.get("/metrics")
    assert resp.status_code == 401, resp.text


@pytest.mark.asyncio
async def test_metrics_wrong_token_returns_401(client: AsyncClient, scrape_token):
    resp = await client.get(
        "/metrics", headers={"Authorization": "Bearer not-the-token"}
    )
    assert resp.status_code == 401, resp.text


@pytest.mark.asyncio
async def test_metrics_correct_token_returns_prometheus_text(
    client: AsyncClient, scrape_token
):
    resp = await client.get(
        "/metrics", headers={"Authorization": f"Bearer {scrape_token}"}
    )
    assert resp.status_code == 200, resp.text
    assert resp.headers["content-type"].startswith("text/plain"), resp.headers
    # The audit-emit counter must be in the exposition output even at
    # zero, because the Counter is registered at import time.
    assert "audit_emit_failures_total" in resp.text


@pytest.mark.asyncio
async def test_metrics_ip_not_allowed_returns_403(client: AsyncClient, scrape_token, monkeypatch):
    monkeypatch.setattr(settings, "METRICS_ALLOWED_IPS", ["192.168.1.1"])
    resp = await client.get(
        "/metrics", headers={"Authorization": f"Bearer {scrape_token}", "X-Forwarded-For": "10.0.0.1"}
    )
    assert resp.status_code == 403, resp.text


@pytest.mark.asyncio
async def test_metrics_ip_allowed_returns_prometheus_text(client: AsyncClient, scrape_token, monkeypatch):
    monkeypatch.setattr(settings, "METRICS_ALLOWED_IPS", ["10.0.0.1"])
    resp = await client.get(
        "/metrics", headers={"Authorization": f"Bearer {scrape_token}", "X-Forwarded-For": "10.0.0.1"}
    )
    assert resp.status_code == 200, resp.text


@pytest.mark.asyncio
async def test_metrics_empty_token_returns_503(client: AsyncClient, monkeypatch):
    """Empty METRICS_SCRAPE_TOKEN keeps the endpoint inert. This is the
    default in dev/test and prevents metrics from leaking on a misconfigured
    deploy where the operator forgot to set the env var."""
    monkeypatch.setattr(settings, "METRICS_SCRAPE_TOKEN", "")
    resp = await client.get(
        "/metrics", headers={"Authorization": "Bearer anything"}
    )
    assert resp.status_code == 503, resp.text
