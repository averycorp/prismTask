import asyncio
from datetime import datetime, timezone
import json
import time
from sqlalchemy import select
from sqlalchemy.ext.asyncio import create_async_engine, AsyncSession
from sqlalchemy.orm import sessionmaker

from app.database import Base
from app.models import Project, Tag, Task, TaskTag, TaskTemplate, User

async def run_benchmark():
    engine = create_async_engine("sqlite+aiosqlite:///:memory:", echo=False)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    async_session = sessionmaker(
        engine, class_=AsyncSession, expire_on_commit=False
    )

    async with async_session() as db:
        user = User(id=1, firebase_uid="test1", email="test@example.com")
        db.add(user)
        project = Project(id=1, user_id=1, name="Proj")
        db.add(project)

        tags = []
        for i in range(100):
            tag = Tag(id=i+1, user_id=1, name=f"Tag{i}")
            db.add(tag)
            tags.append(i+1)

        tmpl = TaskTemplate(
            id=1, user_id=1, name="Tmpl", template_project_id=1,
            template_tags_json=json.dumps(tags)
        )
        db.add(tmpl)
        await db.commit()

        start = time.perf_counter()

        for _ in range(10): # run 10 times to get good measurement
            new_task = Task(id=100+_, project_id=1, user_id=1, title="New Task", depth=0)
            db.add(new_task)
            await db.flush()

            # The current N+1 way
            if tmpl.template_tags_json:
                tag_ids = json.loads(tmpl.template_tags_json)
                if isinstance(tag_ids, list):
                    for tag_id in tag_ids:
                        if not isinstance(tag_id, int):
                            continue
                        tag_result = await db.execute(
                            select(Tag).where(
                                Tag.id == tag_id, Tag.user_id == user.id
                            )
                        )
                        if tag_result.scalar_one_or_none() is None:
                            continue
                        db.add(TaskTag(task_id=new_task.id, tag_id=tag_id))
            await db.flush()

        end = time.perf_counter()
        print(f"N+1 time: {end - start:.4f}s")

        # Now clean up DB and run the optimized way
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.drop_all)
            await conn.run_sync(Base.metadata.create_all)

        user = User(id=1, firebase_uid="test1", email="test@example.com")
        db.add(user)
        project = Project(id=1, user_id=1, name="Proj")
        db.add(project)

        tags = []
        for i in range(100):
            tag = Tag(id=i+1, user_id=1, name=f"Tag{i}")
            db.add(tag)
            tags.append(i+1)

        tmpl = TaskTemplate(
            id=1, user_id=1, name="Tmpl", template_project_id=1,
            template_tags_json=json.dumps(tags)
        )
        db.add(tmpl)
        await db.commit()

        start = time.perf_counter()

        for _ in range(10): # run 10 times
            new_task = Task(id=100+_, project_id=1, user_id=1, title="New Task", depth=0)
            db.add(new_task)
            await db.flush()

            # Optimized way
            if tmpl.template_tags_json:
                tag_ids = json.loads(tmpl.template_tags_json)
                if isinstance(tag_ids, list):
                    valid_tag_ids = [t_id for t_id in tag_ids if isinstance(t_id, int)]
                    if valid_tag_ids:
                        tag_result = await db.execute(
                            select(Tag.id).where(
                                Tag.id.in_(valid_tag_ids), Tag.user_id == user.id
                            )
                        )
                        user_tag_ids = set(tag_result.scalars().all())
                        for tag_id in valid_tag_ids:
                            if tag_id in user_tag_ids:
                                db.add(TaskTag(task_id=new_task.id, tag_id=tag_id))
            await db.flush()

        end = time.perf_counter()
        print(f"Optimized time: {end - start:.4f}s")

if __name__ == "__main__":
    asyncio.run(run_benchmark())
