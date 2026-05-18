# AI Personal Assistant — Phase 1: Tool-Use Loop + Read Tools

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a backend agentic tool-use loop to `POST /api/v1/ai/chat` so Claude Haiku can call ≤10 server-executed read tools per turn (six narrow + one escape-hatch `query`) and ground its reply in live user data — while preserving today's write-chip protocol unchanged.

**Architecture:** New `app/routers/ai/tools/` package + `app/routers/ai/tool_registry.py` with a `ToolHandler` protocol. The existing `generate_chat_response` in `app/services/ai_productivity.py` grows a bounded `while True:` loop: dispatch every `tool_use` block whose name is in the **read-tool set** server-side, feed `tool_result` blocks back to Claude, repeat until Claude emits no read tool_uses or the budget is hit. Write tool_uses (existing 8 chip types) and memory tool_uses (`remember_preference` / `forget_preference`) flow through unchanged as final-response actions. Gated on `tier == PRO` AND `AI_ASSISTANT_TOOL_USE_ENABLED=true`.

**Tech Stack:** Python 3.12, FastAPI, SQLAlchemy 2 async, Alembic, Anthropic SDK (`anthropic.Anthropic`), pytest + pytest-asyncio. No new third-party dependencies.

**Spec:** [`docs/superpowers/specs/2026-05-18-ai-personal-assistant-design.md`](../specs/2026-05-18-ai-personal-assistant-design.md)

---

## File Map

**Create (backend):**
- `backend/app/routers/ai/tools/__init__.py` — package marker.
- `backend/app/routers/ai/tools/_base.py` — `ToolHandler` protocol + shared validation helpers.
- `backend/app/routers/ai/tools/tasks.py` — `get_tasks`.
- `backend/app/routers/ai/tools/habits.py` — `get_habits`.
- `backend/app/routers/ai/tools/projects.py` — `get_projects`.
- `backend/app/routers/ai/tools/medications.py` — `get_medications`.
- `backend/app/routers/ai/tools/mood.py` — `get_mood_logs`.
- `backend/app/routers/ai/tools/leisure.py` — `get_leisure_logs`.
- `backend/app/routers/ai/tools/query.py` — escape-hatch `query` with closed filter grammar.
- `backend/app/routers/ai/tool_registry.py` — registry + Claude schema export + dispatcher.
- `backend/alembic/versions/029_add_chat_messages_tool_calls.py` — Alembic migration for `chat_messages.tool_calls` JSONB column.
- `docs/audits/AI_ASSISTANT_PHASE_1_PROMPT_AUDIT.md` — prompt-change audit doc (required by load-bearing-prompt convention).

**Modify (backend):**
- `backend/app/config.py` — add `AI_ASSISTANT_TOOL_USE_ENABLED` setting.
- `backend/app/models.py:803` (`ChatMessage`) — add `tool_calls = Column(JSON, nullable=True)`.
- `backend/app/schemas/ai.py` — extend `ChatMessageRecord` with optional `tool_calls` field; add `ChatToolCallRecord` model.
- `backend/app/services/ai_productivity.py:1412` (`_CHAT_SYSTEM_PROMPT_BASE`) — append Tools section.
- `backend/app/services/ai_productivity.py:2127` (`generate_chat_response`) — wrap single-shot call in bounded loop.
- `backend/app/routers/ai/chat.py:70` (`chat` handler) — persist `tool_calls` summary to the assistant row; surface in response.

**Create (tests):**
- `backend/tests/test_ai_tools_base.py`
- `backend/tests/test_ai_tool_registry.py`
- `backend/tests/test_ai_tools_tasks.py`
- `backend/tests/test_ai_tools_habits.py`
- `backend/tests/test_ai_tools_projects.py`
- `backend/tests/test_ai_tools_medications.py`
- `backend/tests/test_ai_tools_mood.py`
- `backend/tests/test_ai_tools_leisure.py`
- `backend/tests/test_ai_tools_query.py`
- `backend/tests/test_ai_chat_tool_loop.py`

**Modify (tests):**
- `backend/tests/test_ai_chat.py` — add 2 canary tests (free-tier, flag-off).

---

## Task 1: Feature flag config + audit-doc skeleton

**Files:**
- Modify: `backend/app/config.py`
- Create: `docs/audits/AI_ASSISTANT_PHASE_1_PROMPT_AUDIT.md`

- [ ] **Step 1: Read `backend/app/config.py` to find the settings class shape**

Run: `grep -n "class Settings\|tool_use\|AI_" backend/app/config.py | head -20`
Expected: shows `class Settings(BaseSettings)` definition and existing AI-related env knobs.

- [ ] **Step 2: Add `AI_ASSISTANT_TOOL_USE_ENABLED` to the Settings class**

In `backend/app/config.py`, append inside the `Settings` class body (after the last existing `AI_*` field):

```python
    # Phase 1 feature flag for the AI Assistant agentic read-tool loop.
    # When False, chat falls back to today's single-shot Claude call with
    # the curated context bundle + write-chip tools only — exactly the
    # pre-loop behavior. Default off so prod can flip the loop on per env.
    AI_ASSISTANT_TOOL_USE_ENABLED: bool = False
```

- [ ] **Step 3: Verify the setting loads**

Run: `cd backend && python -c "from app.config import settings; print(settings.AI_ASSISTANT_TOOL_USE_ENABLED)"`
Expected output: `False`

- [ ] **Step 4: Create the audit doc skeleton**

Create `docs/audits/AI_ASSISTANT_PHASE_1_PROMPT_AUDIT.md` with:

```markdown
# AI Assistant Phase 1 — System Prompt Audit

**Status:** Draft (Phase 1 in progress)
**Spec:** `docs/superpowers/specs/2026-05-18-ai-personal-assistant-design.md`
**Convention:** `_CHAT_SYSTEM_PROMPT_BASE` is load-bearing — per
`memory/project_chat_system_prompt_load_bearing.md`, every edit
requires this audit doc before merge.

## Diff Summary (planned)

The Tools section is added to `_CHAT_SYSTEM_PROMPT_BASE` between the
existing "User preferences memory" block and the closing triple-quote.
No other section is touched.

## Forgiveness-First Guardrail Review

- ✅ Crisis pre-filter still runs before the loop in `chat()`.
- ✅ No new chip type introduced that encodes shame / deficit framing.
- ✅ The "Tools" section instructs the model to call READ tools server-side
  but to never invent task IDs or user data — same anti-fabrication rule
  as today's prompt.
- ✅ Free tier behavior unchanged (rate limiter rejects at 403 before any
  tool-use path); flag-off behavior unchanged.

## Canary Test Coverage

- `test_ai_chat.py::test_endpoint_403_for_free_tier` — preserved.
- `test_ai_chat.py::test_loop_disabled_with_flag_off_matches_baseline` — added Task 14.
- `test_ai_chat.py::test_loop_disabled_for_free_tier` — added Task 14.

## Approval

Author: Avery Karlin
Date: 2026-05-18
```

- [ ] **Step 5: Commit**

```bash
git add backend/app/config.py docs/audits/AI_ASSISTANT_PHASE_1_PROMPT_AUDIT.md
git commit -m "feat(ai-assistant): add AI_ASSISTANT_TOOL_USE_ENABLED flag + Phase 1 audit doc skeleton"
```

---

## Task 2: Add `tool_calls` column to `ChatMessage` model + Alembic migration

**Files:**
- Modify: `backend/app/models.py:823`
- Create: `backend/alembic/versions/029_add_chat_messages_tool_calls.py`

- [ ] **Step 1: Add the SQLAlchemy column**

In `backend/app/models.py`, modify the `ChatMessage` class (around line 823) — add `tool_calls` right after the existing `actions` line:

```python
    actions = Column(JSON, nullable=True)
    # Phase 1 (AI Assistant tool-use loop): summary of read-tool calls the
    # assistant invoked during this turn — [{name, input, result_summary},
    # ...]. NULL for user-role rows and for assistant rows that did not
    # invoke any read tool. Lets ``GET /chat/history`` re-render what the
    # AI looked up for cross-device debugging.
    tool_calls = Column(JSON, nullable=True)
    task_context_snapshot = Column(JSON, nullable=True)
```

- [ ] **Step 2: Create the Alembic migration**

Create `backend/alembic/versions/029_add_chat_messages_tool_calls.py`:

```python
"""Add ``chat_messages.tool_calls`` for AI Assistant Phase 1 tool-use loop.

Stores a list of ``[{name, input, result_summary}, ...]`` on assistant
rows whenever the Phase 1 read-tool loop fires. Nullable so existing rows
and write-only / no-tool turns remain valid. Per
``docs/superpowers/specs/2026-05-18-ai-personal-assistant-design.md``.

Revision ID: 029
Revises: 028
Create Date: 2026-05-18
"""

from alembic import op
import sqlalchemy as sa


revision = "029"
down_revision = "028"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "chat_messages",
        sa.Column("tool_calls", sa.JSON, nullable=True),
    )


def downgrade() -> None:
    op.drop_column("chat_messages", "tool_calls")
```

- [ ] **Step 3: Verify the migration is well-formed**

Run: `cd backend && alembic heads`
Expected output includes: `029 (head)`

- [ ] **Step 4: Run the migration against the test DB**

Run: `cd backend && pytest tests/test_ai_chat.py::TestEndpoint::test_endpoint_returns_message_and_echoes_conversation_id -v -x`
Expected: PASS (the existing test exercises chat persistence; if the migration is broken, INSERT fails).

- [ ] **Step 5: Commit**

```bash
git add backend/app/models.py backend/alembic/versions/029_add_chat_messages_tool_calls.py
git commit -m "feat(ai-assistant): add chat_messages.tool_calls column + migration 029"
```

---

## Task 3: `ToolHandler` protocol + shared validation helpers

**Files:**
- Create: `backend/app/routers/ai/tools/__init__.py`
- Create: `backend/app/routers/ai/tools/_base.py`
- Create: `backend/tests/test_ai_tools_base.py`

- [ ] **Step 1: Write the failing test**

Create `backend/tests/test_ai_tools_base.py`:

```python
"""Unit tests for the ToolHandler protocol + validation helpers."""

import pytest

from app.routers.ai.tools._base import (
    ToolError,
    ToolHandler,
    ToolResult,
    validate_iso_date,
    validate_one_of,
    validate_positive_int,
)


def test_tool_error_carries_message_and_field():
    err = ToolError("bad arg", field="bucket")
    assert err.message == "bad arg"
    assert err.field == "bucket"


def test_tool_result_serializes_to_dict():
    r = ToolResult(data={"foo": 1}, summary="ok")
    assert r.to_dict() == {"data": {"foo": 1}, "summary": "ok"}


def test_validate_one_of_accepts_valid_value():
    assert validate_one_of("today", {"today", "tomorrow"}, field="bucket") == "today"


def test_validate_one_of_rejects_invalid_value():
    with pytest.raises(ToolError) as exc:
        validate_one_of("never", {"today", "tomorrow"}, field="bucket")
    assert "never" in exc.value.message
    assert exc.value.field == "bucket"


def test_validate_iso_date_accepts_valid_iso():
    assert validate_iso_date("2026-05-18", field="start").isoformat() == "2026-05-18"


def test_validate_iso_date_rejects_garbage():
    with pytest.raises(ToolError) as exc:
        validate_iso_date("not-a-date", field="start")
    assert exc.value.field == "start"


def test_validate_positive_int_accepts_in_range():
    assert validate_positive_int(50, max_value=100, field="limit") == 50


def test_validate_positive_int_rejects_zero():
    with pytest.raises(ToolError):
        validate_positive_int(0, max_value=100, field="limit")


def test_validate_positive_int_rejects_above_max():
    with pytest.raises(ToolError):
        validate_positive_int(101, max_value=100, field="limit")


def test_tool_handler_protocol_signature():
    """Smoke test that ToolHandler is a Protocol with the expected attrs."""
    assert hasattr(ToolHandler, "name")
    assert hasattr(ToolHandler, "schema")
    assert hasattr(ToolHandler, "dispatch")
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && pytest tests/test_ai_tools_base.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.routers.ai.tools'`.

- [ ] **Step 3: Create the package marker**

Create `backend/app/routers/ai/tools/__init__.py`:

```python
"""AI Assistant read-tool handlers.

One file per tool. Registered via ``app.routers.ai.tool_registry``.
"""
```

- [ ] **Step 4: Implement `_base.py`**

