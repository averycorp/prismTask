#!/usr/bin/env python3
"""Admin tool for issuing, listing, and revoking beta-tester unlock codes.

Usage:
    # Issue a code valid until 2026-06-15, granting Pro until 2026-09-01,
    # capped at 50 redemptions:
    python scripts/beta_codes.py issue \\
        --code EARLY-BIRD-2026 \\
        --description "Closed beta cohort 1" \\
        --valid-until 2026-06-15 \\
        --grants-pro-until 2026-09-01 \\
        --max-redemptions 50

    # Issue a perpetual code with no cap:
    python scripts/beta_codes.py issue --code FRIENDS-AND-FAMILY

    # List all codes (or only currently-active ones):
    python scripts/beta_codes.py list
    python scripts/beta_codes.py list --active-only

    # Revoke a code (existing redemptions stay valid):
    python scripts/beta_codes.py revoke --code EARLY-BIRD-2026

Requires DATABASE_URL to be set (or defaults to the dev database).
Mirrors ``scripts/set_admin.py`` for shape (argparse + asyncio +
SQLAlchemy via ``settings.DATABASE_URL``).
"""

import argparse
import asyncio
import os
import sys
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

# Allow running from repo root
sys.path.insert(0, "backend")
# Allow ``from _db import ...`` regardless of the caller's CWD.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from app.models import BetaCode  # noqa: E402

from _db import async_engine_from_settings  # noqa: E402


def _parse_date(raw: str | None) -> datetime | None:
    """Parse YYYY-MM-DD into a UTC datetime at 00:00. Returns None for None.

    Accepts the simple ISO date form because operators issue codes from
    the CLI by hand; the time-of-day precision isn't useful here.
    """
    if raw is None:
        return None
    return datetime.strptime(raw, "%Y-%m-%d").replace(tzinfo=timezone.utc)


async def _session() -> AsyncSession:
    engine = async_engine_from_settings()
    factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)
    return engine, factory


async def cmd_issue(args: argparse.Namespace) -> None:
    engine, factory = await _session()
    async with factory() as session:
        existing = await session.execute(
            select(BetaCode).where(BetaCode.code == args.code)
        )
        if existing.scalar_one_or_none() is not None:
            print(f"Error: code '{args.code}' already exists")
            sys.exit(1)

        row = BetaCode(
            code=args.code,
            description=args.description,
            valid_from=_parse_date(args.valid_from) or datetime.now(timezone.utc),
            valid_until=_parse_date(args.valid_until),
            grants_pro_until=_parse_date(args.grants_pro_until),
            max_redemptions=args.max_redemptions,
        )
        session.add(row)
        await session.commit()
        print(f"Issued '{args.code}'")
        print(f"  description    : {row.description or '-'}")
        print(f"  valid_until    : {row.valid_until.isoformat() if row.valid_until else 'open-ended'}")
        print(f"  grants_pro_until: {row.grants_pro_until.isoformat() if row.grants_pro_until else 'perpetual'}")
        print(f"  max_redemptions: {row.max_redemptions if row.max_redemptions is not None else 'unlimited'}")
    await engine.dispose()


async def cmd_list(args: argparse.Namespace) -> None:
    engine, factory = await _session()
    async with factory() as session:
        result = await session.execute(select(BetaCode).order_by(BetaCode.created_at.desc()))
        rows = list(result.scalars())
        now = datetime.now(timezone.utc)

        def _is_active(r: BetaCode) -> bool:
            if r.revoked_at is not None:
                return False
            valid_from = r.valid_from if r.valid_from.tzinfo else r.valid_from.replace(tzinfo=timezone.utc)
            if valid_from > now:
                return False
            if r.valid_until is not None:
                valid_until = r.valid_until if r.valid_until.tzinfo else r.valid_until.replace(tzinfo=timezone.utc)
                if valid_until < now:
                    return False
            if r.max_redemptions is not None and r.redemption_count >= r.max_redemptions:
                return False
            return True

        if args.active_only:
            rows = [r for r in rows if _is_active(r)]

        if not rows:
            print("(no codes)")
        for r in rows:
            cap = r.max_redemptions if r.max_redemptions is not None else "inf"
            status = "active" if _is_active(r) else ("revoked" if r.revoked_at else "inactive")
            print(f"{r.code:<32} {r.redemption_count}/{cap:<6} {status:<8} {r.description or ''}")
    await engine.dispose()


async def cmd_revoke(args: argparse.Namespace) -> None:
    engine, factory = await _session()
    async with factory() as session:
        result = await session.execute(select(BetaCode).where(BetaCode.code == args.code))
        row = result.scalar_one_or_none()
        if row is None:
            print(f"Error: code '{args.code}' does not exist")
            sys.exit(1)
        if row.revoked_at is not None:
            print(f"'{args.code}' was already revoked at {row.revoked_at.isoformat()}")
            return
        row.revoked_at = datetime.now(timezone.utc)
        await session.commit()
        print(f"Revoked '{args.code}'. Existing redemptions remain valid.")
    await engine.dispose()


def main() -> None:
    parser = argparse.ArgumentParser(description="Manage PrismTask beta-tester unlock codes")
    subparsers = parser.add_subparsers(dest="command", required=True)

    issue = subparsers.add_parser("issue", help="Create a new beta-tester unlock code")
    issue.add_argument("--code", required=True, help="The code string (max 64 chars)")
    issue.add_argument("--description", help="Admin note (e.g. 'Closed beta cohort 1')")
    issue.add_argument("--valid-from", help="YYYY-MM-DD; defaults to now")
    issue.add_argument("--valid-until", help="YYYY-MM-DD; omit for open-ended")
    issue.add_argument("--grants-pro-until", help="YYYY-MM-DD; omit for perpetual Pro")
    issue.add_argument("--max-redemptions", type=int, help="Cap on distinct redeemers; omit for unlimited")

    lst = subparsers.add_parser("list", help="List existing codes")
    lst.add_argument("--active-only", action="store_true")

    rev = subparsers.add_parser("revoke", help="Revoke a code (existing redemptions stay valid)")
    rev.add_argument("--code", required=True)

    args = parser.parse_args()
    if args.command == "issue":
        asyncio.run(cmd_issue(args))
    elif args.command == "list":
        asyncio.run(cmd_list(args))
    elif args.command == "revoke":
        asyncio.run(cmd_revoke(args))


if __name__ == "__main__":
    main()
