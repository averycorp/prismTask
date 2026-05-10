#!/usr/bin/env python3
"""One-time script to grant admin privileges to a user.

Usage:
    # By Firebase UID:
    python scripts/set_admin.py --firebase-uid <FIREBASE_UID>

    # By email address:
    python scripts/set_admin.py --email <EMAIL>

    # By database user ID:
    python scripts/set_admin.py --user-id <ID>

Requires DATABASE_URL to be set (or defaults to the dev database).
"""

import argparse
import asyncio
import os
import sys

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

# Allow running from repo root
sys.path.insert(0, "backend")
# Allow ``from _db import ...`` regardless of the caller's CWD.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from app.models import User  # noqa: E402

from _db import async_engine_from_settings  # noqa: E402


async def set_admin(
    *,
    firebase_uid: str | None = None,
    email: str | None = None,
    user_id: int | None = None,
) -> None:
    engine = async_engine_from_settings()
    session_factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)

    async with session_factory() as session:
        if firebase_uid:
            q = select(User).where(User.firebase_uid == firebase_uid)
        elif email:
            q = select(User).where(User.email == email)
        elif user_id is not None:
            q = select(User).where(User.id == user_id)
        else:
            print("Error: provide --firebase-uid, --email, or --user-id")
            sys.exit(1)

        result = await session.execute(q)
        user = result.scalar_one_or_none()

        if not user:
            print("Error: user not found")
            sys.exit(1)

        user.is_admin = True
        await session.commit()

        print(f"✓ User '{user.name}' (id={user.id}, email={user.email}) is now an admin.")

    await engine.dispose()


def main() -> None:
    parser = argparse.ArgumentParser(description="Grant admin privileges to a PrismTask user")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--firebase-uid", help="Firebase UID of the user")
    group.add_argument("--email", help="Email address of the user")
    group.add_argument("--user-id", type=int, help="Database user ID")
    args = parser.parse_args()

    asyncio.run(
        set_admin(
            firebase_uid=args.firebase_uid,
            email=args.email,
            user_id=args.user_id,
        )
    )


if __name__ == "__main__":
    main()