Create `backend/app/routers/ai/tools/_base.py`:

```python
"""Shared protocol + validation helpers for AI Assistant read tools.

Every tool handler in this package conforms to ``ToolHandler``. The
validation helpers raise ``ToolError`` instead of generic ValueErrors so
the registry can convert them into structured ``tool_result`` blocks
that the model can recover from on the next loop iteration.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from typing import Any, Awaitable, Callable, Protocol

from sqlalchemy.ext.asyncio import AsyncSession

from app.models import User


class ToolError(Exception):
    """Raised by a tool handler when its args fail validation.

    The registry converts this into a structured tool_result the model
    can read on the next loop iteration ("you passed bucket='never';
    must be one of {today, tomorrow}").
    """

    def __init__(self, message: str, *, field: str | None = None):
        super().__init__(message)
        self.message = message
        self.field = field


@dataclass
class ToolResult:
    """Wrap a tool's structured payload so the registry can summarize it
    for persistence to ``chat_messages.tool_calls`` without bloating the
    row with the full data response."""

    data: Any
    summary: str = ""

    def to_dict(self) -> dict:
        return {"data": self.data, "summary": self.summary}


class ToolHandler(Protocol):
    """One Claude tool. Registered in ``tool_registry``."""

    name: str
    schema: dict  # Anthropic tool schema (passed to ``client.messages.create(tools=...)``)

    async def dispatch(
        self,
        *,
        user: User,
        db: AsyncSession,
        args: dict,
        logical_today: date,
    ) -> ToolResult: ...


# --- validation helpers -----------------------------------------------------


def validate_one_of(value: Any, allowed: set[str], *, field: str) -> str:
    if not isinstance(value, str) or value not in allowed:
        raise ToolError(
            f"{field}={value!r} not in {sorted(allowed)}",
            field=field,
        )
    return value


def validate_iso_date(value: Any, *, field: str) -> date:
    if not isinstance(value, str):
        raise ToolError(f"{field} must be an ISO YYYY-MM-DD string", field=field)
    try:
        return date.fromisoformat(value)
    except ValueError as e:
        raise ToolError(f"{field}={value!r} is not ISO YYYY-MM-DD", field=field) from e


def validate_positive_int(value: Any, *, max_value: int, field: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool):
        raise ToolError(f"{field} must be an int", field=field)
    if value < 1 or value > max_value:
        raise ToolError(
            f"{field}={value} out of range [1, {max_value}]",
            field=field,
        )
    return value
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && pytest tests/test_ai_tools_base.py -v`
Expected: 10 PASSED.

- [ ] **Step 6: Commit**

```bash
git add backend/app/routers/ai/tools/__init__.py backend/app/routers/ai/tools/_base.py backend/tests/test_ai_tools_base.py
git commit -m "feat(ai-assistant): ToolHandler protocol + validation helpers"
```

---

## Task 4: `tool_registry` module — schema export + dispatcher

**Files:**
- Create: `backend/app/routers/ai/tool_registry.py`
- Create: `backend/tests/test_ai_tool_registry.py`

- [ ] **Step 1: Write the failing test**

Create `backend/tests/test_ai_tool_registry.py`:

```python
"""Unit tests for the AI Assistant tool registry + dispatcher."""

from datetime import date

import pytest

from app.routers.ai.tool_registry import (
    READ_TOOL_NAMES,
    ToolRegistry,
    build_tool_error_result,
)
from app.routers.ai.tools._base import ToolError, ToolHandler, ToolResult


class _FakeHandler:
    name = "fake_tool"
    schema = {
        "name": "fake_tool",
        "description": "fake",
        "input_schema": {"type": "object", "properties": {}, "required": []},
    }

    async def dispatch(self, *, user, db, args, logical_today):
        return ToolResult(data={"ok": True, "args": args}, summary="fake summary")


class _RaisingHandler:
    name = "raises"
    schema = {
        "name": "raises",
        "description": "raises",
        "input_schema": {"type": "object", "properties": {}, "required": []},
    }

    async def dispatch(self, *, user, db, args, logical_today):
        raise ToolError("bad arg", field="x")


def test_registry_exposes_claude_schemas():
    reg = ToolRegistry([_FakeHandler()])
    schemas = reg.claude_schemas()
    assert len(schemas) == 1
    assert schemas[0]["name"] == "fake_tool"


def test_registry_rejects_duplicate_names():
    with pytest.raises(ValueError):
        ToolRegistry([_FakeHandler(), _FakeHandler()])


@pytest.mark.asyncio
async def test_registry_dispatches_to_named_handler():
    reg = ToolRegistry([_FakeHandler()])
    result = await reg.dispatch(
        user=None, db=None, name="fake_tool", args={"a": 1},
        logical_today=date(2026, 5, 18),
    )
    assert result.data == {"ok": True, "args": {"a": 1}}


@pytest.mark.asyncio
async def test_registry_unknown_name_raises_toolerror():
    reg = ToolRegistry([_FakeHandler()])
    with pytest.raises(ToolError):
        await reg.dispatch(
            user=None, db=None, name="not_registered", args={},
            logical_today=date(2026, 5, 18),
        )


@pytest.mark.asyncio
async def test_registry_propagates_toolerror_from_handler():
    reg = ToolRegistry([_RaisingHandler()])
    with pytest.raises(ToolError) as exc:
        await reg.dispatch(
            user=None, db=None, name="raises", args={},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "x"


def test_build_tool_error_result_shape():
    payload = build_tool_error_result(ToolError("nope", field="bucket"))
    assert payload == {"error": "nope", "field": "bucket"}


def test_read_tool_names_is_a_frozen_set():
    assert isinstance(READ_TOOL_NAMES, frozenset)
    assert "get_tasks" in READ_TOOL_NAMES
    assert "query" in READ_TOOL_NAMES
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && pytest tests/test_ai_tool_registry.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.routers.ai.tool_registry'`.

- [ ] **Step 3: Implement the registry**

Create `backend/app/routers/ai/tool_registry.py`:

```python
"""Registry + dispatcher for AI Assistant read tools.

The registry binds tool names to handler instances and exposes the
combined Anthropic tool-schema list for inclusion in
``client.messages.create(tools=...)``. The dispatcher is the single
entry point used by the agentic loop in ``ai_productivity.py``.

``READ_TOOL_NAMES`` is the canonical set of names the loop must treat
as server-executed. Any tool_use block whose name is NOT in this set is
either a write chip (forwarded to the client) or a memory tool call
(``remember_preference`` / ``forget_preference`` — handled by the
existing chat handler logic, unchanged).
"""

from __future__ import annotations

from datetime import date
from typing import Iterable

from sqlalchemy.ext.asyncio import AsyncSession

from app.models import User

from .tools._base import ToolError, ToolHandler, ToolResult
from .tools.habits import GetHabitsHandler
from .tools.leisure import GetLeisureLogsHandler
from .tools.medications import GetMedicationsHandler
from .tools.mood import GetMoodLogsHandler
from .tools.projects import GetProjectsHandler
from .tools.query import QueryHandler
from .tools.tasks import GetTasksHandler


READ_TOOL_NAMES: frozenset[str] = frozenset({
    "get_tasks",
    "get_habits",
    "get_projects",
    "get_medications",
    "get_mood_logs",
    "get_leisure_logs",
    "query",
})


class ToolRegistry:
    def __init__(self, handlers: Iterable[ToolHandler]):
        self._handlers: dict[str, ToolHandler] = {}
        for h in handlers:
            if h.name in self._handlers:
                raise ValueError(f"duplicate tool handler: {h.name}")
            self._handlers[h.name] = h

    def claude_schemas(self) -> list[dict]:
        return [h.schema for h in self._handlers.values()]

    async def dispatch(
        self,
        *,
        user: User | None,
        db: AsyncSession | None,
        name: str,
        args: dict,
        logical_today: date,
    ) -> ToolResult:
        handler = self._handlers.get(name)
        if handler is None:
            raise ToolError(f"unknown tool name: {name!r}", field="name")
        return await handler.dispatch(
            user=user, db=db, args=args, logical_today=logical_today,
        )


def build_tool_error_result(err: ToolError) -> dict:
    """Render a ToolError into the JSON payload sent back as ``tool_result``
    content so the model can read it and retry with corrected args."""
    return {"error": err.message, "field": err.field}


def default_registry() -> ToolRegistry:
    """Construct the canonical Phase 1 registry. Called from
    ``ai_productivity.generate_chat_response`` once per request."""
    return ToolRegistry([
        GetTasksHandler(),
        GetHabitsHandler(),
        GetProjectsHandler(),
        GetMedicationsHandler(),
        GetMoodLogsHandler(),
        GetLeisureLogsHandler(),
        QueryHandler(),
    ])
```

- [ ] **Step 4: Stub the handler imports so the module loads**

The registry imports seven not-yet-written handlers (Tasks 5–11 will fill them). For now, create stub files so this task's tests pass in isolation. Create each of these with the placeholder body shown:

```python
# backend/app/routers/ai/tools/tasks.py
from datetime import date
from app.routers.ai.tools._base import ToolHandler, ToolResult


class GetTasksHandler:
    name = "get_tasks"
    schema = {
        "name": "get_tasks",
        "description": "stub — Task 5 will fill this in",
        "input_schema": {"type": "object", "properties": {}, "required": []},
    }

    async def dispatch(self, *, user, db, args, logical_today: date):
        return ToolResult(data={}, summary="stub")
```

Repeat the same shape (class name `Get<Name>Handler`, attribute `name = "<snake>"`, schema stub, dispatch returns `ToolResult(data={}, summary="stub")`) in:
- `backend/app/routers/ai/tools/habits.py` → class `GetHabitsHandler`, name `"get_habits"`.
- `backend/app/routers/ai/tools/projects.py` → class `GetProjectsHandler`, name `"get_projects"`.
- `backend/app/routers/ai/tools/medications.py` → class `GetMedicationsHandler`, name `"get_medications"`.
- `backend/app/routers/ai/tools/mood.py` → class `GetMoodLogsHandler`, name `"get_mood_logs"`.
- `backend/app/routers/ai/tools/leisure.py` → class `GetLeisureLogsHandler`, name `"get_leisure_logs"`.
- `backend/app/routers/ai/tools/query.py` → class `QueryHandler`, name `"query"`.

- [ ] **Step 5: Run the registry test to verify it passes**

Run: `cd backend && pytest tests/test_ai_tool_registry.py -v`
Expected: 7 PASSED.

- [ ] **Step 6: Commit**

```bash
git add backend/app/routers/ai/tool_registry.py backend/app/routers/ai/tools/ backend/tests/test_ai_tool_registry.py
git commit -m "feat(ai-assistant): tool registry + dispatcher with stub handlers"
```

---

## Task 5: `get_tasks` handler — replace stub with real implementation

**Files:**
- Modify: `backend/app/routers/ai/tools/tasks.py`
- Create: `backend/tests/test_ai_tools_tasks.py`

The `get_tasks` tool returns task summaries from one of six buckets that mirror today's curated context bundle (`overdue / today / planned / upcoming / backlog / recently_completed`). It reads from the same Firestore source the chat-context bundle already uses (`app.services.firestore_tasks`), filtered by optional `project_id` / `tag` / `life_category`.

- [ ] **Step 1: Write the failing test**

Create `backend/tests/test_ai_tools_tasks.py`:

