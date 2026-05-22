import asyncio
import time
from sqlalchemy.ext.asyncio import create_async_engine, AsyncSession
from sqlalchemy.orm import sessionmaker
from datetime import datetime, timezone

import os
import sys
# Make sure backend is in path
sys.path.insert(0, os.path.abspath('backend'))

from app.models import Base, User, Goal, Project, Task, Tag, Habit, HabitCompletion
from app.routers.sync import ENTITY_MAP

async def run_benchmark():
    # Setup test DB (sqlite)
    engine = create_async_engine('sqlite+aiosqlite:///:memory:', echo=False)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    async_session = sessionmaker(
        engine, class_=AsyncSession, expire_on_commit=False
    )

    # Insert test user and some data
    async with async_session() as session:
        user = User(email="test@example.com", hashed_password="pw")
        session.add(user)
        await session.commit()
        await session.refresh(user)

        # Add a bunch of entities to simulate real data
        for i in range(100):
            session.add(Goal(user_id=user.id, title=f"Goal {i}"))
            session.add(Project(user_id=user.id, title=f"Project {i}"))
            session.add(Task(user_id=user.id, title=f"Task {i}"))
            session.add(Tag(user_id=user.id, name=f"Tag {i}"))
            session.add(Habit(user_id=user.id, title=f"Habit {i}"))
        await session.commit()

    # The benchmark function (simulate sync_pull)
    async def simulate_sync_pull(db, current_user, since=None):
        changes = []
        for entity_type, model in ENTITY_MAP.items():
            if entity_type == "habit_completion":
                continue

            from sqlalchemy import select
            query = select(model)
            if hasattr(model, "user_id"):
                query = query.where(model.user_id == current_user.id)

            if since and hasattr(model, "updated_at"):
                query = query.where(model.updated_at > since)
            elif since and hasattr(model, "created_at"):
                query = query.where(model.created_at > since)

            result = await db.execute(query)
            for entity in result.scalars().all():
                data = {}
                for col in entity.__table__.columns:
                    val = getattr(entity, col.name)
                    if hasattr(val, "value"):
                        val = val.value
                    if hasattr(val, "isoformat"):
                        val = val.isoformat()
                    data[col.name] = val

                timestamp = getattr(entity, "updated_at", None) or getattr(entity, "created_at", None)
                changes.append({
                    "entity_type": entity_type,
                    "operation": "upsert",
                    "entity_id": entity.id,
                    "data": data,
                    "timestamp": timestamp or datetime.now(timezone.utc),
                })
        return changes

    # Warmup
    async with async_session() as session:
        await simulate_sync_pull(session, user)

    # Benchmark
    times = []
    for _ in range(50):
        async with async_session() as session:
            start = time.perf_counter()
            await simulate_sync_pull(session, user)
            end = time.perf_counter()
            times.append(end - start)

    avg_time = sum(times) / len(times)
    print(f"Average time for sync_pull simulation: {avg_time * 1000:.2f} ms")

asyncio.run(run_benchmark())
