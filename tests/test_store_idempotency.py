from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone

from el_agent.store import TaskStore


def test_concurrent_idempotent_submissions_create_one_task(tmp_path):
    store = TaskStore(tmp_path / "tasks.db")
    run_at = datetime.now(timezone.utc) + timedelta(hours=1)

    def submit(_):
        return store.create_launch(
            run_at,
            "notepad",
            idempotency_key="concurrent-schedule-key",
        )

    with ThreadPoolExecutor(max_workers=8) as executor:
        results = list(executor.map(submit, range(16)))

    task_ids = {result["task_id"] for result in results}
    assert len(task_ids) == 1
    assert len(store.list_tasks()) == 1


def test_sqlite_enforces_task_idempotency_foreign_key(tmp_path):
    store = TaskStore(tmp_path / "tasks.db")

    with store._connect() as connection:
        enabled = connection.execute("PRAGMA foreign_keys").fetchone()[0]

    assert enabled == 1