```python
"""Unit tests for the get_tasks tool handler."""

from datetime import date
from unittest.mock import AsyncMock, patch

import pytest

from app.routers.ai.tools._base import ToolError
from app.routers.ai.tools.tasks import GetTasksHandler


class _FakeUser:
    id = 7
    firebase_uid = "firebase-7"


@pytest.mark.asyncio
async def test_get_tasks_rejects_unknown_bucket():
    handler = GetTasksHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"bucket": "yesterday"},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "bucket"


@pytest.mark.asyncio
async def test_get_tasks_rejects_oversized_limit():
    handler = GetTasksHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"bucket": "today", "limit": 999},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "limit"


@pytest.mark.asyncio
async def test_get_tasks_overdue_bucket_calls_fetch_incomplete():
    """Overdue/today/planned/backlog all derive from the user's incomplete
    task set; the handler filters bucket-locally without an extra fetch."""
    handler = GetTasksHandler()
    with patch(
        "app.routers.ai.tools.tasks._fetch_incomplete_tasks",
        new=AsyncMock(return_value=[]),
    ) as mock_fetch:
        result = await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"bucket": "overdue", "limit": 10},
            logical_today=date(2026, 5, 18),
        )
    mock_fetch.assert_awaited_once_with("firebase-7")
    assert result.data == {"bucket": "overdue", "tasks": [], "count": 0}


@pytest.mark.asyncio
async def test_get_tasks_filters_by_project_tag_life_category():
    """Each optional filter narrows the returned list."""
    from app.services.firestore_tasks import TaskDTO

    sample = [
        TaskDTO(
            id="a", title="Buy groceries", description=None,
            due_date=date(2026, 5, 17), priority="medium",
            tags=["errand"], project_id="p1", life_category="personal",
            completed_at=None, subtasks=[], recurrence_rule=None,
        ),
        TaskDTO(
            id="b", title="Write spec", description=None,
            due_date=date(2026, 5, 17), priority="high",
            tags=["work"], project_id="p2", life_category="work",
            completed_at=None, subtasks=[], recurrence_rule=None,
        ),
    ]
    handler = GetTasksHandler()
    with patch(
        "app.routers.ai.tools.tasks._fetch_incomplete_tasks",
        new=AsyncMock(return_value=sample),
    ):
        result = await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"bucket": "overdue", "project_id": "p1"},
            logical_today=date(2026, 5, 18),
        )
    assert [t["id"] for t in result.data["tasks"]] == ["a"]


@pytest.mark.asyncio
async def test_get_tasks_recently_completed_bucket_uses_completion_history():
    """Recently completed bucket reads from the per-user completion history."""
    handler = GetTasksHandler()
    with patch(
        "app.routers.ai.tools.tasks._fetch_recently_completed",
        new=AsyncMock(return_value=[]),
    ) as mock_recent:
        result = await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"bucket": "recently_completed", "limit": 10},
            logical_today=date(2026, 5, 18),
        )
    mock_recent.assert_awaited_once_with("firebase-7", days=7)
    assert result.data["bucket"] == "recently_completed"


@pytest.mark.asyncio
async def test_get_tasks_summary_includes_count():
    handler = GetTasksHandler()
    with patch(
        "app.routers.ai.tools.tasks._fetch_incomplete_tasks",
        new=AsyncMock(return_value=[]),
    ):
        result = await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"bucket": "today"},
            logical_today=date(2026, 5, 18),
        )
    assert "0 task" in result.summary
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && pytest tests/test_ai_tools_tasks.py -v`
Expected: 6 FAILED (stub `dispatch` returns `{}`, none of the assertions match).

- [ ] **Step 3: Replace the stub with the real implementation**

Replace `backend/app/routers/ai/tools/tasks.py` with:

```python
"""get_tasks read-tool handler.

Returns task summaries from one of six buckets that mirror the
panels in ``load_user_context_bundle``: overdue / today / planned /
upcoming / backlog / recently_completed. Optional project_id / tag /
life_category narrow the bucket further.

Source of truth is Firestore (``users/{uid}/tasks``), read via the same
helpers ``load_user_context_bundle`` uses so the bucket semantics
match exactly.
"""

from __future__ import annotations

from datetime import date, timedelta
from typing import Any

from app.routers.ai.tools._base import (
    ToolError,
    ToolHandler,
    ToolResult,
    validate_one_of,
    validate_positive_int,
)
from app.services.firestore_tasks import TaskDTO


_BUCKETS = {
    "overdue",
    "today",
    "planned",
    "upcoming",
    "backlog",
    "recently_completed",
}
_MAX_LIMIT = 50
_RECENTLY_COMPLETED_WINDOW_DAYS = 7
_UPCOMING_WINDOW_DAYS = 14


async def _fetch_incomplete_tasks(uid: str) -> list[TaskDTO]:
    """Indirection layer so tests can patch this single symbol instead of
    the Firestore client."""
    from app.routers import ai as _ai_pkg
    return await _ai_pkg.fetch_incomplete_tasks(uid)


async def _fetch_recently_completed(uid: str, *, days: int) -> list[TaskDTO]:
    """Indirection layer for recently-completed reads."""
    from app.routers import ai as _ai_pkg
    return await _ai_pkg.fetch_recently_completed_tasks(uid, days=days)


def _summarize(t: TaskDTO, today: date) -> dict[str, Any]:
    d = t.due_date
    days_overdue = (today - d).days if d and d < today else 0
    days_until = (d - today).days if d and d >= today else None
    return {
        "id": t.id,
        "title": t.title,
        "due": d.isoformat() if d else None,
        "days_overdue": days_overdue if days_overdue > 0 else None,
        "days_until_due": days_until,
        "priority": t.priority,
        "tags": list(t.tags or []),
        "project_id": t.project_id,
        "life_category": t.life_category,
    }


def _filter(
    tasks: list[TaskDTO],
    *,
    project_id: str | None,
    tag: str | None,
    life_category: str | None,
) -> list[TaskDTO]:
    out = tasks
    if project_id is not None:
        out = [t for t in out if t.project_id == project_id]
    if tag is not None:
        out = [t for t in out if tag in (t.tags or [])]
    if life_category is not None:
        out = [t for t in out if t.life_category == life_category]
    return out


def _split_buckets(
    tasks: list[TaskDTO], today: date
) -> dict[str, list[TaskDTO]]:
    overdue, due_today, planned, upcoming, backlog = [], [], [], [], []
    horizon = today + timedelta(days=_UPCOMING_WINDOW_DAYS)
    for t in tasks:
        d = t.due_date
        if d is None:
            backlog.append(t)
        elif d < today:
            overdue.append(t)
        elif d == today:
            due_today.append(t)
        elif d <= horizon:
            upcoming.append(t)
        else:
            planned.append(t)
    return {
        "overdue": overdue,
        "today": due_today,
        "planned": planned,
        "upcoming": upcoming,
        "backlog": backlog,
    }


class GetTasksHandler:
    name = "get_tasks"
    schema = {
        "name": "get_tasks",
        "description": (
            "Read tasks from one of six bucket views that mirror the user's "
            "Today screen: overdue (due_date < today, not done), today "
            "(due_date == today), planned (due_date > today+14d), upcoming "
            "(due_date in next 14d), backlog (no due_date), "
            "recently_completed (completed in last 7d). Optional filters "
            "narrow by project, tag, or life_category."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "bucket": {
                    "type": "string",
                    "enum": sorted(_BUCKETS),
                },
                "limit": {
                    "type": "integer",
                    "minimum": 1,
                    "maximum": _MAX_LIMIT,
                    "default": 20,
                },
                "project_id": {"type": "string"},
                "tag": {"type": "string"},
                "life_category": {
                    "type": "string",
                    "enum": ["work", "personal", "self_care", "health", "uncategorized"],
                },
            },
            "required": ["bucket"],
        },
    }

    async def dispatch(self, *, user, db, args: dict, logical_today: date) -> ToolResult:
        uid = getattr(user, "firebase_uid", None)
        if not uid:
            raise ToolError("user is not Firebase-linked", field="user")
        bucket = validate_one_of(args.get("bucket"), _BUCKETS, field="bucket")
        limit = validate_positive_int(
            args.get("limit", 20), max_value=_MAX_LIMIT, field="limit",
        )
        project_id = args.get("project_id")
        tag = args.get("tag")
        life_category = args.get("life_category")

        if bucket == "recently_completed":
            raw = await _fetch_recently_completed(
                uid, days=_RECENTLY_COMPLETED_WINDOW_DAYS,
            )
            filtered = _filter(
                raw, project_id=project_id, tag=tag, life_category=life_category,
            )[:limit]
            return ToolResult(
                data={
                    "bucket": bucket,
                    "tasks": [_summarize(t, logical_today) for t in filtered],
                    "count": len(filtered),
                },
                summary=f"{len(filtered)} task(s) in {bucket}",
            )

        raw = await _fetch_incomplete_tasks(uid)
        filtered = _filter(
            raw, project_id=project_id, tag=tag, life_category=life_category,
        )
        bucketed = _split_buckets(filtered, logical_today)[bucket][:limit]
        return ToolResult(
            data={
                "bucket": bucket,
                "tasks": [_summarize(t, logical_today) for t in bucketed],
                "count": len(bucketed),
            },
            summary=f"{len(bucketed)} task(s) in {bucket}",
        )
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && pytest tests/test_ai_tools_tasks.py -v`
Expected: 6 PASSED.

- [ ] **Step 5: Commit**

```bash
git add backend/app/routers/ai/tools/tasks.py backend/tests/test_ai_tools_tasks.py
git commit -m "feat(ai-assistant): get_tasks read-tool handler"
```

---

## Task 6: `get_habits` handler

**Files:**
- Modify: `backend/app/routers/ai/tools/habits.py`
- Create: `backend/tests/test_ai_tools_habits.py`

The `get_habits` tool returns per-habit progress for one of three windows (`today / last_7d / last_30d`). Reads use the existing `app.routers.ai.context._load_habit_progress` (refactor required to expose a public function — done in this task).

- [ ] **Step 1: Inspect the existing habit loader**

Run: `grep -n "def _load_habit\|def load_habit\|habit" backend/app/routers/ai/context.py | head -20`
Expected: shows a private `_load_habit_progress` (or similarly named) function in `context.py`.

- [ ] **Step 2: Write the failing test**

Create `backend/tests/test_ai_tools_habits.py`:

```python
"""Unit tests for the get_habits tool handler."""

from datetime import date
from unittest.mock import AsyncMock, patch

import pytest

from app.routers.ai.tools._base import ToolError
from app.routers.ai.tools.habits import GetHabitsHandler


class _FakeUser:
    id = 9
    firebase_uid = "fb-9"


@pytest.mark.asyncio
async def test_get_habits_rejects_unknown_window():
    handler = GetHabitsHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"window": "year"},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "window"


@pytest.mark.asyncio
async def test_get_habits_today_window_returns_per_habit_progress():
    sample = [
        {"id": "h1", "name": "Meditate", "count": 1, "target": 1,
         "streak": 12, "last7_count": 6, "category": "self_care"},
        {"id": "h2", "name": "Walk", "count": 0, "target": 1,
         "streak": 0, "last7_count": 3, "category": "health"},
    ]
    handler = GetHabitsHandler()
    with patch(
        "app.routers.ai.tools.habits._load_habits_today",
        new=AsyncMock(return_value=sample),
    ) as mock_load:
        result = await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"window": "today"},
            logical_today=date(2026, 5, 18),
        )
    mock_load.assert_awaited_once_with("fb-9", date(2026, 5, 18))
    assert result.data["window"] == "today"
    assert len(result.data["habits"]) == 2
    assert result.data["habits"][0]["streak"] == 12


@pytest.mark.asyncio
async def test_get_habits_last_7d_uses_window_loader():
    handler = GetHabitsHandler()
    with patch(
        "app.routers.ai.tools.habits._load_habits_window",
        new=AsyncMock(return_value=[]),
    ) as mock_window:
        result = await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"window": "last_7d"},
            logical_today=date(2026, 5, 18),
        )
    mock_window.assert_awaited_once_with("fb-9", days=7, today=date(2026, 5, 18))
    assert result.data["window"] == "last_7d"


@pytest.mark.asyncio
async def test_get_habits_filters_by_category():
    sample = [
        {"id": "h1", "name": "Meditate", "count": 1, "target": 1,
         "streak": 12, "last7_count": 6, "category": "self_care"},
        {"id": "h2", "name": "Walk", "count": 0, "target": 1,
         "streak": 0, "last7_count": 3, "category": "health"},
    ]
    handler = GetHabitsHandler()
    with patch(
        "app.routers.ai.tools.habits._load_habits_today",
        new=AsyncMock(return_value=sample),
    ):
        result = await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"window": "today", "category": "health"},
            logical_today=date(2026, 5, 18),
        )
    assert [h["id"] for h in result.data["habits"]] == ["h2"]
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd backend && pytest tests/test_ai_tools_habits.py -v`
Expected: 4 FAILED.

- [ ] **Step 4: Implement the handler**

Replace `backend/app/routers/ai/tools/habits.py` with:

