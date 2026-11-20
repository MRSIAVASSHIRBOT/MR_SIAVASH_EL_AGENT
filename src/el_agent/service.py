"""Local runtime paths and durable scheduler."""
from __future__ import annotations
import asyncio
import logging
import os
from datetime import datetime, timezone
from pathlib import Path
from el_agent.actions import launch_app
from el_agent.store import TaskStore
logger = logging.getLogger(__name__)

def data_dir() -> Path:
    base = os.environ.get("LOCALAPPDATA")
    return Path(base) / "MR_SIAVASH_EL_AGENT" if base else Path.home() / ".mr_siavash_el_agent"

def screenshot_dir() -> Path:
    return data_dir() / "screenshots"

def task_store() -> TaskStore:
    return TaskStore(data_dir() / "tasks.sqlite3")

async def scheduler_loop(store: TaskStore) -> None:
    while True:
        try:
            for task in store.claim_due(datetime.now(timezone.utc)):
                try:
                    launch_app(task["app_alias"])
                    store.finish(task["task_id"])
                except Exception as exc:
                    logger.exception("Scheduled task failed: %s", task["task_id"])
                    store.finish(task["task_id"], type(exc).__name__ + ": " + str(exc))
        except Exception:
            logger.exception("Scheduler iteration failed")
        await asyncio.sleep(1)


async def hub_heartbeat_loop() -> None:
    """Synchronize only minimal device health metadata with the paired Hub."""
    from el_agent.hub import HEARTBEAT_INTERVAL_SECONDS, HubClientError, heartbeat_once

    while True:
        try:
            await asyncio.to_thread(heartbeat_once)
        except HubClientError as exc:
            logger.warning("Hub heartbeat unavailable: %s", exc)
        except Exception:
            logger.exception("Unexpected Hub heartbeat failure")
        await asyncio.sleep(HEARTBEAT_INTERVAL_SECONDS)
