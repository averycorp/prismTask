import asyncio
import time
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from app.models import User, Project, Goal, Base
from app.routers.projects import reorder_projects
from fastapi import HTTPException
import uuid

# Use sqlite in-memory for testing
engine = create_async_engine("sqlite+aiosqlite:///:memory:", echo=False)
async_session_factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)

async def setup_data(db, user_id):
    # Create tables
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    # Create a user
    user = User(id=user_id, email=f"{user_id}@example.com", firebase_uid=str(uuid.uuid4()), hashed_password="dummy", name="test user")
    db.add(user)
    await db.flush()

    goal = Goal(id=1000, user_id=user.id, title="Test Goal")
    db.add(goal)
    await db.flush()

    projects = []
    for i in range(1, 1001):
        p = Project(id=i+1000, user_id=user.id, goal_id=goal.id, title=f"Project {i}", sort_order=i)
        db.add(p)
        projects.append(p)
    await db.flush()
    await db.commit()
    return user, projects

async def main():
    async with async_session_factory() as db:
        user_id = 9999
        user, projects = await setup_data(db, user_id)

        items = [{"id": p.id, "sort_order": 1000 - i} for i, p in enumerate(projects)]

        start_time = time.time()
        try:
            await reorder_projects(items=items, current_user=user, db=db)
        except Exception as e:
            print(f"Error: {e}")
        end_time = time.time()

        duration = end_time - start_time
        print(f"Time taken to reorder {len(items)} projects (Optimized): {duration:.4f} seconds")

if __name__ == "__main__":
    asyncio.run(main())