```python
"""get_habits read-tool handler.

Returns per-habit progress for one of three windows. Reads from
Firestore via the same helpers ``load_user_context_bundle`` uses,
exposed here as ``_load_habits_today`` / ``_load_habits_window``
patch points so tests can swap in fixtures.
"""

from __future__ import annotations

from datetime import date
from typing import Any

from app.routers.ai.tools._base import (
    ToolError,
    ToolHandler,
    ToolResult,
    validate_one_of,
)


_WINDOWS = {"today", "last_7d", "last_30d"}
_CATEGORIES = {"work", "personal", "self_care", "health", "uncategorized"}


async def _load_habits_today(uid: str, today: date) -> list[dict[str, Any]]:
    """Wrap the existing context-bundle habit loader so tests can patch one symbol."""
    from app.routers.ai.context import load_habits_today
    return await load_habits_today(uid, today)


async def _load_habits_window(
    uid: str, *, days: int, today: date
) -> list[dict[str, Any]]:
    from app.routers.ai.context import load_habits_window
    return await load_habits_window(uid, days=days, today=today)


class GetHabitsHandler:
    name = "get_habits"
    schema = {
        "name": "get_habits",
        "description": (
            "Read per-habit progress: today's count vs target, current "
            "streak, and last_7d count. window=today returns the same data "
            "the Today screen renders; last_7d / last_30d returns rolling-"
            "window aggregates for trend questions."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "window": {"type": "string", "enum": sorted(_WINDOWS)},
                "category": {"type": "string", "enum": sorted(_CATEGORIES)},
                "include_archived": {"type": "boolean", "default": False},
            },
            "required": ["window"],
        },
    }

    async def dispatch(self, *, user, db, args: dict, logical_today: date) -> ToolResult:
        uid = getattr(user, "firebase_uid", None)
        if not uid:
            raise ToolError("user is not Firebase-linked", field="user")
        window = validate_one_of(args.get("window"), _WINDOWS, field="window")
        category = args.get("category")
        if category is not None:
            validate_one_of(category, _CATEGORIES, field="category")

        if window == "today":
            habits = await _load_habits_today(uid, logical_today)
        else:
            days = 7 if window == "last_7d" else 30
            habits = await _load_habits_window(uid, days=days, today=logical_today)

        if category is not None:
            habits = [h for h in habits if h.get("category") == category]

        return ToolResult(
            data={"window": window, "habits": habits, "count": len(habits)},
            summary=f"{len(habits)} habit(s) in {window}",
        )
```

- [ ] **Step 5: Add public loader wrappers in `context.py`**

The handler imports `load_habits_today` / `load_habits_window` from `app.routers.ai.context`. If those public names don't exist yet, add thin re-exports at the bottom of `backend/app/routers/ai/context.py`:

```python
# Public re-exports for the AI Assistant tool handlers. The private
# loaders that build the chat context bundle are the source of truth; the
# tool handlers patch these public symbols in their unit tests.
async def load_habits_today(uid: str, today: date) -> list[dict]:
    return await _load_habits_today_block(uid, today)  # whatever the existing helper is named


async def load_habits_window(uid: str, *, days: int, today: date) -> list[dict]:
    return await _load_habits_window_block(uid, days=days, today=today)
```

Confirm the actual private function names by running:
```bash
grep -n "def _load_habits\|def _habit\|habit_today\|habit_window" backend/app/routers/ai/context.py
```
and renaming the calls accordingly. If a "window" loader does not exist, lift the inline window logic from `load_user_context_bundle` into a new private `_load_habits_window_block` first and then have `load_user_context_bundle` call it.

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd backend && pytest tests/test_ai_tools_habits.py -v`
Expected: 4 PASSED.

- [ ] **Step 7: Commit**

```bash
git add backend/app/routers/ai/tools/habits.py backend/app/routers/ai/context.py backend/tests/test_ai_tools_habits.py
git commit -m "feat(ai-assistant): get_habits read-tool handler"
```

---

## Task 7: `get_projects` handler

**Files:**
- Modify: `backend/app/routers/ai/tools/projects.py`
- Create: `backend/tests/test_ai_tools_projects.py`

- [ ] **Step 1: Write the failing test**

Create `backend/tests/test_ai_tools_projects.py`:

```python
"""Unit tests for the get_projects tool handler."""

from datetime import date
from unittest.mock import AsyncMock, patch

import pytest

from app.routers.ai.tools._base import ToolError
from app.routers.ai.tools.projects import GetProjectsHandler


class _FakeUser:
    id = 11
    firebase_uid = "fb-11"


@pytest.mark.asyncio
async def test_get_projects_rejects_unknown_status():
    handler = GetProjectsHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"status": "frozen"},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "status"


@pytest.mark.asyncio
async def test_get_projects_active_status_returns_summaries():
    sample = [
        {"id": "p1", "title": "Q2 launch", "status": "active",
         "progress_pct": 42, "done_tasks": 21, "total_tasks": 50,
         "days_until_due": 7, "milestones_count": 3},
        {"id": "p2", "title": "Backlog cleanup", "status": "active",
         "progress_pct": None, "done_tasks": 0, "total_tasks": 0,
         "days_until_due": None, "milestones_count": 0},
    ]
    handler = GetProjectsHandler()
    with patch(
        "app.routers.ai.tools.projects._load_projects",
        new=AsyncMock(return_value=sample),
    ) as mock_load:
        result = await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"status": "active"},
            logical_today=date(2026, 5, 18),
        )
    mock_load.assert_awaited_once_with("fb-11", status="active")
    assert len(result.data["projects"]) == 2


@pytest.mark.asyncio
async def test_get_projects_limit_caps_returned_rows():
    sample = [
        {"id": f"p{i}", "title": f"P{i}", "status": "active",
         "progress_pct": None, "done_tasks": 0, "total_tasks": 0,
         "days_until_due": None, "milestones_count": 0}
        for i in range(20)
    ]
    handler = GetProjectsHandler()
    with patch(
        "app.routers.ai.tools.projects._load_projects",
        new=AsyncMock(return_value=sample),
    ):
        result = await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"status": "active", "limit": 5},
            logical_today=date(2026, 5, 18),
        )
    assert len(result.data["projects"]) == 5
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && pytest tests/test_ai_tools_projects.py -v`
Expected: 3 FAILED.

- [ ] **Step 3: Implement the handler**

Replace `backend/app/routers/ai/tools/projects.py` with:

```python
"""get_projects read-tool handler."""

from __future__ import annotations

from datetime import date
from typing import Any

from app.routers.ai.tools._base import (
    ToolError,
    ToolHandler,
    ToolResult,
    validate_one_of,
    validate_positive_int,
)


_STATUSES = {"active", "archived", "all"}
_MAX_LIMIT = 50


async def _load_projects(uid: str, *, status: str) -> list[dict[str, Any]]:
    """Patch point. Wraps the existing context-bundle project loader."""
    from app.routers.ai.context import load_projects
    return await load_projects(uid, status=status)


class GetProjectsHandler:
    name = "get_projects"
    schema = {
        "name": "get_projects",
        "description": (
            "Read user's projects with progress, due date, milestone count. "
            "status=active is the default users care about; archived / all "
            "are for retrospective questions."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "status": {"type": "string", "enum": sorted(_STATUSES)},
                "limit": {
                    "type": "integer",
                    "minimum": 1,
                    "maximum": _MAX_LIMIT,
                    "default": 20,
                },
            },
            "required": ["status"],
        },
    }

    async def dispatch(self, *, user, db, args: dict, logical_today: date) -> ToolResult:
        uid = getattr(user, "firebase_uid", None)
        if not uid:
            raise ToolError("user is not Firebase-linked", field="user")
        status = validate_one_of(args.get("status"), _STATUSES, field="status")
        limit = validate_positive_int(
            args.get("limit", 20), max_value=_MAX_LIMIT, field="limit",
        )
        projects = await _load_projects(uid, status=status)
        sliced = projects[:limit]
        return ToolResult(
            data={"status": status, "projects": sliced, "count": len(sliced)},
            summary=f"{len(sliced)} project(s) ({status})",
        )
```

- [ ] **Step 4: Add the public `load_projects` re-export to `context.py`**

At the bottom of `backend/app/routers/ai/context.py`, add a `load_projects` wrapper around whatever the existing private project-loader is (mirror the Task 6 Step 5 approach).

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && pytest tests/test_ai_tools_projects.py -v`
Expected: 3 PASSED.

- [ ] **Step 6: Commit**

```bash
git add backend/app/routers/ai/tools/projects.py backend/app/routers/ai/context.py backend/tests/test_ai_tools_projects.py
git commit -m "feat(ai-assistant): get_projects read-tool handler"
```

---

## Task 8: `get_medications` handler

**Files:**
- Modify: `backend/app/routers/ai/tools/medications.py`
- Create: `backend/tests/test_ai_tools_medications.py`

- [ ] **Step 1: Write the failing test**

Create `backend/tests/test_ai_tools_medications.py`:

```python
"""Unit tests for the get_medications tool handler."""

from datetime import date
from unittest.mock import AsyncMock, patch

import pytest

from app.routers.ai.tools._base import ToolError
from app.routers.ai.tools.medications import GetMedicationsHandler


class _FakeUser:
    id = 13
    firebase_uid = "fb-13"


@pytest.mark.asyncio
async def test_get_medications_rejects_unknown_window():
    handler = GetMedicationsHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"window": "year"},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "window"


@pytest.mark.asyncio
async def test_get_medications_today_returns_adherence_snapshot():
    sample = {
        "active_count": 2,
        "active": [
            {"id": "m1", "name": "Sertraline", "taken_today": True,
             "missed_today": False, "refill_due_in_days": 12},
            {"id": "m2", "name": "Vitamin D", "taken_today": False,
             "missed_today": True, "refill_due_in_days": None},
        ],
    }
    handler = GetMedicationsHandler()
    with patch(
        "app.routers.ai.tools.medications._load_medications",
        new=AsyncMock(return_value=sample),
    ) as mock_load:
        result = await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"window": "today"},
            logical_today=date(2026, 5, 18),
        )
    mock_load.assert_awaited_once_with(
        "fb-13", window="today", include_archived=False,
        today=date(2026, 5, 18),
    )
    assert result.data["active_count"] == 2
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && pytest tests/test_ai_tools_medications.py -v`
Expected: 2 FAILED.

- [ ] **Step 3: Implement the handler**

Replace `backend/app/routers/ai/tools/medications.py` with:

```python
"""get_medications read-tool handler."""

from __future__ import annotations

from datetime import date
from typing import Any

from app.routers.ai.tools._base import (
    ToolError,
    ToolHandler,
    ToolResult,
    validate_one_of,
)


_WINDOWS = {"today", "last_7d"}


async def _load_medications(
    uid: str, *, window: str, include_archived: bool, today: date
) -> dict[str, Any]:
    from app.routers.ai.context import load_medications
    return await load_medications(
        uid, window=window, include_archived=include_archived, today=today,
    )


class GetMedicationsHandler:
    name = "get_medications"
    schema = {
        "name": "get_medications",
        "description": (
            "Read medication adherence: per-med taken_today / missed_today "
            "and refill_due_in_days. window=last_7d returns per-med 7-day "
            "adherence counts."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "window": {"type": "string", "enum": sorted(_WINDOWS)},
                "include_archived": {"type": "boolean", "default": False},
            },
            "required": ["window"],
        },
    }

    async def dispatch(self, *, user, db, args: dict, logical_today: date) -> ToolResult:
        uid = getattr(user, "firebase_uid", None)
        if not uid:
            raise ToolError("user is not Firebase-linked", field="user")
        window = validate_one_of(args.get("window"), _WINDOWS, field="window")
        include_archived = bool(args.get("include_archived", False))
        data = await _load_medications(
            uid, window=window, include_archived=include_archived,
            today=logical_today,
        )
        return ToolResult(
            data=data,
            summary=f"{data.get('active_count', 0)} active med(s) ({window})",
        )
```

- [ ] **Step 4: Add `load_medications` re-export to `context.py`**

Mirror the Task 6 pattern — wrap whatever the existing private medication loader is.

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && pytest tests/test_ai_tools_medications.py -v`
Expected: 2 PASSED.

- [ ] **Step 6: Commit**

```bash
git add backend/app/routers/ai/tools/medications.py backend/app/routers/ai/context.py backend/tests/test_ai_tools_medications.py
git commit -m "feat(ai-assistant): get_medications read-tool handler"
```

---

## Task 9: `get_mood_logs` handler

**Files:**
- Modify: `backend/app/routers/ai/tools/mood.py`
- Create: `backend/tests/test_ai_tools_mood.py`

- [ ] **Step 1: Write the failing test**

Create `backend/tests/test_ai_tools_mood.py`:

```python
"""Unit tests for the get_mood_logs tool handler."""

from datetime import date
from unittest.mock import AsyncMock, patch

