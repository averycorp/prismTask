import asyncio
import time
from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker
from sqlalchemy import text, Integer, String, Column, ForeignKey
from sqlalchemy.orm import declarative_base

Base = declarative_base()

class Tag(Base):
    __tablename__ = 'tags'
    id = Column(Integer, primary_key=True)
    user_id = Column(String)
    name = Column(String)

class TaskTag(Base):
    __tablename__ = 'task_tags'
    id = Column(Integer, primary_key=True)
    task_id = Column(Integer)
    tag_id = Column(Integer, ForeignKey('tags.id'))

# Create engine and session
engine = create_async_engine("sqlite+aiosqlite:///:memory:", echo=False)
AsyncSessionLocal = async_sessionmaker(engine, expire_on_commit=False)

async def setup_db():
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

async def test_baseline():
    async with AsyncSessionLocal() as db:
        user_id = "test_user"
        task_id = 1

        # Create 100 tags
        tags = []
        for i in range(100):
            tag = Tag(id=i+1, user_id=user_id, name=f"tag{i}")
            db.add(tag)
            tags.append(tag)

        await db.flush()

        tag_ids = [t.id for t in tags]

        # Benchmark set_task_tags baseline
        start = time.perf_counter()

        from sqlalchemy import select

        # Add new associations
        res_tags = []
        for tid in tag_ids:
            tag_result = await db.execute(
                select(Tag).where(Tag.id == tid, Tag.user_id == user_id)
            )
            tag = tag_result.scalar_one_or_none()
            if not tag:
                raise Exception(f"Tag {tid} not found")
            db.add(TaskTag(task_id=task_id, tag_id=tid))
            res_tags.append(tag)

        await db.flush()

        end = time.perf_counter()
        print(f"Baseline (100 tags): {end - start:.4f} seconds")

async def test_optimized():
    async with AsyncSessionLocal() as db:
        user_id = "test_user"
        task_id = 2

        # Create 100 tags
        tags = []
        for i in range(100, 200):
            tag = Tag(id=i+1, user_id=user_id, name=f"tag{i}")
            db.add(tag)
            tags.append(tag)

        await db.flush()

        tag_ids = [t.id for t in tags]

        # Benchmark optimized set_task_tags
        start = time.perf_counter()

        if not tag_ids:
            res_tags = []
        else:
            from sqlalchemy import select

            tag_result = await db.execute(
                select(Tag).where(Tag.id.in_(tag_ids), Tag.user_id == user_id)
            )
            res_tags = tag_result.scalars().all()

            if len(res_tags) != len(tag_ids):
                raise Exception("Some tags not found")

            for t in res_tags:
                db.add(TaskTag(task_id=task_id, tag_id=t.id))

        await db.flush()

        end = time.perf_counter()
        print(f"Optimized (100 tags): {end - start:.4f} seconds")

async def main():
    await setup_db()
    await test_baseline()
    await test_optimized()

if __name__ == "__main__":
    asyncio.run(main())
