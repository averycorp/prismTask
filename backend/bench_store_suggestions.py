import asyncio
import time
import uuid

from sqlalchemy.ext.asyncio import create_async_engine, AsyncSession
from sqlalchemy.orm import sessionmaker

from app.models import Base, SuggestedTask, IntegrationSource, User
from app.services.integrations.base import store_suggestions

async def setup_db():
    engine = create_async_engine("sqlite+aiosqlite:///:memory:", echo=False)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    SessionLocal = sessionmaker(
        bind=engine, class_=AsyncSession, expire_on_commit=False
    )
    return SessionLocal

async def run_benchmark():
    SessionLocal = await setup_db()

    async with SessionLocal() as session:
        # Create user
        user = User(email="bench@test.com", hashed_password="hash", name="Bench")
        session.add(user)
        await session.commit()
        await session.refresh(user)

        user_id = user.id

        # Create a large batch of suggestions
        num_suggestions = 1000
        suggestions = []
        for i in range(num_suggestions):
            suggestions.append({
                "source_id": f"msg_{i}",
                "source_title": f"Source {i}",
                "suggested_title": f"Title {i}",
            })

        # First run (creating)
        start_time = time.time()
        created = await store_suggestions(session, user_id, suggestions, IntegrationSource.GMAIL)
        end_time = time.time()
        print(f"Creation of {num_suggestions} suggestions took {end_time - start_time:.4f} seconds")

        # Second run (duplicates)
        start_time = time.time()
        created_dups = await store_suggestions(session, user_id, suggestions, IntegrationSource.GMAIL)
        end_time = time.time()
        print(f"Checking {num_suggestions} duplicate suggestions took {end_time - start_time:.4f} seconds")

        # Another batch with mixed duplicates and new
        mixed_suggestions = []
        for i in range(num_suggestions // 2):
            mixed_suggestions.append({
                "source_id": f"msg_{i}",
                "source_title": f"Source {i}",
                "suggested_title": f"Title {i}",
            })
        for i in range(num_suggestions, num_suggestions + num_suggestions // 2):
            mixed_suggestions.append({
                "source_id": f"msg_{i}",
                "source_title": f"Source {i}",
                "suggested_title": f"Title {i}",
            })

        start_time = time.time()
        created_mixed = await store_suggestions(session, user_id, mixed_suggestions, IntegrationSource.GMAIL)
        end_time = time.time()
        print(f"Checking {num_suggestions} mixed suggestions took {end_time - start_time:.4f} seconds")

if __name__ == "__main__":
    asyncio.run(run_benchmark())