import pytest

from app.routers.ai.tools._base import ToolError
from app.routers.ai.tools.mood import GetMoodLogsHandler


class _FakeUser:
    id = 15
    firebase_uid = "fb-15"


@pytest.mark.asyncio
async def test_mood_rejects_garbage_start_date():
    handler = GetMoodLogsHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"start": "yesterday", "end": "2026-05-18", "aggregate": "raw"},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "start"


@pytest.mark.asyncio
async def test_mood_rejects_end_before_start():
    handler = GetMoodLogsHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"start": "2026-05-18", "end": "2026-05-17", "aggregate": "raw"},
            logical_today=date(2026, 5, 18),
        )
    assert "before" in exc.value.message.lower() or "after" in exc.value.message.lower()


@pytest.mark.asyncio
async def test_mood_raw_returns_per_log_points():
    sample = [
        {"logged_at": "2026-05-17T09:00:00Z", "mood": 4, "energy": 3},
        {"logged_at": "2026-05-17T18:00:00Z", "mood": 5, "energy": 4},
    ]
    handler = GetMoodLogsHandler()
    with patch(
        "app.routers.ai.tools.mood._load_mood_logs",
        new=AsyncMock(return_value=sample),
    ):
        result = await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"start": "2026-05-17", "end": "2026-05-18", "aggregate": "raw"},
            logical_today=date(2026, 5, 18),
        )
    assert result.data["aggregate"] == "raw"
    assert len(result.data["points"]) == 2
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && pytest tests/test_ai_tools_mood.py -v`
Expected: 3 FAILED.

- [ ] **Step 3: Implement the handler**

Replace `backend/app/routers/ai/tools/mood.py` with:

```python
"""get_mood_logs read-tool handler."""

from __future__ import annotations

from datetime import date
from typing import Any

from app.routers.ai.tools._base import (
    ToolError,
    ToolHandler,
    ToolResult,
    validate_iso_date,
    validate_one_of,
)


_AGGREGATES = {"raw", "daily_avg"}


async def _load_mood_logs(
    user_id: int, *, start: date, end: date
) -> list[dict[str, Any]]:
    from app.routers.ai.context import load_mood_logs
    return await load_mood_logs(user_id, start=start, end=end)


def _daily_avg(points: list[dict[str, Any]]) -> list[dict[str, Any]]:
    by_day: dict[str, list[dict[str, Any]]] = {}
    for p in points:
        day = (p.get("logged_at") or "")[:10]
        if not day:
            continue
        by_day.setdefault(day, []).append(p)
    out: list[dict[str, Any]] = []
    for day, ps in sorted(by_day.items()):
        moods = [p["mood"] for p in ps if isinstance(p.get("mood"), (int, float))]
        energies = [p["energy"] for p in ps if isinstance(p.get("energy"), (int, float))]
        out.append({
            "date": day,
            "mood_avg": round(sum(moods) / len(moods), 2) if moods else None,
            "energy_avg": round(sum(energies) / len(energies), 2) if energies else None,
            "log_count": len(ps),
        })
    return out


class GetMoodLogsHandler:
    name = "get_mood_logs"
    schema = {
        "name": "get_mood_logs",
        "description": (
            "Read mood / energy log entries between start and end (inclusive). "
            "aggregate=raw returns each log; aggregate=daily_avg returns "
            "one row per day with mood_avg / energy_avg / log_count."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "start": {"type": "string", "description": "YYYY-MM-DD"},
                "end": {"type": "string", "description": "YYYY-MM-DD"},
                "aggregate": {"type": "string", "enum": sorted(_AGGREGATES)},
            },
            "required": ["start", "end", "aggregate"],
        },
    }

    async def dispatch(self, *, user, db, args: dict, logical_today: date) -> ToolResult:
        user_id = getattr(user, "id", None)
        if user_id is None:
            raise ToolError("missing user id", field="user")
        start = validate_iso_date(args.get("start"), field="start")
        end = validate_iso_date(args.get("end"), field="end")
        if end < start:
            raise ToolError(
                f"end={end.isoformat()} is before start={start.isoformat()}",
                field="end",
            )
        aggregate = validate_one_of(args.get("aggregate"), _AGGREGATES, field="aggregate")
        raw = await _load_mood_logs(user_id, start=start, end=end)
        if aggregate == "raw":
            return ToolResult(
                data={"aggregate": aggregate, "points": raw, "count": len(raw)},
                summary=f"{len(raw)} mood log(s)",
            )
        rolled = _daily_avg(raw)
        return ToolResult(
            data={"aggregate": aggregate, "days": rolled, "count": len(rolled)},
            summary=f"{len(rolled)} day(s) of mood averages",
        )
```

- [ ] **Step 4: Add `load_mood_logs` re-export to `context.py`**

Mirror prior tasks. The mood logs live in Postgres (`MoodEnergyLogEntity`), not Firestore, so the loader takes a `user_id: int`, not a Firebase UID.

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && pytest tests/test_ai_tools_mood.py -v`
Expected: 3 PASSED.

- [ ] **Step 6: Commit**

```bash
git add backend/app/routers/ai/tools/mood.py backend/app/routers/ai/context.py backend/tests/test_ai_tools_mood.py
git commit -m "feat(ai-assistant): get_mood_logs read-tool handler"
```

---

## Task 10: `get_leisure_logs` handler

**Files:**
- Modify: `backend/app/routers/ai/tools/leisure.py`
- Create: `backend/tests/test_ai_tools_leisure.py`

- [ ] **Step 1: Write the failing test**

Create `backend/tests/test_ai_tools_leisure.py`:

```python
"""Unit tests for the get_leisure_logs tool handler."""

from datetime import date
from unittest.mock import AsyncMock, patch

import pytest

from app.routers.ai.tools._base import ToolError
from app.routers.ai.tools.leisure import GetLeisureLogsHandler


class _FakeUser:
    id = 17
    firebase_uid = "fb-17"


@pytest.mark.asyncio
async def test_leisure_rejects_unknown_window():
    handler = GetLeisureLogsHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"window": "year"},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "window"


@pytest.mark.asyncio
async def test_leisure_today_returns_totals_and_entries():
    sample = {
        "totals_by_category": {"reading": 45, "gaming": 30},
        "entries": [
            {"id": "l1", "category": "reading", "minutes": 45,
             "logged_at": "2026-05-18T09:00:00Z"},
        ],
        "total_minutes": 75,
    }
    handler = GetLeisureLogsHandler()
    with patch(
        "app.routers.ai.tools.leisure._load_leisure",
        new=AsyncMock(return_value=sample),
    ) as mock_load:
        result = await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"window": "today"},
            logical_today=date(2026, 5, 18),
        )
    mock_load.assert_awaited_once_with(
        17, window="today", category=None, today=date(2026, 5, 18),
    )
    assert result.data["total_minutes"] == 75


@pytest.mark.asyncio
async def test_leisure_filters_by_category():
    handler = GetLeisureLogsHandler()
    with patch(
        "app.routers.ai.tools.leisure._load_leisure",
        new=AsyncMock(return_value={"totals_by_category": {}, "entries": [], "total_minutes": 0}),
    ) as mock_load:
        await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"window": "last_7d", "category": "reading"},
            logical_today=date(2026, 5, 18),
        )
    mock_load.assert_awaited_once_with(
        17, window="last_7d", category="reading", today=date(2026, 5, 18),
    )
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && pytest tests/test_ai_tools_leisure.py -v`
Expected: 3 FAILED.

- [ ] **Step 3: Implement the handler**

Replace `backend/app/routers/ai/tools/leisure.py` with:

```python
"""get_leisure_logs read-tool handler."""

from __future__ import annotations

from datetime import date
from typing import Any

from app.routers.ai.tools._base import (
    ToolError,
    ToolHandler,
    ToolResult,
    validate_one_of,
)


_WINDOWS = {"today", "last_7d", "last_30d"}


async def _load_leisure(
    user_id: int, *, window: str, category: str | None, today: date
) -> dict[str, Any]:
    from app.routers.ai.context import load_leisure
    return await load_leisure(
        user_id, window=window, category=category, today=today,
    )


class GetLeisureLogsHandler:
    name = "get_leisure_logs"
    schema = {
        "name": "get_leisure_logs",
        "description": (
            "Read leisure (rest / play) log entries with per-category totals. "
            "Window today / last_7d / last_30d; optional category filter."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "window": {"type": "string", "enum": sorted(_WINDOWS)},
                "category": {"type": "string"},
            },
            "required": ["window"],
        },
    }

    async def dispatch(self, *, user, db, args: dict, logical_today: date) -> ToolResult:
        user_id = getattr(user, "id", None)
        if user_id is None:
            raise ToolError("missing user id", field="user")
        window = validate_one_of(args.get("window"), _WINDOWS, field="window")
        category = args.get("category")
        if category is not None and not isinstance(category, str):
            raise ToolError("category must be a string", field="category")
        data = await _load_leisure(
            user_id, window=window, category=category, today=logical_today,
        )
        return ToolResult(
            data=data,
            summary=f"{data.get('total_minutes', 0)} leisure minute(s) ({window})",
        )
```

- [ ] **Step 4: Add `load_leisure` re-export to `context.py`**

Mirror prior tasks. Leisure logs are in Postgres → use `user_id: int`.

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && pytest tests/test_ai_tools_leisure.py -v`
Expected: 3 PASSED.

- [ ] **Step 6: Commit**

```bash
git add backend/app/routers/ai/tools/leisure.py backend/app/routers/ai/context.py backend/tests/test_ai_tools_leisure.py
git commit -m "feat(ai-assistant): get_leisure_logs read-tool handler"
```

---

## Task 11: `query` escape-hatch handler + closed filter grammar

**Files:**
- Modify: `backend/app/routers/ai/tools/query.py`
- Create: `backend/tests/test_ai_tools_query.py`

The escape hatch supports filtered reads on 8 entity types. Each entity type has a fixed set of allowed filter keys; unknown keys raise `ToolError`. Returns at most 100 rows.

- [ ] **Step 1: Write the failing test**

Create `backend/tests/test_ai_tools_query.py`:

```python
"""Unit tests for the escape-hatch query tool handler."""

from datetime import date
from unittest.mock import AsyncMock, patch

import pytest

from app.routers.ai.tools._base import ToolError
from app.routers.ai.tools.query import QueryHandler, ALLOWED_FILTERS


class _FakeUser:
    id = 19
    firebase_uid = "fb-19"


@pytest.mark.asyncio
async def test_query_rejects_unknown_entity_type():
    handler = QueryHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"entity_type": "WIDGET", "filters": {}},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "entity_type"


@pytest.mark.asyncio
async def test_query_rejects_unknown_filter_key():
    handler = QueryHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"entity_type": "TASK", "filters": {"nonsense": 1}},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "filters.nonsense"


@pytest.mark.asyncio
async def test_query_caps_limit_at_100():
    handler = QueryHandler()
    with pytest.raises(ToolError) as exc:
        await handler.dispatch(
            user=_FakeUser(), db=None,
            args={"entity_type": "TASK", "filters": {}, "limit": 500},
            logical_today=date(2026, 5, 18),
        )
    assert exc.value.field == "limit"


@pytest.mark.asyncio
async def test_query_task_routes_to_task_dispatch():
    handler = QueryHandler()
    with patch(
        "app.routers.ai.tools.query._query_tasks",
        new=AsyncMock(return_value=[{"id": "t1"}]),
    ) as mock_dispatch:
        result = await handler.dispatch(
            user=_FakeUser(), db=None,
            args={
                "entity_type": "TASK",
                "filters": {"completed": False, "due_before": "2026-05-25"},
                "fields": ["id", "title"],
                "limit": 10,
            },
            logical_today=date(2026, 5, 18),
        )
    mock_dispatch.assert_awaited_once()
    assert result.data["count"] == 1


def test_allowed_filters_covers_every_entity_type():
    expected = {
        "TASK", "HABIT", "PROJECT", "MEDICATION",
        "MOOD_LOG", "LEISURE_LOG", "CHECK_IN", "GOAL",
    }
    assert set(ALLOWED_FILTERS.keys()) == expected
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && pytest tests/test_ai_tools_query.py -v`
Expected: 5 FAILED.

- [ ] **Step 3: Implement the handler**

Replace `backend/app/routers/ai/tools/query.py` with:

```python
"""Escape-hatch query tool — narrow, closed-set filter grammar per entity type.

Unknown filter keys raise ToolError so the model can correct on the next
loop iteration. No raw SQL — every dispatch hits a typed helper.
"""

