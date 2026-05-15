"""Unit tests for app.tasks.weekly_review_generator.

Mocks the Firestore client so the aggregator + writer can be exercised
without firebase-admin. The cron registration (``start_scheduler`` /
``stop_scheduler``) is exercised by a tiny smoke test that asserts the
job lands in the AsyncIO scheduler with the expected trigger.
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any
from unittest.mock import MagicMock

import pytest

from app.tasks import weekly_review_generator as gen


# ---------------------------------------------------------------------------
# Test scaffolding: a minimal Firestore client stub that supports the
# ``client.collection().document().collection().stream()`` /
# ``.set(payload, merge=True)`` shape the generator uses.
# ---------------------------------------------------------------------------


class _FakeDocSnapshot:
    def __init__(self, doc_id: str, data: dict[str, Any], exists: bool = True) -> None:
        self.id = doc_id
        self._data = data
        self.exists = exists

    def to_dict(self) -> dict[str, Any]:
        return dict(self._data)


class _FakeDocument:
    def __init__(self, parent_collection: "_FakeCollection", doc_id: str) -> None:
        self._parent = parent_collection
        self._id = doc_id
        self._subcollections: dict[str, _FakeCollection] = {}

    def collection(self, name: str) -> "_FakeCollection":
        if name not in self._subcollections:
            self._subcollections[name] = _FakeCollection(name)
        return self._subcollections[name]

    def get(self) -> _FakeDocSnapshot:
        data = self._parent.docs.get(self._id)
        if data is None:
            return _FakeDocSnapshot(self._id, {}, exists=False)
        return _FakeDocSnapshot(self._id, data, exists=True)

    def set(self, payload: dict[str, Any], merge: bool = False) -> None:
        if merge and self._id in self._parent.docs:
            merged = dict(self._parent.docs[self._id])
            merged.update(payload)
            self._parent.docs[self._id] = merged
        else:
            self._parent.docs[self._id] = dict(payload)


class _FakeCollection:
    def __init__(self, name: str) -> None:
        self.name = name
        self.docs: dict[str, dict[str, Any]] = {}
        self._documents_cache: dict[str, _FakeDocument] = {}

    def document(self, doc_id: str) -> _FakeDocument:
        if doc_id not in self._documents_cache:
            self._documents_cache[doc_id] = _FakeDocument(self, doc_id)
        return self._documents_cache[doc_id]

    def stream(self):
        for doc_id, data in self.docs.items():
            yield _FakeDocSnapshot(doc_id, data, exists=True)


class _FakeFirestoreClient:
    def __init__(self) -> None:
        self._root: dict[str, _FakeCollection] = {}

    def collection(self, name: str) -> _FakeCollection:
        if name not in self._root:
            self._root[name] = _FakeCollection(name)
        return self._root[name]


# ---------------------------------------------------------------------------
# Window math
# ---------------------------------------------------------------------------


def test_last_week_window_anchored_to_prior_monday() -> None:
    # Sunday 2026-05-10 23:30 UTC -> prior week is Mon 2026-05-04 ..
    # Mon 2026-05-11 (this week's Monday, exclusive).
    ref = datetime(2026, 5, 10, 23, 30, tzinfo=timezone.utc)
    start, end, iso = gen._last_week_window(ref)
    assert start == datetime(2026, 5, 4, 0, 0, tzinfo=timezone.utc)
    assert end == datetime(2026, 5, 11, 0, 0, tzinfo=timezone.utc)
    assert iso == "2026-05-04"


def test_last_week_window_when_called_on_monday() -> None:
    # Monday 2026-05-11 00:30 UTC -> prior week ends Monday 2026-05-11,
    # so the doc id should be 2026-05-04.
    ref = datetime(2026, 5, 11, 0, 30, tzinfo=timezone.utc)
    _, _, iso = gen._last_week_window(ref)
    assert iso == "2026-05-04"


# ---------------------------------------------------------------------------
# _aggregate_user_week
# ---------------------------------------------------------------------------


def _ms(dt: datetime) -> int:
    return int(dt.timestamp() * 1000)


def test_aggregate_counts_completed_slipped_and_categories() -> None:
    client = _FakeFirestoreClient()
    tasks = client.collection("users").document("u1").collection("tasks")
    week_start = datetime(2026, 5, 4, 0, 0, tzinfo=timezone.utc)
    week_end = datetime(2026, 5, 11, 0, 0, tzinfo=timezone.utc)
    mid_week_ms = _ms(datetime(2026, 5, 6, 12, 0, tzinfo=timezone.utc))
    after_week_ms = _ms(datetime(2026, 5, 12, 12, 0, tzinfo=timezone.utc))

    # Completed inside the window — work category.
    tasks.docs["t1"] = {
        "isCompleted": True,
        "completedAt": mid_week_ms,
        "lifeCategory": "work",
    }
    # Completed inside the window — self_care category (the literal
    # Android storage form is "self_care" — match exactly).
    tasks.docs["t2"] = {
        "isCompleted": True,
        "completedAt": mid_week_ms,
        "lifeCategory": "self_care",
    }
    # Completed *after* the window — should NOT count.
    tasks.docs["t3"] = {
        "isCompleted": True,
        "completedAt": after_week_ms,
        "lifeCategory": "work",
    }
    # Slipped: due in window, not completed, not archived.
    tasks.docs["t4"] = {
        "isCompleted": False,
        "dueDate": mid_week_ms,
        "archivedAt": None,
    }
    # Slipped-but-archived → excluded.
    tasks.docs["t5"] = {
        "isCompleted": False,
        "dueDate": mid_week_ms,
        "archivedAt": _ms(datetime(2026, 5, 7, tzinfo=timezone.utc)),
    }
    # Out-of-window due — excluded.
    tasks.docs["t6"] = {"isCompleted": False, "dueDate": after_week_ms}

    # Two habit completions, one in window and one skip-row that must
    # be filtered out.
    habits = client.collection("users").document("u1").collection("habit_completions")
    habits.docs["h1"] = {"completedAt": mid_week_ms}
    habits.docs["h2"] = {"completedAt": mid_week_ms, "isSkip": True}
    habits.docs["h3"] = {"completedAt": after_week_ms}

    metrics = gen._aggregate_user_week(client, "u1", week_start, week_end)
    assert metrics["completed_count"] == 2
    assert metrics["slipped_count"] == 1
    assert metrics["habit_hits"] == 1
    assert metrics["by_category"]["work"] == 1
    assert metrics["by_category"]["self_care"] == 1
    assert metrics["by_category"]["personal"] == 0
    assert metrics["by_category"]["health"] == 0
    assert metrics["completion_rate"] == pytest.approx(2 / 3, abs=1e-4)
    assert metrics["generated_by"] == "cron"


def test_aggregate_empty_user_returns_zero_metrics() -> None:
    client = _FakeFirestoreClient()
    week_start = datetime(2026, 5, 4, tzinfo=timezone.utc)
    week_end = datetime(2026, 5, 11, tzinfo=timezone.utc)
    metrics = gen._aggregate_user_week(client, "u_empty", week_start, week_end)
    assert metrics["completed_count"] == 0
    assert metrics["slipped_count"] == 0
    assert metrics["habit_hits"] == 0
    assert metrics["completion_rate"] == 0.0


# ---------------------------------------------------------------------------
# _write_review
# ---------------------------------------------------------------------------


def test_write_review_uses_week_start_iso_as_doc_id_and_merges() -> None:
    client = _FakeFirestoreClient()
    week_start_ms = _ms(datetime(2026, 5, 4, tzinfo=timezone.utc))
    metrics = {"completed_count": 3, "slipped_count": 1}
    gen._write_review(client, "u1", "2026-05-04", week_start_ms, metrics)

    col = client.collection("users").document("u1").collection("weekly_reviews")
    assert "2026-05-04" in col.docs
    payload = col.docs["2026-05-04"]
    assert payload["week_start_date"] == "2026-05-04"
    assert payload["week_start_ms"] == week_start_ms
    assert json.loads(payload["metrics_json"])["completed_count"] == 3
    assert payload["created_at"] == payload["updated_at"]
    # ai_insights_json must NOT be written so a client narrative isn't
    # clobbered on re-run.
    assert "ai_insights_json" not in payload


def test_write_review_preserves_ai_insights_and_created_at_on_reruns() -> None:
    client = _FakeFirestoreClient()
    col = client.collection("users").document("u1").collection("weekly_reviews")
    # Pre-seed the row as if a client had written AI insights to it.
    col.docs["2026-05-04"] = {
        "week_start_date": "2026-05-04",
        "ai_insights_json": '{"narrative":"client-supplied"}',
        "created_at": 12345,
        "updated_at": 12345,
    }
    week_start_ms = _ms(datetime(2026, 5, 4, tzinfo=timezone.utc))
    gen._write_review(client, "u1", "2026-05-04", week_start_ms, {"completed_count": 9})
    payload = col.docs["2026-05-04"]
    # ai_insights_json survives the merge.
    assert payload["ai_insights_json"] == '{"narrative":"client-supplied"}'
    # created_at preserved from the existing row.
    assert payload["created_at"] == 12345
    # updated_at advanced past created_at on the rerun.
    assert payload["updated_at"] > 12345


# ---------------------------------------------------------------------------
# Scheduler smoke
# ---------------------------------------------------------------------------


def test_start_scheduler_registers_cron_job_then_stop_clears_it() -> None:
    if gen.AsyncIOScheduler is None:
        pytest.skip("apscheduler not installed")
    gen.stop_scheduler()  # idempotent guard
    gen.start_scheduler()
    try:
        assert gen._scheduler is not None
        jobs = gen._scheduler.get_jobs()
        ids = [j.id for j in jobs]
        assert "weekly_review_generator" in ids
    finally:
        gen.stop_scheduler()
    assert gen._scheduler is None


# ---------------------------------------------------------------------------
# run_weekly_review_for_all_users end-to-end (with mocked DB + Firestore)
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_run_writes_one_doc_per_active_firebase_user(monkeypatch) -> None:
    fake_client = _FakeFirestoreClient()
    # Seed two users with one in-window completion each so the rows
    # land with non-zero counts.
    mid_week_ms = _ms(datetime(2026, 5, 6, 12, 0, tzinfo=timezone.utc))
    for uid in ("uid-A", "uid-B"):
        fake_client.collection("users").document(uid).collection("tasks").docs["t"] = {
            "isCompleted": True,
            "completedAt": mid_week_ms,
            "lifeCategory": "work",
        }

    # Patch the Firestore client factory the runner uses.
    monkeypatch.setattr(
        "app.services.firestore_tasks._get_firestore_client",
        lambda: fake_client,
    )

    # Patch the DB session to yield three users: two active, one with
    # no firebase_uid (must be skipped), one pending deletion (skipped).
    user_active = MagicMock(spec=["firebase_uid", "deletion_pending_at", "id"])
    user_active.firebase_uid = "uid-A"
    user_active.deletion_pending_at = None
    user_active.id = 1

    user_active_2 = MagicMock(spec=["firebase_uid", "deletion_pending_at", "id"])
    user_active_2.firebase_uid = "uid-B"
    user_active_2.deletion_pending_at = None
    user_active_2.id = 2

    user_local_only = MagicMock(spec=["firebase_uid", "deletion_pending_at", "id"])
    user_local_only.firebase_uid = None
    user_local_only.deletion_pending_at = None
    user_local_only.id = 3

    user_deleting = MagicMock(spec=["firebase_uid", "deletion_pending_at", "id"])
    user_deleting.firebase_uid = "uid-C"
    user_deleting.deletion_pending_at = datetime(2026, 5, 1, tzinfo=timezone.utc)
    user_deleting.id = 4

    class _ScalarsResult:
        def __init__(self, items): self._items = items
        def all(self): return self._items

    class _ExecuteResult:
        def __init__(self, items): self._items = items
        def scalars(self): return _ScalarsResult(self._items)

    class _FakeSession:
        async def __aenter__(self): return self
        async def __aexit__(self, *exc): return False
        async def execute(self, _query):
            return _ExecuteResult(
                [user_active, user_active_2, user_local_only, user_deleting]
            )

    monkeypatch.setattr(
        "app.tasks.weekly_review_generator.async_session_factory",
        lambda: _FakeSession(),
    )

    ref = datetime(2026, 5, 10, 23, 30, tzinfo=timezone.utc)
    await gen.run_weekly_review_for_all_users(reference=ref)

    # Two docs written (one per active user), skipping the local-only
    # user and the user pending deletion.
    col_a = fake_client.collection("users").document("uid-A").collection("weekly_reviews")
    col_b = fake_client.collection("users").document("uid-B").collection("weekly_reviews")
    col_c = fake_client.collection("users").document("uid-C").collection("weekly_reviews")
    assert "2026-05-04" in col_a.docs
    assert "2026-05-04" in col_b.docs
    # uid-C (deletion_pending_at set) must be skipped.
    assert "2026-05-04" not in col_c.docs
    metrics = json.loads(col_a.docs["2026-05-04"]["metrics_json"])
    assert metrics["completed_count"] == 1
    assert metrics["by_category"]["work"] == 1
