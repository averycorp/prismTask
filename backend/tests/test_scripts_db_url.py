"""Tests for ``scripts/_db.py`` URL coercion.

Railway exposes ``DATABASE_URL`` with the bare ``postgresql://`` scheme but
SQLAlchemy ``create_async_engine`` requires the explicit asyncpg driver. The
``coerce_async_url`` helper in ``scripts/_db.py`` normalizes either of the
supported postgres scheme variants (or the legacy ``postgres://`` alias) to
the asyncpg form, idempotently.
"""
from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest

# Load scripts/_db.py as a standalone module (it lives outside backend/).
_DB_HELPER_PATH = Path(__file__).resolve().parents[2] / "scripts" / "_db.py"
_spec = importlib.util.spec_from_file_location("scripts_db_helper", _DB_HELPER_PATH)
_db_helper = importlib.util.module_from_spec(_spec)  # type: ignore[arg-type]
sys.modules["scripts_db_helper"] = _db_helper
_spec.loader.exec_module(_db_helper)  # type: ignore[union-attr]

coerce_async_url = _db_helper.coerce_async_url


@pytest.mark.parametrize(
    "given, expected",
    [
        # Railway-style bare postgres scheme — primary motivating case.
        (
            "postgresql://user:pw@host:5432/db",
            "postgresql+asyncpg://user:pw@host:5432/db",
        ),
        # Legacy ``postgres://`` alias.
        (
            "postgres://user:pw@host/db",
            "postgresql+asyncpg://user:pw@host/db",
        ),
        # Already-coerced URL passes through unchanged (idempotent).
        (
            "postgresql+asyncpg://user:pw@host:5432/db",
            "postgresql+asyncpg://user:pw@host:5432/db",
        ),
        # Unrelated scheme is left alone.
        (
            "sqlite+aiosqlite:///:memory:",
            "sqlite+aiosqlite:///:memory:",
        ),
    ],
)
def test_coerce_async_url(given: str, expected: str) -> None:
    assert coerce_async_url(given) == expected


def test_coerce_async_url_idempotent() -> None:
    once = coerce_async_url("postgresql://u:p@h/d")
    twice = coerce_async_url(once)
    assert once == twice == "postgresql+asyncpg://u:p@h/d"