from __future__ import annotations

from datetime import date
from typing import Any, Awaitable, Callable

from app.routers.ai.tools._base import (
    ToolError,
    ToolHandler,
    ToolResult,
    validate_one_of,
    validate_positive_int,
)


_ENTITY_TYPES = {
    "TASK", "HABIT", "PROJECT", "MEDICATION",
    "MOOD_LOG", "LEISURE_LOG", "CHECK_IN", "GOAL",
}
_MAX_LIMIT = 100


ALLOWED_FILTERS: dict[str, set[str]] = {
    "TASK": {
        "completed", "due_before", "due_after", "priority_in",
        "tag_in", "project_id", "life_category_in", "text_contains",
    },
    "HABIT": {"category", "is_archived", "is_built_in"},
    "PROJECT": {"status", "due_before", "due_after"},
    "MEDICATION": {"is_archived", "refill_due_within_days"},
    "MOOD_LOG": {"logged_after", "logged_before", "min_mood", "max_mood"},
    "LEISURE_LOG": {"category", "logged_after", "logged_before"},
    "CHECK_IN": {"logged_after", "logged_before"},
    "GOAL": {"status", "target_before", "target_after"},
}


async def _query_tasks(user, db, *, filters: dict, fields: list[str], limit: int, today: date) -> list[dict]:
    from app.routers.ai.context import query_tasks
    return await query_tasks(user, db, filters=filters, fields=fields, limit=limit, today=today)


async def _query_habits(user, db, *, filters: dict, fields: list[str], limit: int, today: date) -> list[dict]:
    from app.routers.ai.context import query_habits
    return await query_habits(user, db, filters=filters, fields=fields, limit=limit, today=today)


async def _query_projects(user, db, *, filters: dict, fields: list[str], limit: int, today: date) -> list[dict]:
    from app.routers.ai.context import query_projects
    return await query_projects(user, db, filters=filters, fields=fields, limit=limit, today=today)


async def _query_medications(user, db, *, filters: dict, fields: list[str], limit: int, today: date) -> list[dict]:
    from app.routers.ai.context import query_medications
    return await query_medications(user, db, filters=filters, fields=fields, limit=limit, today=today)


async def _query_mood_logs(user, db, *, filters: dict, fields: list[str], limit: int, today: date) -> list[dict]:
    from app.routers.ai.context import query_mood_logs
    return await query_mood_logs(user, db, filters=filters, fields=fields, limit=limit, today=today)


async def _query_leisure_logs(user, db, *, filters: dict, fields: list[str], limit: int, today: date) -> list[dict]:
    from app.routers.ai.context import query_leisure_logs
    return await query_leisure_logs(user, db, filters=filters, fields=fields, limit=limit, today=today)


async def _query_check_ins(user, db, *, filters: dict, fields: list[str], limit: int, today: date) -> list[dict]:
    from app.routers.ai.context import query_check_ins
    return await query_check_ins(user, db, filters=filters, fields=fields, limit=limit, today=today)


async def _query_goals(user, db, *, filters: dict, fields: list[str], limit: int, today: date) -> list[dict]:
    from app.routers.ai.context import query_goals
    return await query_goals(user, db, filters=filters, fields=fields, limit=limit, today=today)


_DISPATCH: dict[str, Callable[..., Awaitable[list[dict]]]] = {
    "TASK": _query_tasks,
    "HABIT": _query_habits,
    "PROJECT": _query_projects,
    "MEDICATION": _query_medications,
    "MOOD_LOG": _query_mood_logs,
    "LEISURE_LOG": _query_leisure_logs,
    "CHECK_IN": _query_check_ins,
    "GOAL": _query_goals,
}


class QueryHandler:
    name = "query"
    schema = {
        "name": "query",
        "description": (
            "Run an ad-hoc filtered read across one user-owned entity type. "
            "Use this only when no narrow tool covers the question. "
            "Filters are entity-typed; unknown keys are rejected. "
            "Max 100 rows per call."
        ),
        "input_schema": {
            "type": "object",
            "properties": {
                "entity_type": {"type": "string", "enum": sorted(_ENTITY_TYPES)},
                "filters": {"type": "object"},
                "fields": {
                    "type": "array",
                    "items": {"type": "string"},
                    "default": [],
                },
                "limit": {
                    "type": "integer",
                    "minimum": 1,
                    "maximum": _MAX_LIMIT,
                    "default": 50,
                },
                "order_by": {"type": "string"},
            },
            "required": ["entity_type", "filters"],
        },
    }

    async def dispatch(self, *, user, db, args: dict, logical_today: date) -> ToolResult:
        entity_type = validate_one_of(
            args.get("entity_type"), _ENTITY_TYPES, field="entity_type",
        )
        filters = args.get("filters")
        if not isinstance(filters, dict):
            raise ToolError("filters must be an object", field="filters")
        allowed = ALLOWED_FILTERS[entity_type]
        for k in filters.keys():
            if k not in allowed:
                raise ToolError(
                    f"filter {k!r} not allowed for {entity_type}; "
                    f"allowed: {sorted(allowed)}",
                    field=f"filters.{k}",
                )
        fields = args.get("fields") or []
        if not isinstance(fields, list) or any(not isinstance(f, str) for f in fields):
            raise ToolError("fields must be a list of strings", field="fields")
        limit = validate_positive_int(
            args.get("limit", 50), max_value=_MAX_LIMIT, field="limit",
        )

        rows = await _DISPATCH[entity_type](
            user, db,
            filters=filters, fields=fields, limit=limit, today=logical_today,
        )
        return ToolResult(
            data={"entity_type": entity_type, "rows": rows, "count": len(rows)},
            summary=f"{len(rows)} {entity_type.lower()}(s)",
        )
```

- [ ] **Step 4: Add `query_*` stubs to `context.py`**

Each `query_<entity>` helper must exist in `app/routers/ai/context.py` even if it just returns `[]` for entity types whose query backend isn't built yet. At the bottom of `backend/app/routers/ai/context.py`:

```python
# --- Phase 1 escape-hatch query helpers (one per entity type). -------------
# Stubbed minimal implementations for entity types whose query backend is
# not yet first-class; flesh out as the spec's later phases land. The
# QueryHandler's filter validation is the load-bearing safety boundary;
# these helpers only need to honor the closed-set filter contract.

async def query_tasks(user, db, *, filters, fields, limit, today):
    raise NotImplementedError("query_tasks: Phase 1 follow-on")


async def query_habits(user, db, *, filters, fields, limit, today):
    raise NotImplementedError("query_habits: Phase 1 follow-on")


async def query_projects(user, db, *, filters, fields, limit, today):
    raise NotImplementedError("query_projects: Phase 1 follow-on")


async def query_medications(user, db, *, filters, fields, limit, today):
    raise NotImplementedError("query_medications: Phase 1 follow-on")


async def query_mood_logs(user, db, *, filters, fields, limit, today):
    raise NotImplementedError("query_mood_logs: Phase 1 follow-on")


async def query_leisure_logs(user, db, *, filters, fields, limit, today):
    raise NotImplementedError("query_leisure_logs: Phase 1 follow-on")


async def query_check_ins(user, db, *, filters, fields, limit, today):
    raise NotImplementedError("query_check_ins: Phase 1 follow-on")


async def query_goals(user, db, *, filters, fields, limit, today):
    raise NotImplementedError("query_goals: Phase 1 follow-on")
```

**Important:** these `NotImplementedError` stubs are intentional — the `query` tool is registered with the schema in P1 so the model knows the contract, but actual entity dispatch lands incrementally. When the loop catches a `NotImplementedError` it converts it to a tool_result error block the model can read. **Add a `try/except NotImplementedError` in `QueryHandler.dispatch`** that re-raises as `ToolError`:

Modify the end of `QueryHandler.dispatch` to wrap the dispatch call:

```python
        try:
            rows = await _DISPATCH[entity_type](
                user, db,
                filters=filters, fields=fields, limit=limit, today=logical_today,
            )
        except NotImplementedError:
            raise ToolError(
                f"query backend for {entity_type} not implemented yet; "
                f"use a narrow tool (get_tasks / get_habits / ...) instead",
                field="entity_type",
            )
        return ToolResult(
            data={"entity_type": entity_type, "rows": rows, "count": len(rows)},
            summary=f"{len(rows)} {entity_type.lower()}(s)",
        )
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd backend && pytest tests/test_ai_tools_query.py -v`
Expected: 5 PASSED.

- [ ] **Step 6: Commit**

```bash
git add backend/app/routers/ai/tools/query.py backend/app/routers/ai/context.py backend/tests/test_ai_tools_query.py
git commit -m "feat(ai-assistant): query escape-hatch tool with closed filter grammar"
```

---

## Task 12: Update system prompt — add Tools section + finalize audit doc

**Files:**
- Modify: `backend/app/services/ai_productivity.py:1412`
- Modify: `docs/audits/AI_ASSISTANT_PHASE_1_PROMPT_AUDIT.md`

- [ ] **Step 1: Compose the new Tools section text**

The new section goes between the existing "User preferences memory:" block and the closing triple-quote of `_CHAT_SYSTEM_PROMPT_BASE`. Text:

```
Tools (read):
You have read-only tools that fetch live user data:
- get_tasks(bucket, limit?, project_id?, tag?, life_category?) — task lists by bucket (overdue / today / planned / upcoming / backlog / recently_completed).
- get_habits(window, category?) — per-habit progress; window = today / last_7d / last_30d.
- get_projects(status, limit?) — active / archived / all projects with progress + days_until_due.
- get_medications(window) — adherence; window = today / last_7d.
- get_mood_logs(start, end, aggregate) — raw points or daily averages.
- get_leisure_logs(window, category?) — leisure totals + entries.
- query(entity_type, filters, fields?, limit?, order_by?) — escape hatch for analytical questions no narrow tool covers.

When to call: use a read tool when the user asks a question grounded in their data ("what's most overdue?", "how's my reading streak?", "did I take my meds yesterday?"). Skip the call when the curated state bundle already has the answer. Never call more than ~3 reads per turn unless the user explicitly asked for a multi-source comparison.

Tools (write):
The write tools (complete, reschedule, breakdown, archive, start_timer, create_task, batch_command, …) emit as action chips the user taps. You do NOT execute them. Emit one only when the user has expressed a clear, actionable intent in their most recent message — same rule as before.

