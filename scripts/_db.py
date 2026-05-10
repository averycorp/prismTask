"""Shared DB helpers for admin CLI scripts.

Railway and most managed Postgres providers expose ``DATABASE_URL`` with the
bare ``postgresql://`` (or legacy ``postgres://``) scheme, but SQLAlchemy's
``create_async_engine`` requires an explicit async driver
(``postgresql+asyncpg://``). Without coercion, every admin CLI script run
against Railway fails with::

    InvalidRequestError: The asyncio extension requires an async driver to
    be used.

``coerce_async_url`` normalizes any of the supported postgres scheme
variants to the asyncpg form. It is idempotent — calling it twice is safe.

``async_engine_from_settings`` is the convenience constructor used by
``set_admin.py`` and ``beta_codes.py`` so future admin scripts inherit the
fix for free.
"""

from __future__ import annotations

import sys

from sqlalchemy.ext.asyncio import AsyncEngine, create_async_engine

# Allow running from repo root.
sys.path.insert(0, "backend")

from app.config import settings  # noqa: E402


def coerce_async_url(url: str) -> str:
    """Coerce a postgres URL to use the asyncpg driver. Idempotent."""
    if url.startswith("postgresql+asyncpg://"):
        return url
    if url.startswith("postgresql://"):
        return "postgresql+asyncpg://" + url[len("postgresql://") :]
    if url.startswith("postgres://"):
        return "postgresql+asyncpg://" + url[len("postgres://") :]
    return url


def async_engine_from_settings(*, echo: bool = False) -> AsyncEngine:
    """Build an async engine from ``settings.DATABASE_URL`` with scheme coercion."""
    return create_async_engine(coerce_async_url(settings.DATABASE_URL), echo=echo)
