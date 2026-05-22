import os
import time

os.environ["JWT_SECRET_KEY"] = "testsecret"

# Force process timezone to UTC to align local date.today() with UTC datetime.now()
# during tests, preventing timezone boundary failures.
os.environ["TZ"] = "UTC"
try:
    time.tzset()
except AttributeError:
    pass

from typing import AsyncGenerator

import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy import event
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.database import get_db
from app.main import app
from app.middleware.rate_limit import auth_rate_limiter
from app.models import Base

TEST_DATABASE_URL = "sqlite+aiosqlite:///./test.db"

engine = create_async_engine(TEST_DATABASE_URL, echo=False)


# SQLite serializes writes per file. The medication audit hook uses
# FastAPI BackgroundTasks to write to a *fresh* session decoupled from
# the request session — two concurrent writers collide as "database is
# locked" unless WAL mode + a busy timeout is configured.
@event.listens_for(engine.sync_engine, "connect")
def _enable_sqlite_concurrency(dbapi_conn, connection_record):
    cursor = dbapi_conn.cursor()
    cursor.execute("PRAGMA journal_mode=WAL")
    cursor.execute("PRAGMA busy_timeout=5000")
    cursor.close()


TestSessionLocal = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)


@pytest_asyncio.fixture(autouse=True)
async def setup_db():
    # Reset the in-memory auth rate limiter between tests so the per-IP
    # counter doesn't leak across the session and start returning 429s
    # once enough register/login calls have been made.
    auth_rate_limiter._requests.clear()
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)


async def override_get_db() -> AsyncGenerator[AsyncSession, None]:
    async with TestSessionLocal() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise


app.dependency_overrides[get_db] = override_get_db


@pytest_asyncio.fixture
async def client() -> AsyncGenerator[AsyncClient, None]:
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac


@pytest_asyncio.fixture
async def auth_headers(client: AsyncClient) -> dict:
    reg = await client.post(
        "/api/v1/auth/register",
        json={"email": "test@example.com", "name": "Test User", "password": "testpass123"},
    )
    assert reg.status_code == 201, f"register failed: {reg.status_code} {reg.text}"
    resp = await client.post(
        "/api/v1/auth/login",
        json={"email": "test@example.com", "password": "testpass123"},
    )
    assert resp.status_code == 200, f"login failed: {resp.status_code} {resp.text}"
    token = resp.json()["access_token"]
    return {"Authorization": f"Bearer {token}"}


async def _elevate_tier(email: str, tier: str, firebase_uid: str | None = None) -> None:
    """Directly set a user's tier (and optionally firebase_uid) in the test DB.

    AI endpoints enforce the tier gate server-side, so tests that exercise
    them need to upgrade the default test user from FREE. They also now
    require a ``firebase_uid`` because tasks live in Firestore under
    ``users/{uid}/tasks``.
    """
    from sqlalchemy import update as sqla_update

    from app.models import User as UserModel

    values: dict = {"tier": tier}
    if firebase_uid is not None:
        values["firebase_uid"] = firebase_uid

    async with TestSessionLocal() as session:
        await session.execute(
            sqla_update(UserModel).where(UserModel.email == email).values(**values)
        )
        await session.commit()


TEST_FIREBASE_UID = "test-firebase-uid"


@pytest_asyncio.fixture
async def pro_auth_headers(client: AsyncClient, auth_headers: dict) -> dict:
    """Same as ``auth_headers`` but with the user elevated to PRO and linked
    to a Firebase UID.

    Use this for tests that hit AI endpoints protected by
    ``DailyAIRateLimiter`` / ``ProFeatureGate``. The ``firebase_uid`` is
    required by AI endpoints (they query Firestore); tests that don't need
    AI endpoints can keep using ``auth_headers``.
    """
    await _elevate_tier("test@example.com", "PRO", firebase_uid=TEST_FIREBASE_UID)
    return auth_headers
