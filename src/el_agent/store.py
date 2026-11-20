"""SQLite store for visible, bounded local scheduled app launches.

Recovery is deliberately conservative: an action that was running when the
process stopped is marked unknown, never replayed. Pending tasks more than
24 hours late are marked missed instead of being launched unexpectedly.
"""
from __future__ import annotations

import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import uuid4

MAX_MISSED_LATE_SECONDS = 24 * 60 * 60
UNKNOWN_OUTCOME_ERROR = "unknown_outcome_after_restart"
MISSED_DEADLINE_ERROR = "missed_schedule_deadline_elapsed"


def _utc(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("Task timestamps must include a timezone.")
    return value.astimezone(timezone.utc)


class TaskStore:
    def __init__(self, path: Path):
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as db:
            db.execute(
                """CREATE TABLE IF NOT EXISTS scheduled_tasks (
                    task_id TEXT PRIMARY KEY,
                    run_at TEXT NOT NULL,
                    app_alias TEXT NOT NULL,
                    status TEXT NOT NULL
                        CHECK(status IN ('pending','running','completed','failed','cancelled')),
                    created_at TEXT NOT NULL,
                    finished_at TEXT,
                    error TEXT
                )"""
            )
            db.execute(
                "CREATE INDEX IF NOT EXISTS idx_tasks_due ON scheduled_tasks(status,run_at)"
            )

    def _connect(self) -> sqlite3.Connection:
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        return db

    def create_launch(self, run_at: datetime, alias: str) -> dict:
        scheduled = _utc(run_at)
        task = {
            "task_id": str(uuid4()),
            "run_at": scheduled.isoformat(),
            "app_alias": alias,
            "status": "pending",
            "created_at": datetime.now(timezone.utc).isoformat(),
        }
        with self._connect() as db:
            db.execute(
                """INSERT INTO scheduled_tasks
                   (task_id,run_at,app_alias,status,created_at)
                   VALUES(?,?,?,?,?)""",
                (task["task_id"], task["run_at"], alias, "pending", task["created_at"]),
            )
        return task

    @staticmethod
    def _public_task(row: sqlite3.Row) -> dict:
        task = dict(row)
        if task["status"] == "failed" and task["error"] == UNKNOWN_OUTCOME_ERROR:
            task["status"] = "unknown_outcome"
        elif task["status"] == "failed" and task["error"] == MISSED_DEADLINE_ERROR:
            task["status"] = "missed"
        return task

    def list_tasks(self) -> list[dict]:
        with self._connect() as db:
            rows = db.execute(
                "SELECT * FROM scheduled_tasks ORDER BY run_at DESC LIMIT 100"
            ).fetchall()
        return [self._public_task(row) for row in rows]

    def cancel(self, task_id: str) -> bool:
        with self._connect() as db:
            cur = db.execute(
                "UPDATE scheduled_tasks SET status='cancelled' WHERE task_id=? AND status='pending'",
                (task_id,),
            )
            return cur.rowcount == 1

    def reconcile_interrupted(self, now: datetime | None = None) -> int:
        """Fence tasks left running by a previous process; never retry them."""
        recovered_at = _utc(now or datetime.now(timezone.utc)).isoformat()
        with self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            cur = db.execute(
                """UPDATE scheduled_tasks
                   SET status='failed',finished_at=?,error=?
                   WHERE status='running'""",
                (recovered_at, UNKNOWN_OUTCOME_ERROR),
            )
            return cur.rowcount

    def claim_due(self, now: datetime) -> list[dict]:
        current = _utc(now)
        now_text = current.isoformat()
        missed_before = (current - timedelta(seconds=MAX_MISSED_LATE_SECONDS)).isoformat()
        with self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            db.execute(
                """UPDATE scheduled_tasks
                   SET status='failed',finished_at=?,error=?
                   WHERE status='pending' AND run_at<?""",
                (now_text, MISSED_DEADLINE_ERROR, missed_before),
            )
            rows = db.execute(
                """SELECT * FROM scheduled_tasks
                   WHERE status='pending' AND run_at<=? AND run_at>=?
                   ORDER BY run_at LIMIT 10""",
                (now_text, missed_before),
            ).fetchall()
            for row in rows:
                db.execute(
                    "UPDATE scheduled_tasks SET status='running' WHERE task_id=? AND status='pending'",
                    (row["task_id"],),
                )
        return [dict(row) for row in rows]

    def finish(self, task_id: str, error: str | None = None) -> None:
        finished_at = datetime.now(timezone.utc).isoformat()
        safe_error = error[:300] if error else None
        with self._connect() as db:
            db.execute(
                """UPDATE scheduled_tasks
                   SET status=?,finished_at=?,error=?
                   WHERE task_id=? AND status='running'""",
                ("failed" if error else "completed", finished_at, safe_error, task_id),
            )
