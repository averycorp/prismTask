import asyncio
import time
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from fastapi import HTTPException

async def bench_current(db, task_id, tag_ids, current_user_id):
    from app.models import Tag, TaskTag
    tags = []
    for tid in tag_ids:
        tag_result = await db.execute(
            select(Tag).where(Tag.id == tid, Tag.user_id == current_user_id)
        )
        tag = tag_result.scalar_one_or_none()
        if not tag:
            raise HTTPException(status_code=404, detail=f"Tag {tid} not found")
        db.add(TaskTag(task_id=task_id, tag_id=tid))
        tags.append(tag)
    return tags

async def bench_optimized(db, task_id, tag_ids, current_user_id):
    from app.models import Tag, TaskTag
    tags = []
    if tag_ids:
        tag_result = await db.execute(
            select(Tag).where(Tag.id.in_(tag_ids), Tag.user_id == current_user_id)
        )
        tags = list(tag_result.scalars().all())
        if len(tags) != len(set(tag_ids)):
            found_ids = {t.id for t in tags}
            for tid in tag_ids:
                if tid not in found_ids:
                    raise HTTPException(status_code=404, detail=f"Tag {tid} not found")

        for tid in tag_ids:
            db.add(TaskTag(task_id=task_id, tag_id=tid))
    return tags

import sys
import os
sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))

from app.database import engine
from app.models import User, Task, Tag

async def main():
    # Setup test DB (mocked for benchmarking query logic execution speed, we will rely on test to prove correctness)
    # Testing time complexity directly O(N) queries vs 1 Query
    pass

if __name__ == "__main__":
    asyncio.run(main())