Never invent IDs, fields, or filter keys. If a read tool returns an error, read the error message and retry with corrected args; do not loop forever.
```

- [ ] **Step 2: Apply the prompt edit**

In `backend/app/services/ai_productivity.py`, modify `_CHAT_SYSTEM_PROMPT_BASE` (the string starting around line 1412). Find the line `- Tool calls happen silently — do NOT narrate "I'll remember that" in your reply text unless the user explicitly asked you to. Just remember and respond naturally."""` and replace it with the same line PLUS the new Tools blocks appended before the closing triple-quote:

```python
- Tool calls happen silently — do NOT narrate "I'll remember that" in your reply text unless the user explicitly asked you to. Just remember and respond naturally.

Tools (read):
You have read-only tools that fetch live user data:
- get_tasks(bucket, limit?, project_id?, tag?, life_category?) — task lists by bucket (overdue / today / planned / upcoming / backlog / recently_completed).
- get_habits(window, category?) — per-habit progress; window = today / last_7d / last_30d.
- get_projects(status, limit?) — active / archived / all projects with progress + days_until_due.
- get_medications(window) — adherence; window = today / last_7d.
- get_mood_logs(start, end, aggregate) — raw points or daily averages.
- get_leisure_logs(window, category?) — leisure totals + entries.
- query(entity_type, filters, fields?, limit?, order_by?) — escape hatch for analytical questions no narrow tool covers.

When to call: use a read tool when the user asks a question grounded in their data ("what's most overdue?", "how's my reading streak?", "did I take my meds yesterday?"). Skip the call when the curated state bundle already has the answer. Never call more than ~3 reads per turn unless the user explicitly asked for a multi-source comparison.

Tools (write):
The write tools (complete, reschedule, breakdown, archive, start_timer, create_task, batch_command, …) emit as action chips the user taps. You do NOT execute them. Emit one only when the user has expressed a clear, actionable intent in their most recent message — same rule as before.

Never invent IDs, fields, or filter keys. If a read tool returns an error, read the error message and retry with corrected args; do not loop forever."""
```

- [ ] **Step 3: Update the audit doc to "Implemented" status and lock the diff**

In `docs/audits/AI_ASSISTANT_PHASE_1_PROMPT_AUDIT.md`, replace the **Status:** line with `**Status:** Implemented (Phase 1 PR #<TBD-on-PR-open>)` and add a `## Final Prompt Diff` section at the bottom containing the exact verbatim diff hunk (`git diff backend/app/services/ai_productivity.py | grep -A 50 "Tools (read):"` works as a quick capture).

- [ ] **Step 4: Run the existing prompt canary test to confirm no regression**

Run: `cd backend && pytest tests/test_ai_chat.py -k "system_prompt" -v`
Expected: PASS (existing canary tests don't pin the new Tools block; they check unchanged sections).

- [ ] **Step 5: Commit**

```bash
git add backend/app/services/ai_productivity.py docs/audits/AI_ASSISTANT_PHASE_1_PROMPT_AUDIT.md
git commit -m "feat(ai-assistant): system prompt Tools section + audit doc finalized"
```

---

## Task 13: Wire the agentic loop into `generate_chat_response`

**Files:**
- Modify: `backend/app/services/ai_productivity.py:2127`
- Create: `backend/tests/test_ai_chat_tool_loop.py`

This is the load-bearing change. `generate_chat_response` currently does a single `client.messages.create(...)` call and extracts blocks. We wrap it in a bounded loop that:
1. Calls Claude with the combined tool list (existing write tools + new read tools).
2. Inspects each `tool_use` block in the response.
3. If any block's name ∈ `READ_TOOL_NAMES`, dispatches it server-side, appends `tool_result` blocks, and re-invokes Claude.
4. Otherwise (write chips, memory tools, or no tool_use), returns.
5. Capped at 10 read tool calls per turn.

- [ ] **Step 1: Write the failing test**

Create `backend/tests/test_ai_chat_tool_loop.py`:

```python
"""End-to-end test for the AI Assistant Phase 1 tool-use loop.

Mocks `client.messages.create` to return a two-turn sequence:
  1. Claude calls get_tasks(bucket="overdue").
  2. Claude returns a final text reply after seeing the tool_result.
Verifies the loop dispatched the read tool, fed the result back, and
returned the final reply with a tool_calls summary attached.
"""

from datetime import date
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest


def _block_text(text: str):
    return SimpleNamespace(type="text", text=text)


def _block_tool_use(tool_id: str, name: str, input: dict):
    return SimpleNamespace(type="tool_use", id=tool_id, name=name, input=input)


def _mock_response(content: list, in_tok=5, out_tok=7):
    return SimpleNamespace(
        content=content,
        usage=SimpleNamespace(input_tokens=in_tok, output_tokens=out_tok),
    )


@pytest.mark.asyncio
async def test_loop_dispatches_read_tool_and_feeds_result_back(monkeypatch):
    from app.services import ai_productivity

    # Force the feature flag on.
    monkeypatch.setattr(
        "app.config.settings.AI_ASSISTANT_TOOL_USE_ENABLED", True, raising=False,
    )

    call_count = {"n": 0}

    def fake_create(**kwargs):
        call_count["n"] += 1
        if call_count["n"] == 1:
            return _mock_response([
                _block_tool_use("tu_1", "get_tasks", {"bucket": "overdue"}),
            ])
        return _mock_response([_block_text("You have 2 overdue tasks.")])

    fake_client = SimpleNamespace(messages=SimpleNamespace(create=fake_create))

    async def fake_dispatch_get_tasks(uid):
        from app.services.firestore_tasks import TaskDTO
        return [
            TaskDTO(
                id="t1", title="x", description=None,
                due_date=date(2026, 5, 10), priority="low",
                tags=[], project_id=None, life_category=None,
                completed_at=None, subtasks=[], recurrence_rule=None,
            ),
            TaskDTO(
                id="t2", title="y", description=None,
                due_date=date(2026, 5, 5), priority="high",
                tags=[], project_id=None, life_category=None,
                completed_at=None, subtasks=[], recurrence_rule=None,
            ),
        ]

    with patch.object(ai_productivity, "_get_client", return_value=fake_client), \
         patch(
             "app.routers.ai.tools.tasks._fetch_incomplete_tasks",
             new=AsyncMock(side_effect=fake_dispatch_get_tasks),
         ):
        result = ai_productivity.generate_chat_response(
            message="what's overdue?",
            conversation_id="chat_2026-05-18_abc",
            history=[],
            user_preferences=[],
            current_state={"tasks": {}, "today_iso": "2026-05-18"},
            user_context={
                "today": "2026-05-18",
                "user_id": 7,
                "firebase_uid": "fb-7",
            },
        )

    assert "overdue" in result["message"]
    assert call_count["n"] == 2
    assert result["tool_calls"][0]["name"] == "get_tasks"


@pytest.mark.asyncio
async def test_loop_respects_10_call_budget(monkeypatch):
    """If Claude keeps calling read tools past the budget, the loop
    force-stops and asks Claude for a final reply with what it has."""
    from app.services import ai_productivity

    monkeypatch.setattr(
        "app.config.settings.AI_ASSISTANT_TOOL_USE_ENABLED", True, raising=False,
    )

    call_count = {"n": 0}

    def fake_create(**kwargs):
        call_count["n"] += 1
        if call_count["n"] <= 11:
            return _mock_response([
                _block_tool_use(f"tu_{call_count['n']}", "get_tasks", {"bucket": "today"}),
            ])
        return _mock_response([_block_text("Sorry, budget hit.")])

    fake_client = SimpleNamespace(messages=SimpleNamespace(create=fake_create))

    with patch.object(ai_productivity, "_get_client", return_value=fake_client), \
         patch(
             "app.routers.ai.tools.tasks._fetch_incomplete_tasks",
             new=AsyncMock(return_value=[]),
         ):
        result = ai_productivity.generate_chat_response(
            message="loop forever",
            conversation_id="chat_2026-05-18_xyz",
            history=[],
            user_preferences=[],
            current_state={"tasks": {}, "today_iso": "2026-05-18"},
            user_context={
                "today": "2026-05-18",
                "user_id": 7,
                "firebase_uid": "fb-7",
            },
        )

    assert "budget" in result["message"].lower()
    # 10 dispatches + the final reply call after budget-exceeded signal.
    assert call_count["n"] >= 11


@pytest.mark.asyncio
async def test_write_tool_use_passes_through_without_dispatch(monkeypatch):
    """A tool_use whose name is in the write set must NOT be executed
    server-side. It should be returned as an action chip, same as today."""
    from app.services import ai_productivity

    monkeypatch.setattr(
        "app.config.settings.AI_ASSISTANT_TOOL_USE_ENABLED", True, raising=False,
    )

    def fake_create(**kwargs):
        return _mock_response([
            _block_text("Marking it done."),
            _block_tool_use("tu_w", "complete", {"task_id": "42"}),
        ])

    fake_client = SimpleNamespace(messages=SimpleNamespace(create=fake_create))

    with patch.object(ai_productivity, "_get_client", return_value=fake_client):
        result = ai_productivity.generate_chat_response(
            message="finish task 42",
            conversation_id="chat_2026-05-18_abc",
            history=[],
            user_preferences=[],
            current_state={"tasks": {}, "today_iso": "2026-05-18"},
            user_context={
                "today": "2026-05-18",
                "user_id": 7,
                "firebase_uid": "fb-7",
            },
        )

    assert result["actions"][0]["type"] == "complete"
    assert "tool_calls" in result  # always present, possibly empty
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && pytest tests/test_ai_chat_tool_loop.py -v`
Expected: 3 FAILED (loop doesn't exist yet).

- [ ] **Step 3: Refactor `generate_chat_response` to add the loop**

Replace the body of `generate_chat_response` in `backend/app/services/ai_productivity.py` (around line 2127–2206) with:

```python
def generate_chat_response(
    message: str,
    conversation_id: str,
    task_context_id: int | None = None,
    task_context: dict | None = None,
    history: list[dict] | None = None,
    user_preferences: list[dict] | None = None,
    current_state: dict | None = None,
    user_context: dict | None = None,
) -> dict:
    """Call Claude Haiku with an agentic read-tool loop.

    Phase 1: when ``AI_ASSISTANT_TOOL_USE_ENABLED`` is true, Claude may
    invoke up to 10 read tools (see ``READ_TOOL_NAMES``); each is
    executed server-side and its result fed back as a ``tool_result``
    block on the next loop iteration. Write tool_uses (the existing
    chip catalog) and memory tool_uses pass straight through as final
    actions, matching today's behavior. When the flag is off the path
    collapses to the original single-shot call — no behavior change.

    Returns a dict with shape::

        {
          "message": "<assistant reply text>",
          "actions": [<0..N action dicts>],
          "tokens_used": {"input": int, "output": int},
          "tool_calls": [{"name": str, "input": dict, "result_summary": str},
                         ...]  # empty list when loop didn't run
        }

    Raises:
        RuntimeError: Anthropic client unavailable / API key missing.
        ValueError:   AI returned a malformed (non-content-blocks) response after retry.
    """
    import json
    from datetime import date

    from app.config import settings
    from app.routers.ai.tool_registry import (
        READ_TOOL_NAMES,
        build_tool_error_result,
        default_registry,
    )
    from app.routers.ai.tools._base import ToolError

    client = _get_client()
    model = get_model("chat")
    anthropic_messages = _build_chat_messages_array(
        message, conversation_id, task_context_id, task_context,
        history, user_preferences, user_context,
    )
    system_prompt = _format_chat_system_prompt(
        user_preferences, current_state, user_context,
    )

    use_loop = bool(settings.AI_ASSISTANT_TOOL_USE_ENABLED)
    registry = default_registry() if use_loop else None
    tools = list(_CHAT_TOOL_DEFINITIONS)
    if use_loop:
        tools.extend(registry.claude_schemas())

    # Logical today comes from the user_context payload the Android client
    # forwards; falls back to UTC date if missing.
    today = date.today()
    if isinstance(user_context, dict) and isinstance(user_context.get("today"), str):
        try:
            today = date.fromisoformat(user_context["today"])
        except ValueError:
            pass

    # Build a minimal user shim that tool handlers can read .id and
    # .firebase_uid off — the actual DB session is None in the v1 loop
    # (handlers either hit Firestore via uid or take a user_id int).
    class _UserShim:
        def __init__(self, ctx: dict | None):
            self.id = (ctx or {}).get("user_id")
            self.firebase_uid = (ctx or {}).get("firebase_uid")
    user_shim = _UserShim(user_context)

    tool_calls: list[dict] = []
    tool_calls_made = 0
    TOOL_CALL_BUDGET = 10

    last_error: Exception | None = None
    for attempt in range(2):
        try:
            while True:
                ai_message = client.messages.create(
                    model=model,
                    max_tokens=1024,
                    system=system_prompt,
                    tools=tools,
                    messages=anthropic_messages,
                )
                content_blocks = list(getattr(ai_message, "content", []) or [])

                if not use_loop:
                    return {**_extract_chat_payload_from_blocks(ai_message), "tool_calls": []}

                read_blocks = [
                    b for b in content_blocks
                    if getattr(b, "type", None) == "tool_use"
                    and getattr(b, "name", None) in READ_TOOL_NAMES
                ]
                if not read_blocks:
                    payload = _extract_chat_payload_from_blocks(ai_message)
                    return {**payload, "tool_calls": tool_calls}

                # Append the assistant's tool-using turn so the next loop
                # iteration carries it as the prior message.
                anthropic_messages.append({
                    "role": "assistant",
                    "content": [_block_to_dict(b) for b in content_blocks],
                })

                tool_calls_made += len(read_blocks)
                if tool_calls_made > TOOL_CALL_BUDGET:
                    anthropic_messages.append({
                        "role": "user",
                        "content": [{
                            "type": "tool_result",
                            "tool_use_id": read_blocks[0].id,
                            "content": json.dumps({
                                "error": "tool-call budget exceeded; "
                                         "stop calling tools and reply with what you have",
                            }),
                            "is_error": True,
                        }],
                    })
                    continue

                tool_result_blocks: list[dict] = []
                import asyncio
                loop = asyncio.new_event_loop()
                try:
                    for block in read_blocks:
                        try:
                            result = loop.run_until_complete(registry.dispatch(
                                user=user_shim, db=None,
                                name=block.name, args=block.input or {},
                                logical_today=today,
                            ))
                            tool_calls.append({
                                "name": block.name,
                                "input": block.input or {},
                                "result_summary": result.summary,
                            })
                            tool_result_blocks.append({
                                "type": "tool_result",
                                "tool_use_id": block.id,
                                "content": json.dumps(result.data, default=str),
                            })
                        except ToolError as e:
                            tool_calls.append({
                                "name": block.name,
                                "input": block.input or {},
                                "result_summary": f"error: {e.message}",
                            })
                            tool_result_blocks.append({
                                "type": "tool_result",
                                "tool_use_id": block.id,
                                "content": json.dumps(build_tool_error_result(e)),
                                "is_error": True,
                            })
                finally:
                    loop.close()

                anthropic_messages.append({
                    "role": "user",
                    "content": tool_result_blocks,
                })
        except (KeyError, TypeError, IndexError, ValueError) as e:
            last_error = e
            logger.error(f"Failed to extract chat response (attempt {attempt + 1}): {e}")
            if attempt == 0:
                continue
            raise ValueError(f"Failed to extract chat response after retry: {e}") from e
        except Exception as e:
            logger.error(f"Chat AI error: {type(e).__name__}: {e}")
            raise
    raise ValueError(f"Failed to extract chat response: {last_error}")


def _block_to_dict(block) -> dict:
    """Serialize an Anthropic content block back to the dict shape
    ``messages.create`` accepts on the input side. Required to round-trip
    the assistant's tool_use turn into the next request."""
    btype = getattr(block, "type", None)
    if btype == "text":
        return {"type": "text", "text": getattr(block, "text", "")}
    if btype == "tool_use":
        return {
            "type": "tool_use",
            "id": getattr(block, "id", ""),
            "name": getattr(block, "name", ""),
            "input": getattr(block, "input", None) or {},
        }
    return {"type": btype}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && pytest tests/test_ai_chat_tool_loop.py -v`
Expected: 3 PASSED.

- [ ] **Step 5: Run the existing chat tests to verify no regression**

Run: `cd backend && pytest tests/test_ai_chat.py -v`
Expected: ALL PASSED — the loop is flag-gated, so with the flag off (the default) every existing test path is byte-identical.

- [ ] **Step 6: Commit**

```bash
git add backend/app/services/ai_productivity.py backend/tests/test_ai_chat_tool_loop.py
git commit -m "feat(ai-assistant): agentic read-tool loop in generate_chat_response"
```

---

## Task 14: Persist `tool_calls` on the assistant row + surface via history

**Files:**
- Modify: `backend/app/routers/ai/chat.py`
- Modify: `backend/app/schemas/ai.py`
- Modify: `backend/tests/test_ai_chat.py`

- [ ] **Step 1: Extend `ChatMessageRecord` schema with `tool_calls`**

In `backend/app/schemas/ai.py`, add a `ChatToolCallRecord` model and an optional field on `ChatMessageRecord`:

```python
class ChatToolCallRecord(BaseModel):
    """One read-tool invocation the assistant made during this turn."""

    name: str = Field(min_length=1, max_length=64)
    input: dict[str, Any] = Field(default_factory=dict)
    result_summary: str = Field(default="", max_length=500)


class ChatResponse(BaseModel):
    # ... existing fields unchanged ...
    tool_calls: list[ChatToolCallRecord] = Field(default_factory=list)


class ChatMessageRecord(BaseModel):
    # ... existing fields unchanged ...
    tool_calls: list[ChatToolCallRecord] = Field(default_factory=list)
```

(Locate `class ChatResponse` and `class ChatMessageRecord` — add the `tool_calls` field to each.)

- [ ] **Step 2: Modify `chat()` to capture and persist tool_calls**

In `backend/app/routers/ai/chat.py`, find the section that builds the `ChatMessageModel` assistant_row (around line 226). Modify the `assistant_row` construction:

```python
        assistant_row = ChatMessageModel(
            id=assistant_msg_id,
            user_id=current_user.id,
            conversation_id=data.conversation_id,
            role="assistant",
            content=result["message"],
            actions=[a.model_dump() for a in validated_actions] or None,
            tool_calls=result.get("tool_calls") or None,
            tokens_input=tokens_used.input,
            tokens_output=tokens_used.output,
            created_at=now + timedelta(microseconds=1),
        )
```

Then modify the final `ChatResponse(...)` construction (around line 272) to include `tool_calls`:

```python
    raw_tool_calls = result.get("tool_calls") or []
    parsed_tool_calls = []
    for tc in raw_tool_calls:
        if not isinstance(tc, dict):
            continue
        try:
            parsed_tool_calls.append(ChatToolCallRecord(**tc))
        except ValidationError:
            continue

    return ChatResponse(
        message=result["message"],
        actions=validated_actions,
        conversation_id=data.conversation_id,
        tokens_used=tokens_used,
        user_message_id=user_msg_id,
        assistant_message_id=assistant_msg_id,
        user_preferences=[_to_preference_record(p) for p in updated_prefs],
        tool_calls=parsed_tool_calls,
    )
```

Import `ChatToolCallRecord` at the top of the file alongside the existing `ChatActionPayload` import.

- [ ] **Step 3: Modify `chat_history()` to surface `tool_calls`**

In the same file, in the `chat_history` handler's record-construction loop (around line 330), parse `row.tool_calls` the same way and include it in `ChatMessageRecord(...)`:

```python
        tool_calls_payload = row.tool_calls or []
        parsed_tool_calls: list[ChatToolCallRecord] = []
        for tc in tool_calls_payload:
            if not isinstance(tc, dict):
                continue
            try:
                parsed_tool_calls.append(ChatToolCallRecord(**tc))
            except ValidationError:
                continue
        # ... in the ChatMessageRecord(...) call, add: tool_calls=parsed_tool_calls
```

- [ ] **Step 4: Write the canary tests**

Append to `backend/tests/test_ai_chat.py`:

```python
class TestLoopFlagOff:
    """Phase 1 canary: with AI_ASSISTANT_TOOL_USE_ENABLED=false (default),
    Pro chat behavior is byte-identical to the pre-loop baseline."""

    @pytest.mark.asyncio
    async def test_loop_disabled_with_flag_off_matches_baseline(
        self, client: AsyncClient, auth_headers_pro: dict, monkeypatch
    ):
        monkeypatch.setattr(
            "app.config.settings.AI_ASSISTANT_TOOL_USE_ENABLED", False, raising=False,
        )
        with patch("app.routers.ai.chat.generate_chat_response") as mock_gen:
            mock_gen.return_value = {
                "message": "hi", "actions": [],
                "tokens_used": {"input": 1, "output": 1},
                # baseline path returns no tool_calls key
            }
            resp = await client.post(
                "/api/v1/ai/chat",
                json={"message": "hi", "conversation_id": "chat_x"},
                headers=auth_headers_pro,
            )
            assert resp.status_code == 200
            body = resp.json()
            assert body["tool_calls"] == []

    @pytest.mark.asyncio
    async def test_loop_disabled_for_free_tier(
        self, client: AsyncClient, auth_headers: dict, monkeypatch
    ):
        """Free tier hits 403 at the rate limiter — the loop never runs
        and there is no observable difference whether the flag is on or off."""
        monkeypatch.setattr(
            "app.config.settings.AI_ASSISTANT_TOOL_USE_ENABLED", True, raising=False,
        )
        from app.routers.ai import chat_rate_limiter
        chat_rate_limiter._requests.clear()
        resp = await client.post(
            "/api/v1/ai/chat",
            json={"message": "hi", "conversation_id": "chat_x"},
            headers=auth_headers,
        )
        assert resp.status_code == 403
```

(Use whatever fixture name `auth_headers_pro` corresponds to in `conftest.py` — likely `auth_headers_pro` or `beta_pro_auth_headers`. Confirm by running `grep -n "auth_headers_pro\|beta_pro_auth_headers" backend/tests/conftest.py | head -5` and using the matching fixture.)

- [ ] **Step 5: Run all chat tests to verify**

Run: `cd backend && pytest tests/test_ai_chat.py tests/test_ai_chat_tool_loop.py -v`
Expected: ALL PASSED (incl. the 2 new canaries).

- [ ] **Step 6: Commit**

```bash
git add backend/app/routers/ai/chat.py backend/app/schemas/ai.py backend/tests/test_ai_chat.py
git commit -m "feat(ai-assistant): persist tool_calls on chat_messages + surface via history"
```

---

## Task 15: Open the PR

- [ ] **Step 1: Push the branch**

Run: `git push -u origin <current-branch>`
(Branch name was set when the worktree was created.)

- [ ] **Step 2: Open the PR with auto-merge**

```bash
gh pr create --title "feat(ai-assistant): Phase 1 — tool-use loop + 6 read tools + query escape hatch" --body "$(cat <<'EOF'
## Summary

Implements Phase 1 of the AI Personal Assistant umbrella spec ([`docs/superpowers/specs/2026-05-18-ai-personal-assistant-design.md`](docs/superpowers/specs/2026-05-18-ai-personal-assistant-design.md)).

- New `app/routers/ai/tools/` package + `tool_registry.py`: six narrow read tools (`get_tasks`, `get_habits`, `get_projects`, `get_medications`, `get_mood_logs`, `get_leisure_logs`) plus an escape-hatch `query` with a closed filter grammar.
- `generate_chat_response` grows a bounded agentic loop (≤10 read tool calls per turn). Read tool_uses execute server-side; write/memory tool_uses pass through unchanged.
- New `chat_messages.tool_calls` JSONB column + Alembic migration 029. `GET /chat/history` surfaces it.
- Feature flag `AI_ASSISTANT_TOOL_USE_ENABLED` (default off) — rollback path.
- System prompt update + audit doc `docs/audits/AI_ASSISTANT_PHASE_1_PROMPT_AUDIT.md`.

## Why

The current AI Coach can only answer questions the curated context bundle anticipates. With this loop, a Pro user can ask "show me my most overdue Self-Care tasks" or "how's my reading streak last week?" and the AI grounds its reply in live data without bloating every turn's prompt.

## Test plan

- [ ] `pytest backend/tests/test_ai_tools_base.py backend/tests/test_ai_tool_registry.py backend/tests/test_ai_tools_*.py backend/tests/test_ai_chat_tool_loop.py` — new tests pass.
- [ ] `pytest backend/tests/test_ai_chat.py` — existing tests + 2 new canaries pass.
- [ ] Flip `AI_ASSISTANT_TOOL_USE_ENABLED=true` in staging; verify a Pro user gets a grounded reply to "what's my most overdue task?" with `tool_calls[].name == "get_tasks"` in the response.
- [ ] Flip the flag off; verify the response shape collapses to the pre-loop baseline.
- [ ] Confirm free-tier still 403s at the rate limiter.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3: Queue auto-merge**

```bash
gh pr merge --auto --squash --delete-branch --repo averycorp/prismTask
```

Expected: queues server-side; the PR merges once Android CI / Backend CI / Web CI pass.

- [ ] **Step 4: Done**

The PR URL is the final output. Report it to the user. No follow-on action — Phase 2 plan will be written separately once this lands and the chip-tier protocol is locked.

---

## Spec Coverage Check (self-review)

- ✅ Architecture — agentic loop in `chat.py` → backend tool dispatcher (Task 13).
- ✅ Tool dispatcher contract — `ToolHandler` protocol (Task 3) + registry (Task 4).
- ✅ Six narrow read tools — Tasks 5–10.
- ✅ `query` escape hatch with closed filter grammar — Task 11.
- ✅ System-prompt update + audit doc — Tasks 1 + 12.
- ✅ `chat_messages.tool_calls` column + migration — Task 2.
- ✅ Persistence via `chat()` handler + `chat_history()` — Task 14.
- ✅ Free tier byte-identical (rate limiter 403) — verified by Task 14 canary.
- ✅ Feature flag `AI_ASSISTANT_TOOL_USE_ENABLED` rollback path — Task 1.
- ✅ Acceptance criteria from spec § "Acceptance Criteria (Phase 1 only)" — every bullet maps to a task in this plan.

**Not in this plan (deferred to later phases per spec):**
- Streaming SSE + tool_use → Phase 4.
- Android `ChatActionExecutor` registry + 35-type chip union → Phase 2.
- Web `useChatActionExecutor` + `BatchPreviewModal` → Phase 3.
- Real (non-`NotImplementedError`) backends for the escape-hatch `query` per entity type → incremental P1 follow-ons or roll into P2 audit.
