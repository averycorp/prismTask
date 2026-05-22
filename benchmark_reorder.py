import asyncio
from sqlalchemy.ext.asyncio import create_async_engine, AsyncSession
from sqlalchemy.orm import sessionmaker
from sqlalchemy import select, text
from backend.app.models import Base, Task, User
from backend.app.routers.tasks import ReorderItem, reorder_tasks
from fastapi import HTTPException
import time

async def setup_db():
    engine = create_async_engine('sqlite+aiosqlite:///:memory:', echo=False)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    return engine

async def run_benchmark():
    engine = await setup_db()
    async_session = sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)

    async with async_session() as db:
        user = User(id=1, username="test_user", email="test@example.com", hashed_password="pw")
        db.add(user)

        # Add 500 tasks
        for i in range(500):
            task = Task(id=i+1, title=f"Task {i}", sort_order=i, user_id=1)
            db.add(task)
        await db.commit()

        # Prepare 500 items for reordering
        items = [ReorderItem(id=i+1, sort_order=500-i) for i in range(500)]

        # Run benchmark
        start_time = time.time()
        try:
            await reorder_tasks(items=items, current_user=user, db=db)
        except Exception as e:
            print(f"Error: {e}")
        end_time = time.time()

        print(f"Time taken: {end_time - start_time:.4f} seconds")

if __name__ == '__main__':
    asyncio.run(run_benchmark())
