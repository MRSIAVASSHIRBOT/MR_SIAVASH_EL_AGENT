from __future__ import annotations

import asyncio
import threading
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from el_agent import service
from el_agent.store import (
    MAX_MISSED_LATE_SECONDS,
    MISSED_DEADLINE_ERROR,
    UNKNOWN_OUTCOME_ERROR,
    TaskStore,
)


def test_interrupted_running_task_is_unknown_and_never_reclaimed(tmp_path: Path):
    store = TaskStore(tmp_path / "tasks.sqlite3")
    now = datetime.now(timezone.utc)
    task = store.create_launch(now - timedelta(seconds=10), "notepad")

    assert len(store.claim_due(now)) == 1
    recovered = store.reconcile_interrupted(now + timedelta(seconds=30))

    assert recovered == 1
    rows = store.list_tasks()
    assert rows[0]["task_id"] == task["task_id"]
    assert rows[0]["status"] == "unknown_outcome"
    assert rows[0]["error"] == UNKNOWN_OUTCOME_ERROR
    assert store.claim_due(now + timedelta(seconds=31)) == []


def test_pending_task_more_than_24_hours_late_is_marked_missed(tmp_path: Path):
    store = TaskStore(tmp_path / "tasks.sqlite3")
    now = datetime.now(timezone.utc)
    task = store.create_launch(
        now - timedelta(seconds=MAX_MISSED_LATE_SECONDS + 1),
        "calculator",
    )

    assert store.claim_due(now) == []
    row = next(item for item in store.list_tasks() if item["task_id"] == task["task_id"])
    assert row["status"] == "missed"
    assert row["error"] == MISSED_DEADLINE_ERROR


def test_due_task_within_lateness_window_is_claimed_once(tmp_path: Path):
    store = TaskStore(tmp_path / "tasks.sqlite3")
    now = datetime.now(timezone.utc)
    task = store.create_launch(now - timedelta(hours=23), "explorer")

    claimed = store.claim_due(now)

    assert len(claimed) == 1
    assert claimed[0]["task_id"] == task["task_id"]
    assert store.claim_due(now) == []


def test_finish_preserves_normal_completed_and_failed_states(tmp_path: Path):
    store = TaskStore(tmp_path / "tasks.sqlite3")
    now = datetime.now(timezone.utc)
    succeeded = store.create_launch(now, "notepad")
    failed = store.create_launch(now, "calculator")
    store.claim_due(now)
    store.finish(succeeded["task_id"])
    store.finish(failed["task_id"], "RuntimeError: launch failed")

    rows = {item["task_id"]: item for item in store.list_tasks()}
    assert rows[succeeded["task_id"]]["status"] == "completed"
    assert rows[failed["task_id"]]["status"] == "failed"
    assert rows[failed["task_id"]]["error"] == "RuntimeError: launch failed"


def test_task_store_rejects_naive_datetimes(tmp_path: Path):
    store = TaskStore(tmp_path / "tasks.sqlite3")
    with pytest.raises(ValueError, match="timezone"):
        store.create_launch(datetime(2026, 11, 20), "notepad")
    with pytest.raises(ValueError, match="timezone"):
        store.claim_due(datetime(2026, 11, 20))


def test_scheduler_startup_reconciles_running_rows_before_polling(tmp_path: Path, monkeypatch):
    store = TaskStore(tmp_path / "tasks.sqlite3")
    now = datetime.now(timezone.utc)
    task = store.create_launch(now - timedelta(seconds=1), "notepad")
    assert len(store.claim_due(now)) == 1
    launched = []
    recovery_finished = threading.Event()
    original_reconcile = store.reconcile_interrupted

    def reconcile_and_signal(current=None):
        result = original_reconcile(current)
        recovery_finished.set()
        return result

    monkeypatch.setattr(store, "reconcile_interrupted", reconcile_and_signal)
    monkeypatch.setattr(service, "launch_app", lambda alias: launched.append(alias))

    async def run_once():
        worker = asyncio.create_task(service.scheduler_loop(store))
        completed = await asyncio.to_thread(recovery_finished.wait, 5)
        assert completed, "scheduler startup recovery did not complete"
        worker.cancel()
        with pytest.raises(asyncio.CancelledError):
            await worker

    asyncio.run(run_once())

    row = next(item for item in store.list_tasks() if item["task_id"] == task["task_id"])
    assert row["status"] == "unknown_outcome"
    assert launched == []
