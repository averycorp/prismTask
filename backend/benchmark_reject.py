import asyncio
import time
import uuid

from app.models import Base, SuggestedTask, SuggestionStatus, User
from app.schemas.integration import SuggestionBatchRequest
from sqlalchemy.ext.asyncio import async_sessionmaker, AsyncSession
from tests.conftest import TestSessionLocal, engine

async def main():
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)
        await conn.run_sync(Base.metadata.create_all)

    async with TestSessionLocal() as session:
        # Create a user
        user = User(email=f"{uuid.uuid4()}@example.com", name="Test", hashed_password="pw")
        session.add(user)
        await session.commit()
        await session.refresh(user)

        # Create 1000 suggestions
        print("Inserting 1000 suggestions...")
        suggestions = [
            SuggestedTask(
                user_id=user.id,
                source="gmail",
                source_id=str(uuid.uuid4()),
                source_title="Email subject",
                suggested_title=f"Task {i}",
                status=SuggestionStatus.PENDING,
            )
            for i in range(1000)
        ]
        session.add_all(suggestions)
        await session.commit()

        # Get their IDs
        for s in suggestions:
            await session.refresh(s)

        suggestion_ids = [s.id for s in suggestions]

        from app.routers.integrations import batch_suggestions

        request = SuggestionBatchRequest(accept=[], reject=suggestion_ids)

        print("Starting benchmark...")
        start = time.time()

        await batch_suggestions(body=request, current_user=user, db=session)

        end = time.time()
        print(f"Time taken: {end - start:.4f} seconds")

if __name__ == "__main__":
    asyncio.run(main())
