import asyncio
import time
import json
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import create_async_engine, AsyncSession
from sqlalchemy.orm import sessionmaker

from backend.app.models import Base, User, Task, Project, Goal, Tag, Habit, GoalStatus, ProjectStatus
from backend.app.database import get_db

async def setup_db():
    engine = create_async_engine("sqlite+aiosqlite:///:memory:", echo=False)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    SessionLocal = sessionmaker(bind=engine, class_=AsyncSession, expire_on_commit=False)
    return engine, SessionLocal

async def populate(session, user_id, count):
    # Insert a lot of data
    goal = Goal(user_id=user_id, title="Test Goal", status=GoalStatus.ACTIVE)
    session.add(goal)
    await session.commit()

    project = Project(user_id=user_id, goal_id=goal.id, title="Test Project", status=ProjectStatus.ACTIVE)
    session.add(project)
    await session.commit()

    tags = [Tag(user_id=user_id, name=f"Tag {i}") for i in range(10)]
    session.add_all(tags)

    habits = [Habit(user_id=user_id, name=f"Habit {i}") for i in range(10)]
    session.add_all(habits)

    tasks = [Task(user_id=user_id, project_id=project.id, title=f"Task {i}") for i in range(count)]
    session.add_all(tasks)

    await session.commit()

async def benchmark_old(session, user_id):
    start = time.perf_counter()
    for model in [Task, Project, Goal, Tag, Habit]:
        existing = await session.execute(
            select(model).where(model.user_id == user_id)
        )
        for entity in existing.scalars().all():
            await session.delete(entity)
    await session.flush()
    return time.perf_counter() - start

async def benchmark_new(session, user_id):
    start = time.perf_counter()
    for model in [Task, Project, Goal, Tag, Habit]:
        await session.execute(
            delete(model).where(model.user_id == user_id)
        )
    await session.flush()
    return time.perf_counter() - start

async def main():
    engine, SessionLocal = await setup_db()

    async with SessionLocal() as session:
        user = User(email="test@example.com", hashed_password="pwd", name="Test")
        session.add(user)
        await session.commit()
        user_id = user.id

    # Run old
    async with SessionLocal() as session:
        await populate(session, user_id, 1000)
        t_old = await benchmark_old(session, user_id)
        await session.commit()

    # Run new
    async with SessionLocal() as session:
        await populate(session, user_id, 1000)
        t_new = await benchmark_new(session, user_id)
        await session.commit()

    print(f"Old: {t_old:.4f}s")
    print(f"New: {t_new:.4f}s")
    print(f"Improvement: {(t_old - t_new) / t_old * 100:.2f}%")

if __name__ == "__main__":
    asyncio.run(main())
