import asyncio
import time
from sqlalchemy import select, delete
from sqlalchemy.ext.asyncio import create_async_engine, AsyncSession
from sqlalchemy.orm import sessionmaker
from app.models import Base, Task, User, Project, Goal
from datetime import datetime, timezone

engine = create_async_engine('sqlite+aiosqlite:///:memory:')
async_session = sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)

async def setup_db():
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    async with async_session() as session:
        user = User(email="test@example.com", hashed_password="pw", name="Test")
        session.add(user)
        await session.flush()

        goal = Goal(user_id=user.id, title="Test Goal")
        session.add(goal)
        await session.flush()

        project = Project(user_id=user.id, goal_id=goal.id, title="Test Project")
        session.add(project)

        await session.commit()
        await session.refresh(user)
        await session.refresh(project)
        return user.id, project.id

async def populate_tasks(user_id, project_id, count=1000):
    async with async_session() as session:
        for i in range(count):
            session.add(Task(user_id=user_id, project_id=project_id, title=f"Task {i}"))
        await session.commit()

async def benchmark_scalar_delete(user_id):
    async with async_session() as session:
        start = time.time()
        existing = await session.execute(select(Task).where(Task.user_id == user_id))
        for entity in existing.scalars().all():
            await session.delete(entity)
        await session.flush()
        end = time.time()
        return end - start

async def benchmark_bulk_delete(user_id):
    async with async_session() as session:
        start = time.time()
        await session.execute(delete(Task).where(Task.user_id == user_id))
        await session.flush()
        end = time.time()
        return end - start

async def main():
    user_id, project_id = await setup_db()

    await populate_tasks(user_id, project_id, 1000)
    time_scalar = await benchmark_scalar_delete(user_id)
    print(f"Scalar delete 1000 rows: {time_scalar:.4f}s")

    await populate_tasks(user_id, project_id, 1000)
    time_bulk = await benchmark_bulk_delete(user_id)
    print(f"Bulk delete 1000 rows: {time_bulk:.4f}s")

    print(f"Improvement: {time_scalar/time_bulk:.2f}x faster")

if __name__ == "__main__":
    asyncio.run(main())
