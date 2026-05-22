import asyncio
import time
from sqlalchemy import insert, delete
from sqlalchemy.ext.asyncio import AsyncSession
from app.database import engine, async_session_factory
from app.models import SuggestedTask, SuggestionStatus, User
from app.routers.integrations import batch_suggestions
from app.schemas.integration import SuggestionBatchRequest

async def run_benchmark():
    async with async_session_factory() as db:
        # Create a dummy user
        user = User(email="bench_reject@example.com", hashed_password="pw", name="bench")
        db.add(user)
        await db.commit()
        await db.refresh(user)

        # Create N dummy suggestions
        N = 100
        suggestion_ids = []
        for i in range(N):
            s = SuggestedTask(
                user_id=user.id,
                source="benchmark",
                title=f"Task {i}",
                status=SuggestionStatus.PENDING
            )
            db.add(s)
            await db.flush()
            suggestion_ids.append(s.id)

        await db.commit()

        # Benchmark rejection
        body = SuggestionBatchRequest(accept=[], reject=suggestion_ids)

        start_time = time.perf_counter()

        # Simulate router endpoint
        result = await batch_suggestions(body=body, current_user=user, db=db)

        end_time = time.perf_counter()

        print(f"Time taken to reject {N} suggestions: {end_time - start_time:.4f} seconds")
        print(f"Rejected count: {result['rejected_count']}")

        # Cleanup
        await db.execute(delete(SuggestedTask).where(SuggestedTask.user_id == user.id))
        await db.execute(delete(User).where(User.id == user.id))
        await db.commit()

if __name__ == "__main__":
    import sys
    import os
    sys.path.append(os.path.dirname(os.path.abspath(__file__)))
    asyncio.run(run_benchmark())
