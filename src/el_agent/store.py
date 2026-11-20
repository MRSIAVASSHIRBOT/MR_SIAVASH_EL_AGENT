"""SQLite store for visible, bounded local scheduled app launches."""
from __future__ import annotations
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

class TaskStore:
    def __init__(self, path: Path):
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS scheduled_tasks (
                task_id TEXT PRIMARY KEY, run_at TEXT NOT NULL,
                app_alias TEXT NOT NULL, status TEXT NOT NULL
                CHECK(status IN ('pending','running','completed','failed','cancelled')),
                created_at TEXT NOT NULL, finished_at TEXT, error TEXT)""")
            db.execute("CREATE INDEX IF NOT EXISTS idx_tasks_due ON scheduled_tasks(status,run_at)")
    def _connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        return db
    def create_launch(self, run_at: datetime, alias: str) -> dict:
        task = {"task_id": str(uuid4()), "run_at": run_at.astimezone(timezone.utc).isoformat(),
                "app_alias": alias, "status": "pending", "created_at": datetime.now(timezone.utc).isoformat()}
        with self._connect() as db:
            db.execute("INSERT INTO scheduled_tasks(task_id,run_at,app_alias,status,created_at) VALUES(?,?,?,?,?)",
                       (task["task_id"],task["run_at"],alias,"pending",task["created_at"]))
        return task
    def list_tasks(self) -> list[dict]:
        with self._connect() as db:
            rows = db.execute("SELECT * FROM scheduled_tasks ORDER BY run_at DESC LIMIT 100").fetchall()
        return [dict(row) for row in rows]
    def cancel(self, task_id: str) -> bool:
        with self._connect() as db:
            cur = db.execute("UPDATE scheduled_tasks SET status='cancelled' WHERE task_id=? AND status='pending'",(task_id,))
            return cur.rowcount == 1
    def claim_due(self, now: datetime) -> list[dict]:
        with self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            rows = db.execute("SELECT * FROM scheduled_tasks WHERE status='pending' AND run_at<=? ORDER BY run_at LIMIT 10",(now.astimezone(timezone.utc).isoformat(),)).fetchall()
            for row in rows:
                db.execute("UPDATE scheduled_tasks SET status='running' WHERE task_id=? AND status='pending'",(row["task_id"],))
            return [dict(row) for row in rows]
    def finish(self, task_id: str, error: str | None = None) -> None:
        with self._connect() as db:
            db.execute("UPDATE scheduled_tasks SET status=?,finished_at=?,error=? WHERE task_id=? AND status='running'",
                       ("failed" if error else "completed",datetime.now(timezone.utc).isoformat(),error[:300] if error else None,task_id))
